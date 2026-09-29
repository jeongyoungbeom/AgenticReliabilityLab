package com.project.agenticreliabilitylab.testspec.application

import java.util.UUID

/** H5 accepts confirmed Snapshots together; the optional raw OpenAPI field remains for existing API clients. */
data class StartTestSpecGeneration(
    val targetSystemId: String,
    val knowledgeSnapshotIds: List<UUID>,
    val openApiDocument: String?,
    val requestedModelKey: String?,
    val credentialSessionId: String? = null,
)
