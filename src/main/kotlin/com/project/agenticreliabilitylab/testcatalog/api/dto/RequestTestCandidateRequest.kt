package com.project.agenticreliabilitylab.testcatalog.api.dto

import com.project.agenticreliabilitylab.testcatalog.application.RequestTestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/** A test the user asks for directly. Only a registered category is accepted; free-form execution is never taken. */
data class RequestTestCandidateRequest(
    @field:NotNull val knowledgeSnapshotId: UUID?,
    @field:NotNull val category: TestCandidateCategory?,
    @field:NotBlank @field:Size(max = MAX_TITLE_CHARACTERS) val title: String,
    @field:Size(max = MAX_DESCRIPTION_CHARACTERS) val description: String? = null,
    @field:Size(max = MAX_PATH_CHARACTERS) val targetOperationPath: String? = null,
    @field:Size(max = MAX_STATEMENT_CHARACTERS) val invariantStatement: String? = null,
) {
    fun toCommand(): RequestTestCandidate = RequestTestCandidate(
        knowledgeSnapshotId = requireNotNull(knowledgeSnapshotId) { "knowledgeSnapshotId is required" },
        category = requireNotNull(category) { "category is required" },
        title = title,
        description = description.orEmpty(),
        targetOperationPath = targetOperationPath,
        invariantStatement = invariantStatement,
    )

    private companion object {
        const val MAX_TITLE_CHARACTERS = 200
        const val MAX_DESCRIPTION_CHARACTERS = 1_000
        const val MAX_PATH_CHARACTERS = 500
        const val MAX_STATEMENT_CHARACTERS = 500
    }
}
