package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem

data class PilotTestSessionView(
    val session: PilotTestSession,
    val items: List<PilotTestSessionItem>,
)
