package com.project.agenticreliabilitylab.targetprofile.domain

import com.project.agenticreliabilitylab.common.ClientRequestException

/** The uploaded Profile text is syntactically unsafe or cannot be mapped to the Profile contract. */
class TargetProfileDocumentException(message: String, cause: Throwable? = null) :
    ClientRequestException("TARGET_PROFILE_INVALID", message, cause)
