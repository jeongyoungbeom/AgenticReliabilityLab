export interface TargetTestCandidate {
  id: string
  kind: 'HEALTH_REACHABILITY' | 'HTTP_STATUS_ASSERTION'
  title: string
  description: string
  method: 'GET'
  path: string
  expectedStatusCodes: number[]
  timeoutMs: number
}

export interface TargetTestBatchItem {
  id: string
  candidateId: string
  sequenceNumber: number
  kind: string
  title: string
  method: string
  path: string
  expectedStatusCodes: number[]
  timeoutMs: number
  status: 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'BLOCKED'
  httpStatus: number | null
  latencyMs: number | null
  failureMessage: string | null
  completedAt: string | null
}

export interface TargetTestBatch {
  id: string
  targetSystemId: string
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'RECOVERY_REQUIRED'
  approvedAt: string | null
  approvedBy: string | null
  queuedAt: string
  startedAt: string | null
  completedAt: string | null
  failureMessage: string | null
  items: TargetTestBatchItem[]
}
