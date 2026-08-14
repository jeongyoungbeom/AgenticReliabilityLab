package com.project.agenticreliabilitylab.targetprofile.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetprofile.api.dto.ActivateTargetProfileRequest
import com.project.agenticreliabilitylab.targetprofile.api.dto.ImportTargetProfileRequest
import com.project.agenticreliabilitylab.targetprofile.api.dto.TargetProfileResponse
import com.project.agenticreliabilitylab.targetprofile.api.dto.TargetProfileValidationResponse
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileDocumentParser
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import jakarta.validation.Valid
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/target-profiles")
class TargetProfileController(
    private val parser: TargetProfileDocumentParser,
    private val profileService: TargetProfileService,
    private val operatorAccessService: OperatorAccessService,
) {
    @PostMapping("/validate")
    fun validate(@Valid @RequestBody request: ImportTargetProfileRequest): TargetProfileValidationResponse {
        val definition = parser.parse(request.yaml)
        profileService.validate(definition)
        return TargetProfileValidationResponse.from(definition)
    }

    @PostMapping
    fun import(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ImportTargetProfileRequest,
    ): ResponseEntity<TargetProfileResponse> {
        val actor = operatorAccessService.requireProfileEditor(authorization)
        val version = profileService.import(
            definition = parser.parse(request.yaml),
            source = TargetProfileSource.USER_IMPORT,
            actor = actor,
            correlationId = correlationId(),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TargetProfileResponse.from(version))
    }

    @PostMapping("/{versionId}/activate")
    fun activate(
        @PathVariable versionId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: ActivateTargetProfileRequest,
    ): ResponseEntity<TargetProfileResponse> {
        require(request.confirmation == ACTIVATION_CONFIRMATION) {
            "confirmation must equal $ACTIVATION_CONFIRMATION"
        }
        val actor = operatorAccessService.requireProfileEditor(authorization)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            TargetProfileResponse.from(profileService.activate(versionId, actor, correlationId())),
        )
    }

    @GetMapping
    fun findAllActive(
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): List<TargetProfileResponse> {
        operatorAccessService.requireViewer(authorization)
        return profileService.findAllActive().map(TargetProfileResponse::from)
    }

    @GetMapping("/{versionId}")
    fun find(
        @PathVariable versionId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): TargetProfileResponse {
        operatorAccessService.requireViewer(authorization)
        return TargetProfileResponse.from(profileService.findVersion(versionId))
    }

    private fun correlationId(): String = MDC.get(CORRELATION_ID_KEY) ?: "missing-correlation-id"

    private companion object {
        const val ACTIVATION_CONFIRMATION = "ACTIVATE_TARGET_PROFILE_VERSION"
        const val CORRELATION_ID_KEY = "correlationId"
    }
}
