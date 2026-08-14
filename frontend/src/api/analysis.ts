export type AnalysisArchitecture = 'SINGLE' | 'MULTI'

export interface AnalysisRunDetails {
  id: string
  targetTestBatchId: string | null
  agentType: string
  modelKey: string
  modelId: string
  status: string
  verdict: string | null
  summary: string | null
  failureCode: string | null
  failureMessage: string | null
  promptTokenCount: number | null
  completionTokenCount: number | null
  durationMillis: number | null
  findings: AnalysisFinding[]
  recommendations: AnalysisRecommendation[]
}

export interface AnalysisFinding {
  severity: string
  category: string
  title: string
  detail: string
  evidenceIds: string[]
}

export interface AnalysisRecommendation {
  priority: string
  title: string
  recommendation: string
  rationale: string
  evidenceIds: string[]
}

export interface AnalysisComparison {
  id: string
  targetTestBatchId: string | null
  evidenceCount: number
  selectedConfigurations: AnalysisConfiguration[]
  runs: AnalysisComparisonRun[]
}

export interface AnalysisConfiguration {
  selectionKey: string
  architecture: AnalysisArchitecture
  modelKey: string
}

export interface AnalysisComparisonRun extends AnalysisConfiguration {
  analysisRunId: string
  status: string
  modelId: string
  verdict: string | null
  summary: string | null
  promptTokenCount: number | null
  completionTokenCount: number | null
  durationMillis: number | null
}

export interface RootCauseReport {
  id: string
  analysisRunId: string
  modelKey: string
  status: string
  failureCode: string | null
  failureMessage: string | null
  hypotheses: RootCauseHypothesis[]
  improvementProposals: ImprovementProposal[]
}

export interface RootCauseHypothesis {
  ordinal: number
  title: string
  confidence: string
  rationale: string
  falsifiability: string
  evidenceIds: string[]
}

export interface ImprovementProposal {
  ordinal: number
  hypothesisOrdinal: number
  title: string
  proposedChange: string
  expectedEffect: string
  risk: string
  evidenceIds: string[]
}
