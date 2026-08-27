package com.project.agenticreliabilitylab.targetprofile.application

/** The generated defaults a user must be able to read back for their Target. */
data class EffectiveTargetProfile(
    val targetSystemId: String,
    val targetName: String,
    val environment: String,
    val baseUrl: String,
    val allowedOrigin: String?,
    val allowedCidrs: List<String>,
    val healthPath: String?,
    val openApiPaths: List<String>,
    val harnessStatePath: String?,
    val harnessStateFields: List<String>,
    val harnessResetPath: String?,
    val harnessFaultPath: String?,
    val harnessFaultReleasePath: String?,
    val authProfiles: List<String>,
    val supportedFaults: List<String>,
    val allowedCalls: List<String>,
    val requestTimeout: String?,
    val maxConcurrency: Int?,
    val maxRequestCount: Int?,
    val maxTrials: Int?,
    val generatedYaml: String,
)
