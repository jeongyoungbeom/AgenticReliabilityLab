package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.targetintelligence.application.CreateTargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetintelligence.application.TargetKnowledgeSnapshotService
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.application.GenericProfileContractMapper
import com.project.agenticreliabilitylab.targetprofile.application.HarnessManifestFetcher
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.JacksonException

/** Activates one Profile and immediately creates its automatic OpenAPI Snapshot when configured. */
@Service
class TargetProfileActivationWorkflow(
    private val profiles: TargetProfileService,
    private val fetcher: TargetOpenApiDocumentFetcher,
    private val parser: BoundedOpenApiDocumentParser,
    private val snapshots: TargetKnowledgeSnapshotService,
    private val manifestFetcher: HarnessManifestFetcher,
    private val contractMapper: GenericProfileContractMapper,
    private val credentials: RuntimeTargetCredentialStore,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun activate(
        versionId: UUID, actor: String, correlationId: String, credentialSessionId: String? = null,
    ): TargetProfileVersion {
        val version = profiles.findVersion(versionId)
        val documents = version.definition.target.declaredOpenApiPaths()
            .map { path -> fetcher.fetch(version, path).also(parser::parse) }
        if (version.definition.target.harnessManifestPath != null) {
            val key = credentials.headersFor(version.targetSystemId, credentialSessionId, "harness")
                ?.get("X-ARL-Harness-Key") ?: throw IllegalArgumentException("Harness credential is required")
            val manifest = manifestFetcher.fetch(version.definition.target, key)
            val hasWrites = try {
                objectMapper.readTree(manifest).path("operations").size() > 0
            } catch (exception: JacksonException) {
                throw IllegalArgumentException("Harness manifest must be valid JSON", exception)
            }
            val state = if (hasWrites) {
                manifestFetcher.state(version.definition.target, key)
            } else null
            val current = contractMapper.map(version.definition.target, documents, manifest, state)
            require(current == version.definition) { "OpenAPI or Harness manifest changed since Profile proposal" }
        }
        val activated = profiles.activate(versionId, actor, correlationId)
        documents.forEach { openApi ->
            snapshots.create(
                command = CreateTargetKnowledgeSnapshot(
                    targetSystemId = activated.targetSystemId,
                    openApiDocument = openApi,
                ),
                actor = actor,
                correlationId = correlationId,
            )
        }
        return activated
    }
}
