export type TestPlanStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'DISPATCHED' | 'CANCELLED' | 'SUPERSEDED'

export interface TestPlanItem {
  id: string
  sequenceNumber: number
  candidateId: string
  category: string
  risk: string
  bindingKind: string
  targetTestCandidateIds: string[]
}

export interface TestPlanExecutionReference {
  kind: string
  referenceId: string
}

export interface TestPlan {
  id: string
  targetSystemId: string
  knowledgeSnapshotId: string
  generationId: string
  profileVersionId: string
  profileVersionActive: boolean
  status: TestPlanStatus | string
  requiredConfirmation: string
  createdBy: string
  createdAt: string
  approvedBy: string | null
  approvedAt: string | null
  dispatchedAt: string | null
  terminalReason: string | null
  items: TestPlanItem[]
  executionReferences: TestPlanExecutionReference[]
}

const RISK_ORDER: Record<string, number> = { SAFE: 0, MODERATE: 1, DESTRUCTIVE: 2 }

/**
 * The approval a plan demands is set by its riskiest item, never by the average.
 *
 * Mirroring the server rule here lets the screen tell the user what they are about to approve before they type the
 * phrase, instead of presenting one plan-wide label that hides a destructive item among safe ones.
 */
export function highestRisk(items: TestPlanItem[]): string | null {
  if (items.length === 0) return null
  return items.reduce((worst, item) => ((RISK_ORDER[item.risk] ?? 0) > (RISK_ORDER[worst] ?? 0) ? item.risk : worst),
    items[0].risk)
}

const STATUS_LABELS: Record<string, string> = {
  PENDING_APPROVAL: '승인 대기',
  APPROVED: '승인됨',
  DISPATCHED: '실행 엔진으로 인계됨',
  CANCELLED: '취소됨',
  SUPERSEDED: '대체됨',
}

export function planStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

/** A plan that reached a terminal state can never be approved or dispatched again. */
export function isPlanTerminal(plan: TestPlan): boolean {
  return plan.status === 'CANCELLED' || plan.status === 'SUPERSEDED'
}
