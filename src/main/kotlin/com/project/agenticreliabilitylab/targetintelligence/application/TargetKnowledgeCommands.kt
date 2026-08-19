package com.project.agenticreliabilitylab.targetintelligence.application

/** One workflow the user declared explicitly, so it is recorded as STATED rather than inferred. */
data class TargetBriefWorkflow(
    val title: String,
    val steps: List<String>,
)

/** Structured facts the user typed in directly. Nothing here is parsed out of prose. */
data class TargetBriefInput(
    val domainTerms: List<String> = emptyList(),
    val workflows: List<TargetBriefWorkflow> = emptyList(),
    val invariants: List<String> = emptyList(),
    val components: List<String> = emptyList(),
) {
    val empty: Boolean =
        domainTerms.isEmpty() && workflows.isEmpty() && invariants.isEmpty() && components.isEmpty()
}

data class CreateTargetKnowledgeSnapshot(
    val targetSystemId: String,
    val openApiDocument: String? = null,
    val readmeDocument: String? = null,
    val brief: TargetBriefInput? = null,
) {
    /** Checked before any document is parsed, so an empty request never pays for extraction. */
    val hasAnyInput: Boolean = !openApiDocument.isNullOrBlank() ||
        !readmeDocument.isNullOrBlank() ||
        brief?.empty == false
}
