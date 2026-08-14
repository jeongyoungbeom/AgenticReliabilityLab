package com.project.agenticreliabilitylab.target.api

import com.project.agenticreliabilitylab.target.api.dto.TargetHealthResponse
import com.project.agenticreliabilitylab.target.api.dto.TargetIdentityResponse
import com.project.agenticreliabilitylab.target.api.dto.TargetSummaryResponse
import com.project.agenticreliabilitylab.target.application.TargetSystemService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/targets")
class TargetSystemController(
    private val service: TargetSystemService,
) {
    @GetMapping
    fun findAll(): List<TargetSummaryResponse> =
        service.findAll().map(TargetSummaryResponse::from)

    @GetMapping("/{id}")
    fun findById(@PathVariable id: String): TargetSummaryResponse =
        TargetSummaryResponse.from(service.findById(id))

    @GetMapping("/{id}/health")
    fun health(@PathVariable id: String): TargetHealthResponse =
        TargetHealthResponse.from(service.health(id))

    @GetMapping("/{id}/identity")
    fun identity(@PathVariable id: String): TargetIdentityResponse =
        TargetIdentityResponse.from(service.identity(id))
}
