package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.targetintelligence.application.CreateTargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetintelligence.application.TargetKnowledgeSnapshotService
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Activates one Profile and immediately creates its automatic OpenAPI Snapshot when configured. */
@Service
class TargetProfileActivationWorkflow(
    private val profiles: TargetProfileService,
    private val fetcher: TargetOpenApiDocumentFetcher,
    private val parser: BoundedOpenApiDocumentParser,
    private val snapshots: TargetKnowledgeSnapshotService,
) {
    @Transactional
    fun activate(versionId: UUID, actor: String, correlationId: String): TargetProfileVersion {
        val version = profiles.findVersion(versionId)
        val documents = version.definition.target.declaredOpenApiPaths()
            .map { path -> fetcher.fetch(version, path).also(parser::parse) }
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
