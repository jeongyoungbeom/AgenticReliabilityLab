package com.project.agenticreliabilitylab.targetprofiledraft.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetprofiledraft.api.dto.CreateReadmeTargetProfileDraftRequest
import com.project.agenticreliabilitylab.targetprofiledraft.api.dto.CreateTargetProfileDraftRequest
import com.project.agenticreliabilitylab.targetprofiledraft.api.dto.TargetProfileDraftResponse
import com.project.agenticreliabilitylab.targetprofiledraft.application.TargetProfileDraftService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Produces a disabled Profile YAML proposal only; it cannot import, activate, or execute a Target operation. */
@RestController
@RequestMapping("/api/target-profile-drafts")
class TargetProfileDraftController(
    private val service: TargetProfileDraftService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping("/openapi")
    fun fromOpenApi(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: CreateTargetProfileDraftRequest,
    ): TargetProfileDraftResponse {
        operatorAccessService.requireProfileEditor(authorization)
        return TargetProfileDraftResponse.from(service.fromOpenApi(request.document))
    }

    @PostMapping("/readme")
    fun fromReadme(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: CreateReadmeTargetProfileDraftRequest,
    ): TargetProfileDraftResponse {
        operatorAccessService.requireProfileEditor(authorization)
        return TargetProfileDraftResponse.from(service.fromReadme(request.document))
    }
}
