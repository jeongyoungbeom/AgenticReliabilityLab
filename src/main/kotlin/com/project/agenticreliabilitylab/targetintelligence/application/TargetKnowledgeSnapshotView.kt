package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot

/**
 * A stored Snapshot plus the freshness that is derived at read time.
 *
 * [profileVersionActive] is never persisted: the Snapshot is bound to one Profile Version, and whether that binding is
 * still current has to be recomputed whenever the answer is used, exactly like Phase 12 candidate readiness.
 */
data class TargetKnowledgeSnapshotView(
    val snapshot: TargetKnowledgeSnapshot,
    val profileVersionActive: Boolean,
)
