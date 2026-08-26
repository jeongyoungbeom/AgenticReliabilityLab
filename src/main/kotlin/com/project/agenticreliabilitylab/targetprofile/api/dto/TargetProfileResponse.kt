package com.project.agenticreliabilitylab.targetprofile.api.dto

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import java.time.Instant

data class TargetProfileResponse(
    val id: String,
    val targetSystemId: String,
    val source: String,
    val status: String,
    val checksum: String,
    val openApiPath: String?,
    val openApiPaths: List<String>,
    val genericHttpEnabled: Boolean,
    val readOnlyOperationCount: Int,
    val experimentProfilePresent: Boolean,
    val createdAt: Instant,
    val activatedAt: Instant?,
) {
    companion object {
        fun from(version: TargetProfileVersion): TargetProfileResponse = TargetProfileResponse(
            id = version.id.toString(),
            targetSystemId = version.targetSystemId,
            source = version.source.name,
            status = version.status.name,
            checksum = version.checksum,
            openApiPath = version.definition.target.openApiPath,
            openApiPaths = version.definition.target.declaredOpenApiPaths(),
            genericHttpEnabled = version.definition.genericHttp?.executionEnabled ?: false,
            readOnlyOperationCount = version.definition.genericHttp?.readOnlyOperations?.size ?: 0,
            experimentProfilePresent = version.definition.experiment != null,
            createdAt = version.createdAt,
            activatedAt = version.activatedAt,
        )
    }
}
