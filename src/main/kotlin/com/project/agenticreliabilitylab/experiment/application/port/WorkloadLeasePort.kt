package com.project.agenticreliabilitylab.experiment.application.port

import com.project.agenticreliabilitylab.experiment.domain.WorkloadLease
import java.time.Instant

/** Shared resource exclusion contract used by experiments and safe HTTP batches. */
interface WorkloadLeasePort {
    fun tryAcquire(
        hostResourceGroup: String,
        ownerId: String,
        leaseOwner: String,
        now: Instant,
        expiresAt: Instant,
        mode: String = "EXPERIMENT_WINDOW",
        ownerType: String = "EXPERIMENT_RUN",
    ): WorkloadLease?

    fun release(lease: WorkloadLease)
}
