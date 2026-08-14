package com.project.agenticreliabilitylab.targetprofile.application.port

import java.util.UUID

/** Application-facing check that binds an executable request to the currently active Profile Version. */
interface ActiveTargetProfileVersionCatalog {
    fun requireActiveVersionId(targetSystemId: String): UUID
}
