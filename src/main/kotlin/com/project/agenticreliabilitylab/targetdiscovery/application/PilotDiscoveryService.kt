package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import org.springframework.stereotype.Service

/** Builds the fixed P1 candidate catalogue from the active Profile/OpenAPI allowlist intersection. */
@Service
class PilotDiscoveryService(
    private val profiles: TargetProfileService,
    private val snapshots: TargetKnowledgeSnapshotStore,
) {
    fun find(targetSystemId: String): PilotDiscovery {
        val profile = activeProfile(targetSystemId)
        val openApiPaths = profile.openApiPaths()
        val snapshots = activeOpenApiSnapshots(targetSystemId, profile)
        val allowed = profile.allowedOperations()
        val discovered = snapshots.flatMap { snapshot -> snapshot.content.operations }
            .flatMap { operation -> operation.matches(allowed) }
            .distinctBy { operation -> Triple(operation.method, operation.executionPath, operation.authProfile) }
        val matchedSwaggerOperations = snapshots.sumOf { snapshot -> snapshot.content.operations.count { operation ->
            operation.matches(allowed).isNotEmpty()
        } }
        return PilotDiscovery(
            targetSystemId = targetSystemId,
            profileVersionId = profile.id.toString(),
            openApiPath = openApiPaths.first(),
            openApiPaths = openApiPaths,
            snapshotId = snapshots.first().id.toString(),
            snapshotChecksum = snapshots.first().checksum,
            snapshotChecksums = snapshots.map { snapshot -> snapshot.checksum },
            discoveredOperations = discovered,
            ignoredOperationCount = snapshots.sumOf { snapshot -> snapshot.content.operations.size } -
                matchedSwaggerOperations,
            candidates = candidates(profile, discovered),
        )
    }

    private fun activeProfile(targetSystemId: String): TargetProfileVersion =
        profiles.findActive(targetSystemId)
            ?: throw ClientRequestException(
                "TARGET_PROFILE_NOT_ACTIVE",
                "Target '$targetSystemId' has no active Profile",
            )

    private fun TargetProfileVersion.openApiPaths(): List<String> =
        definition.target.declaredOpenApiPaths().takeIf { paths -> paths.isNotEmpty() }
            ?: throw ClientRequestException(
                "OPENAPI_PATH_NOT_CONFIGURED",
                "Target '$targetSystemId' has no OpenAPI path",
            )

    private fun activeOpenApiSnapshots(
        targetSystemId: String,
        profile: TargetProfileVersion,
    ) = snapshots.findByTarget(targetSystemId, MAX_SNAPSHOTS)
        .filter { candidate ->
            candidate.profileVersionId == profile.id &&
                candidate.content.sources.any { source -> source.type == KnowledgeSourceType.OPENAPI }
        }
        .takeIf { values -> values.isNotEmpty() }
        ?: throw ClientRequestException(
            "OPENAPI_DISCOVERY_NOT_READY",
            "Target '$targetSystemId' has no OpenAPI Snapshot for its active Profile",
        )

    private fun TargetProfileVersion.allowedOperations(): List<AllowedOperation> = buildList {
        definition.genericHttp?.readOnlyOperations?.forEach { operation ->
            add(AllowedOperation("GET", operation.path, operation.operationId, null))
        }
        definition.testSpecExecution?.allowedCalls?.forEach { call -> add(call.toAllowedOperation()) }
    }

    private fun ProfileHttpCallDefinition.toAllowedOperation() =
        AllowedOperation(method.uppercase(), path, operationId, authProfile)

    private fun ExtractedOperation.matches(
        allowed: List<AllowedOperation>,
    ): List<PilotDiscoveredOperation> = allowed
        .filter { declaration ->
            method == declaration.method &&
                (path == declaration.executionPath || declaration.operationId?.let { it == operationId } == true)
        }
        .map { declaration ->
            PilotDiscoveredOperation(
                method = method,
                swaggerPath = path,
                executionPath = declaration.executionPath,
                operationId = operationId,
                authProfile = declaration.authProfile,
                summary = summary,
            )
        }

    @Suppress("LongMethod") // Fixed P1–P4 catalogue is kept together so its required allowlist calls are reviewable.
    private fun candidates(
        profile: TargetProfileVersion,
        discovered: List<PilotDiscoveredOperation>,
    ): List<PilotTestCandidate> {
        val catalogPath = profile.definition.genericHttp?.readOnlyOperations
            ?.firstOrNull()
            ?.path
            ?: PRODUCT_PATH
        val catalog = discovered.find("GET", catalogPath)
        val product = discovered.find("POST", PRODUCT_PATH)
        val order = discovered.find("POST", ORDER_PATH)
        val payment = discovered.find("POST", PAYMENT_PATH)
        return listOf(
            candidate(
                profile = profile,
                id = "availability",
                title = "Health와 상품 카탈로그 가용성",
                description = "등록된 health 경로와 public product catalog의 2xx 응답을 확인합니다.",
                required = listOf("GET $catalogPath" to catalog),
            ),
            candidate(
                profile = profile,
                id = "product-create",
                title = "판매자 상품 생성",
                description = "판매자 역할로 새 테스트 상품을 만들고 생성 응답을 확인합니다.",
                required = listOf("POST $PRODUCT_PATH" to product),
            ),
            candidate(
                profile = profile,
                id = "order-workflow",
                title = "상품 생성 → 구매자 주문",
                description = "새 상품 ID를 capture해 구매자 주문으로 연결하는 workflow입니다.",
                required = listOf("POST $PRODUCT_PATH" to product, "POST $ORDER_PATH" to order),
            ),
            candidate(
                profile = profile,
                id = "payment-success",
                title = "결제 성공 workflow",
                description = "상품과 주문 fixture 뒤 정상 결제 callback과 최종 상태를 확인합니다.",
                required = listOf(
                    "POST $PRODUCT_PATH" to product,
                    "POST $ORDER_PATH" to order,
                    "POST $PAYMENT_PATH" to payment,
                ),
            ),
            candidate(
                profile = profile,
                id = "order-idempotency",
                title = "동일 주문의 idempotency",
                description = "같은 주문 body와 Idempotency-Key를 두 번 보내도 주문은 하나만 만들어져야 합니다.",
                required = listOf("POST $PRODUCT_PATH" to product, "POST $ORDER_PATH" to order),
            ),
            candidate(
                profile = profile,
                id = "order-concurrency",
                title = "주문 동시성 20 × 3",
                description = "같은 상품에 대해 20개 병렬 주문을 3회 실행하고, trial마다 정리합니다.",
                required = listOf("POST $PRODUCT_PATH" to product, "POST $ORDER_PATH" to order),
            ),
            candidate(
                profile = profile,
                id = "payment-failure-recovery",
                title = "결제 장애 주입과 복구",
                description = "허용된 PAYMENT_FAILURE를 next-1 범위로 주입·해제한 뒤 새 주문 결제의 복구를 확인합니다.",
                required = listOf(
                    "POST $PRODUCT_PATH" to product,
                    "POST $ORDER_PATH" to order,
                    "POST $PAYMENT_PATH" to payment,
                ),
            ),
        )
    }

    private fun candidate(
        profile: TargetProfileVersion,
        id: String,
        title: String,
        description: String,
        required: List<Pair<String, PilotDiscoveredOperation?>>,
    ): PilotTestCandidate {
        val missing = required
            .filter { (_, operation) -> operation == null }
            .map { (label, _) -> label }
            .toMutableList()
        missing += PilotTemplateConfigurationReadiness.missing(profile, id)
        return PilotTestCandidate(
            id = id,
            title = title,
            description = description,
            readiness = if (missing.isEmpty()) {
                PilotCandidateReadiness.READY
            } else {
                PilotCandidateReadiness.NOT_READY
            },
            operations = required.mapNotNull { (_, operation) -> operation }.distinct(),
            missingOperations = missing,
        )
    }

    private fun List<PilotDiscoveredOperation>.find(method: String, path: String): PilotDiscoveredOperation? =
        firstOrNull { operation -> operation.method == method && operation.executionPath == path }

    private data class AllowedOperation(
        val method: String,
        val executionPath: String,
        val operationId: String?,
        val authProfile: String?,
    )

    private companion object {
        const val MAX_SNAPSHOTS = 50
        const val PRODUCT_PATH = "/api/products"
        const val ORDER_PATH = "/api/orders"
        const val PAYMENT_PATH = "/api/payments/webhook"
    }
}

