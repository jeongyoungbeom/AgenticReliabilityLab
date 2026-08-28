import type { ApiClient, FailureDiagnosis } from './ApiClient'

export type PilotTestSessionStatus = 'RUNNING' | 'COMPLETED' | 'RECOVERY_REQUIRED'
export type PilotTestSessionItemStatus = 'COMPLETED' | 'FAILED' | 'RECOVERY_REQUIRED'
export type PilotTestSessionOutcome = 'PASSED' | 'VIOLATED' | 'INCONCLUSIVE'

export interface PilotTestSessionItem {
  candidateId: string
  specificationId: string | null
  testSpecRunId: string | null
  status: PilotTestSessionItemStatus
  resultOutcome: PilotTestSessionOutcome | null
  cleanupVerified: boolean | null
  failureCode: string | null
  failureMessage: string | null
  completedAt: string
  diagnosis?: FailureDiagnosis | null
}

/** One explicitly approved, serial selection of Pilot templates. */
export interface PilotTestSession {
  id: string
  targetSystemId: string
  profileVersionId: string
  status: PilotTestSessionStatus
  resultOutcome: PilotTestSessionOutcome | null
  cleanupVerified: boolean | null
  createdAt: string
  completedAt: string | null
  failure: string | null
  diagnosis?: FailureDiagnosis | null
  outcomes: PilotTestSessionItem[]
}

// Kept as an execution-oriented name at the POST call site; its response is now a persisted session.
export type PilotTemplateExecution = PilotTestSession

export function listPilotTestSessions(api: ApiClient, targetSystemId: string): Promise<PilotTestSession[]> {
  return api.get<PilotTestSession[]>(`/api/targets/${targetSystemId}/pilot-test-sessions`)
}
