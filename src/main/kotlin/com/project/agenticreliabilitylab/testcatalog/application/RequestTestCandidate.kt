package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import java.util.UUID

/** A test the user asked for in their own words, narrowed to a registered category. */
data class RequestTestCandidate(
    val knowledgeSnapshotId: UUID,
    val category: TestCandidateCategory,
    val title: String,
    val description: String,
    val targetOperationPath: String?,
    val invariantStatement: String?,
)