/** Keeps READY's non-Swagger dependencies explicit and independent of the discovery catalogue itself. */
private object PilotTemplateConfigurationReadiness {
    fun missing(profile: TargetProfileVersion, candidateId: String): List<String> {
        val requirements = PilotTestTemplateFactory.requirements(candidateId)
        val execution = profile.definition.testSpecExecution
        val environmentMissing = listOfNotNull(
            "LOCAL/TEST Target environment".takeIf {
                profile.definition.target.environment !in EXECUTABLE_ENVIRONMENTS
            },
        )
        if (execution == null || !execution.executionEnabled) {
            return environmentMissing + "enabled test-spec-execution Profile"
        }
        return environmentMissing + execution.missingTemplateConfiguration(
            requirements,
            candidateId,
            profile.definition.target.healthPath,
        )
    }

    private fun TestSpecExecutionProfileDefinition.missingTemplateConfiguration(
        requirements: PilotTemplateRequirements,
        candidateId: String,
        healthPath: String,
    ): List<String> = basicRequirementsMissing(requirements) +
        harnessRequirementsMissing(requirements) +
        faultRequirementsMissing(requirements) +
        healthRequirementMissing(candidateId, healthPath)

    private fun TestSpecExecutionProfileDefinition.basicRequirementsMissing(
        requirements: PilotTemplateRequirements,
    ): List<String> = buildList {
        if (requirements.stateChanging && !stateChangingAllowed) add("state-changing-allowed")
        (requirements.requiredAuthProfiles - authProfiles).sorted().forEach { role ->
            add("auth profile '$role'")
        }
    }

