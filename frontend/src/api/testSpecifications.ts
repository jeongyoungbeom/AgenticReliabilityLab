import type { ApiClient } from './ApiClient'

export type SpecSource = 'RULE_GENERATED' | 'MODEL_PROPOSED' | 'USER_REQUESTED'
export type SpecCategory = 'AVAILABILITY' | 'CONTRACT_INPUT' | 'WORKFLOW' | 'RETRY_RECOVERY' | 'IDEMPOTENCY' | 'CONCURRENCY' | 'CONSISTENCY'
export type SpecRisk = 'SAFE' | 'MODERATE' | 'DESTRUCTIVE'
export type TestSpecificationStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'SUPERSEDED'
export type TestSpecRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'RECOVERY_REQUIRED'
export type TrialOutcome = 'PASSED' | 'VIOLATED' | 'INCONCLUSIVE'
export type InvariantOutcome = 'PASSED' | 'VIOLATED' | 'NOT_EVALUATED'
export type NotEvaluatedReason =
  | 'OBSERVATION_MISSING'
  | 'REQUIREMENT_UNMET'
  | 'OBSERVATION_INSUFFICIENT'
  | 'EXPRESSION_FAILED'
  | 'TRIAL_NOT_RUN'

export type TestSpecificationDocument = Record<string, unknown>

export interface TestSpecificationResponse {
  id: string
  targetSystemId: string
  specKey: string
  version: number
  title: string
  profileVersionId: string
  profileVersionActive: boolean
  source: SpecSource
  category: SpecCategory
  risk: SpecRisk
  status: TestSpecificationStatus
  document: TestSpecificationDocument
  checksum: string
  requiredConfirmation: string
  unfoundedThresholds: string[]
  createdBy: string
  createdAt: string
  approvedBy: string | null
  approvedAt: string | null
  terminalReason: string | null
}

export interface InvariantVerdict {
  invariantId: string
  description: string
  outcome: InvariantOutcome
  condition: string
  observedValues: Record<string, string>
  notEvaluatedReason: NotEvaluatedReason | null
  detail: string | null
  appliedException: string | null
}

export interface StepTiming {
  name: string
  startedAt: string
  endedAt: string
  role: 'SETUP' | 'WORKLOAD'
}

export type FaultAuditAction = 'INJECTED' | 'RELEASED' | 'RELEASE_FAILED'

/** Target fault lifecycle facts only; credentials and request bodies are never returned here. */
export interface FaultAuditEvent {
  action: FaultAuditAction
  faultId: string | null
  faultType: string | null
  scope: string | null
  ttlMs: number | null
  injectionPoint: string | null
  description: string
  succeeded: boolean
  failure: string | null
}

export interface TestSpecTrialResponse {
  trialNumber: number
  outcome: TrialOutcome
  stateChanged: boolean
  completed: boolean
  failure: string | null
  verdicts: InvariantVerdict[]
  timings: StepTiming[]
  /** Optional while a browser is connected to an ARL server deployed before the audit-field migration. */
  faultEvents?: FaultAuditEvent[]
}

export interface TestSpecResetResponse {
  sequenceNumber: number
  performed: boolean
  verified: boolean
  checks: Array<{ id: string; condition: string; observed: string; satisfied: boolean }>
  failure: string | null
}

export interface TestSpecRunResponse {
  id: string
  specificationId: string
  targetSystemId: string
  profileVersionId: string
  status: TestSpecRunStatus
  requestedTrials: number
  resultOutcome: TrialOutcome | null
  trialsRun: number | null
  trialsViolated: number | null
  trialsInconclusive: number | null
  cleanupVerified: boolean | null
  createdBy: string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  failure: string | null
  trials: TestSpecTrialResponse[]
  resets: TestSpecResetResponse[]
}

export interface TestSpecRegressionRunsResponse {
  targetSystemId: string
  runs: Array<{
    specificationId: string
    specKey: string
    version: number
    run: TestSpecRunResponse | null
    failureCode: string | null
    failureMessage: string | null
  }>
}

export interface CreateTestSpecificationRequest {
  targetSystemId: string
  source: SpecSource
  document: TestSpecificationDocument
}

export function createTestSpecification(api: ApiClient, request: CreateTestSpecificationRequest) {
  return api.post<TestSpecificationResponse>('/api/test-specifications', request, 'profileEditor')
}

export function approveTestSpecification(api: ApiClient, specificationId: string, confirmation: string) {
  return api.post<TestSpecificationResponse>(`/api/test-specifications/${specificationId}/approve`, { confirmation }, 'executor')
}

export function executeTestSpecification(api: ApiClient, specificationId: string, idempotencyKey: string) {
  return api.post<TestSpecRunResponse>(`/api/test-specifications/${specificationId}/runs`, {}, 'executor', idempotencyKey)
}

export function triggerRegressionRuns(api: ApiClient, targetSystemId: string, idempotencyKey: string) {
  return api.post<TestSpecRegressionRunsResponse>(
    `/api/targets/${targetSystemId}/test-specifications/regression-runs`,
    {},
    'executor',
    idempotencyKey,
  )
}

export function findSpecification(api: ApiClient, specificationId: string) {
  return api.get<TestSpecificationResponse>(`/api/test-specifications/${specificationId}`)
}

export function findSpecificationsByTarget(api: ApiClient, targetSystemId: string) {
  return api.get<TestSpecificationResponse[]>(`/api/targets/${targetSystemId}/test-specifications`)
}

export function findRun(api: ApiClient, runId: string) {
  return api.get<TestSpecRunResponse>(`/api/test-spec-runs/${runId}`)
}

const SPEC_STATUS_LABELS: Record<TestSpecificationStatus, string> = {
  DRAFT: '초안',
  PENDING_APPROVAL: '승인 대기',
  APPROVED: '승인됨',
  SUPERSEDED: '대체됨',
}

const RUN_STATUS_LABELS: Record<TestSpecRunStatus, string> = {
  PENDING: '대기 중',
  RUNNING: '실행 중',
  COMPLETED: '완료',
  FAILED: '실행 실패',
  RECOVERY_REQUIRED: '복구 필요',
}

const RISK_LABELS: Record<SpecRisk, string> = {
  SAFE: '안전',
  MODERATE: '상태 변경 가능',
  DESTRUCTIVE: '파괴적 영향 가능',
}

export function specificationStatusLabel(status: TestSpecificationStatus): string {
  return SPEC_STATUS_LABELS[status]
}

export function runStatusLabel(status: TestSpecRunStatus): string {
  return RUN_STATUS_LABELS[status]
}

export function riskLabel(risk: SpecRisk): string {
  return RISK_LABELS[risk]
}

export function isRisky(specification: TestSpecificationResponse): boolean {
  return specification.risk !== 'SAFE'
}

export function isSpecApprovable(specification: TestSpecificationResponse): boolean {
  return specification.status === 'PENDING_APPROVAL' && specification.profileVersionActive
}

export function isRunPolling(status: TestSpecRunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}
