export type ExperimentRunStatus =
  | 'CREATED' | 'VALIDATING' | 'PREPARING' | 'RUNNING' | 'COLLECTING' | 'CLEANING'
  | 'COMPLETED' | 'FAILED' | 'VALIDATION_FAILED' | 'RECOVERY_REQUIRED'

export type SystemOutcome = 'PASSED' | 'FAILED' | 'INCONCLUSIVE' | 'NOT_EVALUATED'

export type InvariantOutcome = 'PASSED' | 'FAILED' | 'NOT_EVALUATED'

export type CleanupStatus = 'NOT_REQUIRED' | 'PENDING' | 'VERIFIED' | 'FAILED' | 'UNKNOWN'

export interface ExperimentRun {
  id: string
  targetSystem: string
  type: string
  definitionVersion: string
  runStatus: ExperimentRunStatus | string
  systemOutcome: SystemOutcome | string
  invariantResult: string | null
  outcomeReason: string | null
  cleanupStatus: CleanupStatus | string
  cleanupFailureCode: string | null
  queuedAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface InvariantVerdict {
  id: string
  title: string
  outcome: InvariantOutcome | string
  expected: string
  observed: string
  detail: string
}

export interface InvariantResult {
  invariantVersion: string
  outcome: string
  workloadCompleted: boolean
  targetReportedStatus: string
  expectedSuccessCount: number
  expectedFinalStock: number
  verdicts: InvariantVerdict[]
}

/**
 * The verdict payload is stored as a JSON string, so a malformed or absent value must not blank the whole screen.
 *
 * Returning null lets the caller keep showing run status and cleanup state, which are the parts that still tell the
 * operator whether the Target was left dirty.
 */
export function parseInvariantResult(raw: string | null): InvariantResult | null {
  if (raw === null || raw.trim() === '') return null
  try {
    const parsed = JSON.parse(raw) as Partial<InvariantResult>
    if (!Array.isArray(parsed.verdicts)) return null
    return {
      invariantVersion: parsed.invariantVersion ?? 'unknown',
      outcome: parsed.outcome ?? 'NOT_EVALUATED',
      workloadCompleted: parsed.workloadCompleted ?? false,
      targetReportedStatus: parsed.targetReportedStatus ?? 'UNKNOWN',
      expectedSuccessCount: parsed.expectedSuccessCount ?? 0,
      expectedFinalStock: parsed.expectedFinalStock ?? 0,
      verdicts: parsed.verdicts,
    }
  } catch {
    return null
  }
}

const OUTCOME_LABELS: Record<string, string> = {
  PASSED: '통과',
  FAILED: '위반',
  INCONCLUSIVE: '판정 불가',
  NOT_EVALUATED: '판정하지 않음',
}

export function outcomeLabel(outcome: string): string {
  return OUTCOME_LABELS[outcome] ?? outcome
}

const CLEANUP_LABELS: Record<string, string> = {
  NOT_REQUIRED: '정리 불필요',
  PENDING: '정리 대기',
  VERIFIED: '정리 확인됨',
  FAILED: '정리 실패',
  UNKNOWN: '정리 상태 불명',
}

export function cleanupLabel(status: string): string {
  return CLEANUP_LABELS[status] ?? status
}

const CLEANUP_FAILURE_LABELS: Record<string, string> = {
  CLEANUP_NOT_VERIFIED: '기록된 리소스의 정리가 확인되지 않았습니다. Target에 잔여물이 남아 있을 수 있습니다.',
  CLEANUP_NO_RESOURCES: 'Target이 만든 리소스를 하나도 보고하지 않아 정리를 증명할 수 없습니다. Harness 계약 결함일 수 있습니다.',
  OUTCOME_UNKNOWN: '외부 작업 결과를 확인할 수 없어 정리 상태를 알 수 없습니다.',
}

/** The two ways cleanup goes unverified send the operator to different places, so they are never merged here. */
export function cleanupFailureLabel(code: string | null): string | null {
  if (code === null) return null
  return CLEANUP_FAILURE_LABELS[code] ?? code
}

/** An unverified cleanup blocks the next experiment on the same Target, so the screen must say so. */
export function blocksNextRun(status: string): boolean {
  return status === 'FAILED' || status === 'UNKNOWN'
}
