import type { ApiClient } from './ApiClient'

export type TestSpecRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'RECOVERY_REQUIRED'
export type TrialOutcome = 'PASSED' | 'VIOLATED' | 'INCONCLUSIVE'
export type InvariantOutcome = 'PASSED' | 'VIOLATED' | 'NOT_EVALUATED'
export type NotEvaluatedReason =
  | 'OBSERVATION_MISSING'
  | 'REQUIREMENT_UNMET'
  | 'OBSERVATION_INSUFFICIENT'
  | 'EXPRESSION_FAILED'
  | 'TRIAL_NOT_RUN'

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

export function executeTestSpecification(
  api: ApiClient,
  specificationId: string,
  idempotencyKey: string,
) {
  return api.post<TestSpecRunResponse>(
    `/api/test-specifications/${specificationId}/runs`,
    {},
    'executor',
    idempotencyKey,
  )
}

export function findRun(api: ApiClient, runId: string) {
  return api.get<TestSpecRunResponse>(`/api/test-spec-runs/${runId}`)
}

const RUN_STATUS_LABELS: Record<TestSpecRunStatus, string> = {
  PENDING: '대기 중',
  RUNNING: '실행 중',
  COMPLETED: '완료',
  FAILED: '실행 실패',
  RECOVERY_REQUIRED: '복구 필요',
}

export function runStatusLabel(status: TestSpecRunStatus): string {
  return RUN_STATUS_LABELS[status]
}

export function isRunPolling(status: TestSpecRunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