    private fun TestSpecExecutionProfileDefinition.harnessRequirementsMissing(
        requirements: PilotTemplateRequirements,
    ): List<String> = buildList {
        val resetPlan = reset
        if (
            resetPlan?.method != CleanupMethod.ENVIRONMENT_RESET || resetPlan.hook?.authProfile != HARNESS ||
            resetPlan.verifications.isEmpty()
        ) add("verified Harness environment reset")

        val harness = observationSources.firstOrNull { source ->
            source.kind == ProfileObservationSourceKind.HARNESS_STATE && source.authProfile == HARNESS
        }
        if (harness == null) add("Harness state observation source")
        else (requirements.requiredHarnessFields - harness.fields).sorted().forEach { field ->
            add("Harness field '$field'")
        }
    }

    private fun TestSpecExecutionProfileDefinition.faultRequirementsMissing(
        requirements: PilotTemplateRequirements,
    ): List<String> = requirements.requiredFaultType?.let { faultType ->
        buildList {
            if (faultType !in supportedFaults) add("supported fault '$faultType'")
            val fault = faultInjection
            if (fault?.injectEndpoint?.authProfile != HARNESS || fault.releaseEndpoint.authProfile != HARNESS) {
                add("Harness fault inject/release hooks")
            }
        }
    } ?: emptyList()

    private fun TestSpecExecutionProfileDefinition.healthRequirementMissing(
        candidateId: String,
        healthPath: String,
    ): List<String> = listOfNotNull(
        "allowlisted health GET $healthPath".takeIf {
            candidateId == PilotTestTemplateFactory.AVAILABILITY &&
                allowedCalls.none { call ->
                    call.method.equals("GET", ignoreCase = true) && call.path == healthPath
                }
        },
    )

    private const val HARNESS = "harness"
    private val EXECUTABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
}
