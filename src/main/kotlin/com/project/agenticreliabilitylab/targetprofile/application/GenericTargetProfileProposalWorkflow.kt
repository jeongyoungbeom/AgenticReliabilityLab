package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialRole
import com.project.agenticreliabilitylab.targetdiscovery.application.TargetOpenApiDocumentFetcher
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.JacksonException
import java.time.Instant
import java.util.UUID

data class GenericTargetProfileProposal(
    val name: String,
    val baseUrl: String,
    val environment: TargetEnvironment,
    val openApiPaths: List<String>,
    val manifestPath: String,
    val harnessKey: String,
)

data class ProposedTargetProfile(val version: TargetProfileVersion, val credentialSessionId: String)

@Service
class GenericTargetProfileProposalWorkflow(
    private val quickFactory: QuickTargetProfileFactory,
    private val profiles: TargetProfileService,
    private val openApiFetcher: TargetOpenApiDocumentFetcher,
    private val manifestFetcher: HarnessManifestFetcher,
    private val mapper: GenericProfileContractMapper,
    private val credentials: RuntimeTargetCredentialStore,
    private val objectMapper: ObjectMapper,
) {
    fun propose(
        request: GenericTargetProfileProposal,
        actor: String,
        correlationId: String,
        credentialSessionId: String?,
    ): ProposedTargetProfile {
        val target = quickFactory.createTarget(
            QuickTargetProfileRegistration(request.name, request.baseUrl, request.environment),
        ).copy(
            openApiPaths = request.openApiPaths,
            harnessManifestPath = request.manifestPath,
            sourceRepository = "generic-registration",
        )
        profiles.validate(TargetProfileDefinition(target))
        val preview = TargetProfileVersion(
            UUID(0, 0), target.id, TargetProfileSource.USER_IMPORT, TargetProfileStatus.DRAFT,
            "generic-registration-preview", TargetProfileDefinition(target), actor, Instant.EPOCH,
        )
        val documents = request.openApiPaths.map { path -> openApiFetcher.fetch(preview, path) }
        val manifest = manifestFetcher.fetch(target, request.harnessKey)
        val hasWrites = try {
            objectMapper.readTree(manifest).path("operations").size() > 0
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("Harness manifest must be valid JSON", exception)
        }
        val state = if (hasWrites) {
            manifestFetcher.state(target, request.harnessKey)
        } else null
        val definition = mapper.map(target, documents, manifest, state)
        val version = profiles.import(definition, TargetProfileSource.USER_IMPORT, actor, correlationId)
        val session = credentials.save(
            target.id, credentialSessionId, mapOf(TargetCredentialRole.HARNESS to request.harnessKey),
        )
        return ProposedTargetProfile(version, session.credentialSessionId)
    }
}
