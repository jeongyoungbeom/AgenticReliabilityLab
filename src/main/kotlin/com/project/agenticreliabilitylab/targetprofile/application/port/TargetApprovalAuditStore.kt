package com.project.agenticreliabilitylab.targetprofile.application.port

import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAuditEvent

/** Append-only approval record used to reconstruct who approved which immutable Target Profile Version. */
interface TargetApprovalAuditStore {
    fun append(event: TargetApprovalAuditEvent)
}
