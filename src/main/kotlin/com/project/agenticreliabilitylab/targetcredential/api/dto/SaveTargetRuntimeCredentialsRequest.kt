package com.project.agenticreliabilitylab.targetcredential.api.dto

import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialRole

data class SaveTargetRuntimeCredentialsRequest(
    val seller: String? = null,
    val buyer: String? = null,
    val harness: String? = null,
) {
    fun values(): Map<TargetCredentialRole, String> = buildMap {
        seller?.takeIf(String::isNotBlank)?.let { value -> put(TargetCredentialRole.SELLER, value) }
        buyer?.takeIf(String::isNotBlank)?.let { value -> put(TargetCredentialRole.BUYER, value) }
        harness?.takeIf(String::isNotBlank)?.let { value -> put(TargetCredentialRole.HARNESS, value) }
    }
}
