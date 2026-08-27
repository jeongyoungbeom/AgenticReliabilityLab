package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.targetdiscovery.application.TargetOpenApiDocumentFetcher
import com.project.agenticreliabilitylab.targetdiscovery.application.TargetProfileActivationWorkflow
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class QuickTargetProfileRegistration(
    val name: String,
    val baseUrl: String,
    val environment: TargetEnvironment,
)

/**
 * Registers the fixed Pilot contract in one action. The customer supplies only identity and origin; all executable
 * endpoints remain code-owned, reviewable allowlists instead of inferred Swagger operations.
 */
@Service
class QuickTargetProfileRegistrationWorkflow(
    private val factory: QuickTargetProfileFactory,
    private val openApiDiscovery: QuickOpenApiDiscovery,
    private val profiles: TargetProfileService,
    private val activation: TargetProfileActivationWorkflow,
) {
    @Transactional
    fun register(
        request: QuickTargetProfileRegistration,
        actor: String,
        correlationId: String,
    ): TargetProfileVersion {
        val standardProfile = factory.create(request)
        val discoveredPaths = openApiDiscovery.findAllowedDocuments(standardProfile)
        val definition = standardProfile.copy(
            target = standardProfile.target.copy(openApiPaths = discoveredPaths),
        )
        val imported = profiles.import(definition, TargetProfileSource.USER_IMPORT, actor, correlationId)
        return if (imported.status == TargetProfileStatus.ACTIVE) imported
        else activation.activate(imported.id, actor, correlationId)
    }
}

/** Resolves the submitted hostname once to create exact, visible CIDR entries for the generated Profile. */
fun interface TargetOriginAddressResolver {
    fun resolveExactCidrs(origin: URI): Set<String>
}

@Component
class DnsTargetOriginAddressResolver : TargetOriginAddressResolver {
    override fun resolveExactCidrs(origin: URI): Set<String> = try {
        InetAddress.getAllByName(origin.host)
            .mapTo(linkedSetOf()) { address ->
                val literal = InetAddress.getByAddress(address.address).hostAddress
                "$literal/${address.address.size * BITS_PER_BYTE}"
            }
            .also { addresses ->
                require(addresses.isNotEmpty()) { "Target host '${origin.host}' returned no addresses" }
            }
    } catch (exception: UnknownHostException) {
        throw IllegalArgumentException("Target host '${origin.host}' could not be resolved", exception)
    }

    private companion object {
        const val BITS_PER_BYTE = 8
    }
}

