import type { ApiClient } from './ApiClient'
import type { TestSpecificationDocument } from './testSpecifications'

export type TestSpecGenerationRunStatus = 'REQUESTED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type TestSpecGenerationCandidateOutcome = 'ACCEPTED' | 'REJECTED'
export type TestSpecMisjudgmentReportStatus = 'REQUESTED' | 'RUNNING' | 'DRAFTED' | 'REJECTED' | 'FAILED'

export interface TestSpecGenerationCandidateResponse {
  ordinal: number
  outcome: TestSpecGenerationCandidateOutcome
  specKey: string
  title: string
  document: TestSpecificationDocument
  rejectionReason: string | null
  specificationId: string | null
}

export interface TestSpecGenerationRunResponse {
  id: string
  targetSystemId: string
  knowledgeSnapshotId: string
  modelKey: string
  modelId: string
  promptVersion: string
  inputChecksum: string
  status: TestSpecGenerationRunStatus
  promptTokenCount: number | null
  completionTokenCount: number | null
  durationMillis: number | null
  failureCode: string | null
  failureMessage: string | null
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
  candidates: TestSpecGenerationCandidateResponse[]
}

export interface TestSpecMisjudgmentReportResponse {
  id: string
  targetSystemId: string
  specificationId: string
  runId: string
  trialNumber: number
  invariantId: string
  reason: string
  modelKey: string
  modelId: string
  promptVersion: string
  status: TestSpecMisjudgmentReportStatus
  draftedCondition: string | null
  draftedDescription: string | null
  resultingSpecificationId: string | null
  rejectionReason: string | null
  promptTokenCount: number | null
  completionTokenCount: number | null
  durationMillis: number | null
  failureCode: string | null
  failureMessage: string | null
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
}

export function startTestSpecGeneration(
  api: ApiClient,
  targetSystemId: string,
  request: { knowledgeSnapshotId: string; openApiDocument?: string; modelKey?: string },
  idempotencyKey: string,
) {
  return api.post<TestSpecGenerationRunResponse>(
    `/api/targets/${targetSystemId}/test-specification-generations`,
    request,
    'profileEditor',
    idempotencyKey,
  )
}

export function findTestSpecGeneration(api: ApiClient, runId: string) {
  return api.get<TestSpecGenerationRunResponse>(`/api/test-specification-generations/${runId}`)
}

export function reportTestSpecMisjudgment(
  api: ApiClient,
  targetSystemId: string,
  request: {
    specificationId: string
    runId: string
    trialNumber: number
    invariantId: string
    reason: string
    modelKey?: string
  },
  idempotencyKey: string,
) {
  return api.post<TestSpecMisjudgmentReportResponse>(
    `/api/targets/${targetSystemId}/test-spec-misjudgment-reports`,
    request,
    'profileEditor',
    idempotencyKey,
  )
}

export function findTestSpecMisjudgmentReport(api: ApiClient, reportId: string) {
  return api.get<TestSpecMisjudgmentReportResponse>(`/api/test-spec-misjudgment-reports/${reportId}`)
}

export function isGenerationPolling(status: TestSpecGenerationRunStatus): boolean {
  return status === 'REQUESTED' || status === 'RUNNING'
}

export function isMisjudgmentPolling(status: TestSpecMisjudgmentReportStatus): boolean {
  return status === 'REQUESTED' || status === 'RUNNING'
}
