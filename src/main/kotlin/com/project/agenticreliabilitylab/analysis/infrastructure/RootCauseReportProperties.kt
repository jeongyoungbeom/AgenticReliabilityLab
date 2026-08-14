package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.RootCauseReportSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.root-cause-reports")
class RootCauseReportProperties : RootCauseReportSettings {
    override var enabled: Boolean = true
    override var promptVersion: String = "root-cause-report-v1"
    override var maxOutputBytes: Int = 65_536
    override var maxHypotheses: Int = 5
    override var maxImprovementProposals: Int = 10
    override var maxInputBytes: Int = 262_144
}
