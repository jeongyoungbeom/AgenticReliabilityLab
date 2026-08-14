package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.experiment.profile.TargetExperimentProfileProperties
import com.project.agenticreliabilitylab.experiment.profile.TargetExperimentRegistration
import com.project.agenticreliabilitylab.target.infrastructure.TargetRegistration
import com.project.agenticreliabilitylab.target.infrastructure.TargetRegistrationProperties
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.ExperimentProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.profile.TargetSpecProperties
import com.project.agenticreliabilitylab.targetspec.profile.TargetSpecRegistration
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** Seeds immutable Profile Versions from the legacy static YAML only when a Target has no active DB Profile. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TargetProfileBootstrapInitializer(
    private val targetProperties: TargetRegistrationProperties,
    private val targetSpecProperties: TargetSpecProperties,
    private val experimentProfileProperties: TargetExperimentProfileProperties,
    private val profileService: TargetProfileService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val specsByTarget = targetSpecProperties.registrations
            .associateNoDuplicates("Target Spec") { it.targetSystemId }
        val experimentsByTarget = experimentProfileProperties.registrations
            .associateNoDuplicates("Target experiment profile") { it.targetSystemId }
        val targetsById = targetProperties.registrations.associateNoDuplicates("Target registration") { it.id }

        targetsById.values.forEach { target ->
            profileService.seedBootstrap(
                TargetProfileDefinition(
                    target = target.toDefinition(),
                    genericHttp = specsByTarget[target.id]?.toDefinition(target.id),
                    experiment = experimentsByTarget[target.id]?.toDefinition(),
                ),
            )
        }
        val unregisteredSpecs = (specsByTarget.keys + experimentsByTarget.keys) - targetsById.keys
        require(unregisteredSpecs.isEmpty()) {
            "Target Profile bootstrap has specifications without a target registration: " +
                unregisteredSpecs.sorted().joinToString()
        }
    }

    private fun <T> List<T>.associateNoDuplicates(label: String, id: (T) -> String): Map<String, T> {
        val duplicates = groupingBy(id).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate $label ids: ${duplicates.sorted().joinToString()}" }
        return associateBy(id)
    }

    private fun TargetRegistration.toDefinition(): TargetRegistrationDefinition = TargetRegistrationDefinition(
        id = id,
        name = name,
        adapterType = adapterType,
        environment = environment,
        baseUrl = baseUrl,
        allowedOrigin = allowedOrigin,
        allowedCidrs = allowedCidrs,
        healthPath = healthPath,
        sourceRepository = sourceRepository,
        identityVerification = identityVerification,
        capabilities = capabilities,
        enabled = enabled,
    )

    private fun TargetSpecRegistration.toDefinition(targetSystemId: String): GenericHttpProfileDefinition =
        GenericHttpProfileDefinition(
            executionEnabled = executionEnabled,
            hostResourceGroup = hostResourceGroup,
            maxBatchSize = maxBatchSize,
            requestTimeout = requestTimeout,
            readOnlyOperations = readOnlyOperations.map {
                ReadOnlyOperationDefinition(it.id, it.title, it.description, it.path, it.expectedStatusCodes)
            },
            failureInjectionPlanningEnabled = failureInjectionPlanningEnabled,
            failureInjectionCandidates = failureInjectionCandidates.map {
                FailureInjectionCandidate(
                    id = it.id,
                    targetSystemId = targetSystemId,
                    type = it.type,
                    risk = it.risk,
                    title = it.title,
                    description = it.description,
                    recoveryExpectation = it.recoveryExpectation,
                )
            },
        )

    private fun TargetExperimentRegistration.toDefinition(): ExperimentProfileDefinition = ExperimentProfileDefinition(
        adapterId = adapterId,
        executionEnabled = executionEnabled,
        hostResourceGroup = hostResourceGroup,
        stockConcurrency = requireNotNull(stockConcurrency) {
            "Target experiment profile '$targetSystemId' must declare STOCK_CONCURRENCY"
        },
    )
}