/** Builds the standard seller/buyer/harness Profile; non-standard contracts remain an explicit YAML use case. */
@Component
class QuickTargetProfileFactory(
    private val addressResolver: TargetOriginAddressResolver,
) {
    fun create(request: QuickTargetProfileRegistration): TargetProfileDefinition {
        require(request.environment in EXECUTABLE_ENVIRONMENTS) {
            "Quick registration supports LOCAL or TEST Targets only; use the advanced Profile for other environments"
        }
        val origin = request.baseUrl.toHttpOrigin()
        val targetId = generatedTargetId(request.name, origin)
        return TargetProfileDefinition(
            target = TargetRegistrationDefinition(
                id = targetId,
                name = request.name.trim(),
                adapterType = HTTP_TARGET,
                environment = request.environment,
                baseUrl = origin.toString(),
                allowedOrigin = origin.toString(),
                allowedCidrs = addressResolver.resolveExactCidrs(origin),
                healthPath = HEALTH_PATH,
                sourceRepository = "quick-registration:${origin.host}",
                identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
                capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
                enabled = true,
            ),
            genericHttp = GenericHttpProfileDefinition(
                executionEnabled = true,
                hostResourceGroup = targetId,
                maxBatchSize = 5,
                requestTimeout = Duration.ofSeconds(STANDARD_REQUEST_TIMEOUT_SECONDS),
                readOnlyOperations = listOf(
                    ReadOnlyOperationDefinition(
                        id = "product-catalog",
                        title = "Public product catalog is reachable",
                        description = "The public product catalog must respond through the registered Target origin.",
                        path = PRODUCT_PATH,
                        expectedStatusCodes = setOf(HTTP_OK),
                        operationId = "getProducts",
                    ),
                ),
                failureInjectionPlanningEnabled = false,
                failureInjectionCandidates = emptyList(),
            ),
            testSpecExecution = standardExecutionProfile(),
        )
    }

    private fun standardExecutionProfile() = TestSpecExecutionProfileDefinition(
        executionEnabled = true,
        allowedCalls = listOf(
            ProfileHttpCallDefinition("GET", HEALTH_PATH),
            ProfileHttpCallDefinition("GET", PRODUCT_PATH, operationId = "getProducts"),
            ProfileHttpCallDefinition("GET", "/api/products/my", "seller", "getMyProducts"),
            ProfileHttpCallDefinition("GET", "/api/orders", "buyer", "getMyOrders"),
            ProfileHttpCallDefinition("POST", PRODUCT_PATH, "seller", "createProduct_1"),
            ProfileHttpCallDefinition("POST", "/api/orders", "buyer", "orders"),
            ProfileHttpCallDefinition("POST", PAYMENT_PATH, operationId = "webhook"),
        ),
        authProfiles = setOf("seller", "buyer", "harness"),
        observationSources = listOf(
            ProfileObservationSourceDefinition(
                name = "harness",
                kind = ProfileObservationSourceKind.HARNESS_STATE,
                endpoint = HARNESS_STATE_PATH,
                fields = setOf(
                    "productCount", "orderCount", "paymentCount", "completedPaymentCount",
                    "failedPaymentCount", "activeFaultCount",
                ),
                authProfile = "harness",
            ),
        ),
        supportedFaults = setOf("PAYMENT_FAILURE"),
        infrastructureTargets = emptySet(),
        maxConcurrency = STANDARD_MAX_CONCURRENCY,
        maxRequestCount = STANDARD_MAX_REQUEST_COUNT,
        maxTrials = STANDARD_MAX_TRIALS,
        stateChangingAllowed = true,
        reset = ProfileResetDefinition(
            method = CleanupMethod.ENVIRONMENT_RESET,
            hook = ProfileHttpCallDefinition("POST", HARNESS_RESET_PATH, "harness"),
            expectedDuration = Duration.ofSeconds(STANDARD_RESET_DURATION_SECONDS),
            verifications = listOf(
                ProfileResetVerificationDefinition(
                    id = "runDataCleared",
                    call = ProfileHttpCallDefinition("GET", HARNESS_STATE_PATH, "harness"),
                    expression = "response.body.state.orderCount",
                    condition = "runDataCleared == 0",
                    readTiming = ProfileReadTimingDefinition(StabilityRule.IMMEDIATE, Duration.ZERO, Duration.ZERO),
                ),
            ),
        ),
        faultInjection = ProfileFaultInjectionDefinition(
            injectEndpoint = ProfileHttpCallDefinition("POST", HARNESS_FAULT_PATH, "harness"),
            releaseEndpoint = ProfileHttpCallDefinition("POST", HARNESS_FAULT_RELEASE_PATH, "harness"),
            maxTtl = Duration.ofSeconds(STANDARD_FAULT_TTL_SECONDS),
        ),
    )

    private fun String.toHttpOrigin(): URI {
        val uri = try {
            URI(trim())
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException("Target URL is not a valid HTTP(S) origin", exception)
        }
        require(
            uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"),
        ) { "Target URL must contain only an HTTP(S) origin" }
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
    }

    private fun generatedTargetId(name: String, origin: URI): String {
        require(name.isNotBlank()) { "Target name is required" }
        val slug = name.lowercase()
            .replace(NON_IDENTIFIER, "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .ifBlank { "target" }
        val suffix = MessageDigest.getInstance("SHA-256").digest(origin.toString().toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(HASH_LENGTH)
        return "quick-$slug-$suffix"
    }

    private companion object {
        const val HTTP_TARGET = "HTTP_TARGET"
        const val HEALTH_PATH = "/actuator/health"
        const val PRODUCT_PATH = "/api/products"
        const val PAYMENT_PATH = "/api/payments/webhook"
        const val HARNESS_STATE_PATH = "/api/harness/state"
        const val HARNESS_RESET_PATH = "/api/harness/reset"
        const val HARNESS_FAULT_PATH = "/api/harness/fault"
        const val HARNESS_FAULT_RELEASE_PATH = "/api/harness/fault/release"
        const val MAX_SLUG_LENGTH = 72
        const val HASH_LENGTH = 12
        const val HTTP_OK = 200
        const val STANDARD_REQUEST_TIMEOUT_SECONDS = 5L
        const val STANDARD_MAX_CONCURRENCY = 20
        const val STANDARD_MAX_REQUEST_COUNT = 100
        const val STANDARD_MAX_TRIALS = 20
        const val STANDARD_RESET_DURATION_SECONDS = 30L
        const val STANDARD_FAULT_TTL_SECONDS = 120L
        val EXECUTABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
        val NON_IDENTIFIER = Regex("[^a-z0-9]+")
    }
}

/** Probes a fixed relative Swagger/OpenAPI allowlist; it never follows links or crawls a Target. */
@Component
class QuickOpenApiDiscovery(
    private val fetcher: TargetOpenApiDocumentFetcher,
    private val parser: BoundedOpenApiDocumentParser,
) {
    fun findAllowedDocuments(definition: TargetProfileDefinition): List<String> {
        val preview = TargetProfileVersion(
            id = UUID(0, 0),
            targetSystemId = definition.target.id,
            source = TargetProfileSource.USER_IMPORT,
            status = TargetProfileStatus.DRAFT,
            checksum = "quick-registration-preview",
            definition = definition.copy(target = definition.target.copy(openApiPaths = CANDIDATE_PATHS)),
            createdBy = "quick-registration-preview",
            createdAt = Instant.EPOCH,
        )
        val documents = CANDIDATE_PATHS.filter { path ->
            try {
                parser.parse(fetcher.fetch(preview, path))
                true
            } catch (_: ClientRequestException) {
                false
            } catch (_: TargetReadTransportException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        if (documents.isEmpty()) {
            throw ClientRequestException(
                code = "QUICK_OPENAPI_NOT_FOUND",
                message = "등록한 URL에서 지원되는 Swagger/OpenAPI 문서를 찾지 못했습니다. " +
                    "허용 경로: ${CANDIDATE_PATHS.joinToString()}",
            )
        }
        return documents
    }

    private companion object {
        val CANDIDATE_PATHS = listOf(
            "/v3/api-docs",
            "/swagger.json",
            "/openapi.json",
            "/api-docs",
            "/api-docs/product",
            "/api-docs/order",
            "/api-docs/payment",
        )
    }
}
