package com.project.agenticreliabilitylab.analysis.application.model

import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails

/** A user-selected architecture and registered model for one comparison run. */
data class RequestedComparisonConfiguration(
    val architecture: AnalysisArchitecture,
    val modelKey: String,
)

data class AnalysisComparisonConfiguration(
    val selectionKey: String,
    val architecture: AnalysisArchitecture,
    val modelKey: String,
)

data class AnalysisComparisonDetails(
    val comparison: AnalysisComparisonRecord,
    val dataset: AnalysisDatasetRecord,
    val configurations: List<AnalysisComparisonConfiguration>,
    val runs: List<ComparisonAnalysisRun>,
)

data class ComparisonAnalysisRun(
    val mapping: AnalysisComparisonRunRecord,
    val configuration: AnalysisComparisonConfiguration,
    val details: AnalysisRunDetails,
)
