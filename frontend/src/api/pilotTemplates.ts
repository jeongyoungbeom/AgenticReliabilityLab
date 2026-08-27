import type { ApiClient } from './ApiClient'

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
  outcomes: PilotTestSessionItem[]
}

// Kept as an execution-oriented name at the POST call site; its response is now a persisted session.
export type PilotTemplateExecution = PilotTestSession

export function listPilotTestSessions(api: ApiClient, targetSystemId: string): Promise<PilotTestSession[]> {
  return api.get<PilotTestSession[]>(`/api/targets/${targetSystemId}/pilot-test-sessions`)
}

export function findPilotTestSession(api: ApiClient, sessionId: string): Promise<PilotTestSession> {
  return api.get<PilotTestSession>(`/api/pilot-test-sessions/${sessionId}`)
}
