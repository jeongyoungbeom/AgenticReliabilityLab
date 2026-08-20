import type { KnowledgeCitation } from './targetKnowledge'

export type TestCandidateCategory =
  | 'AVAILABILITY'
  | 'CONTRACT_INPUT'
  | 'WORKFLOW'
  | 'RETRY_RECOVERY'
  | 'IDEMPOTENCY'
  | 'CONCURRENCY'
  | 'CONSISTENCY'

export type TestCandidateRisk = 'SAFE' | 'MODERATE' | 'DESTRUCTIVE'

export type TestCandidateReadiness = 'EXECUTABLE' | 'CAPABILITY_UNAVAILABLE' | 'NEEDS_USER_INPUT' | 'UNSUPPORTED'

export type ExecutionBindingKind = 'READ_ONLY_BATCH' | 'EXPERIMENT' | 'UNBOUND'

export type CandidateUnresolvedReason =
  | 'NO_SAFE_EXECUTION_PATH'
  | 'MISSING_INVARIANT'
  | 'MISSING_TEST_DATA_LIFECYCLE'
  | 'MISSING_OBSERVATION_CAPABILITY'
  | 'UNSUPPORTED_TEST_TYPE'

export interface ExecutionBinding {
  kind: ExecutionBindingKind | string
  targetTestCandidateIds: string[]
  experimentType: string | null
  requiredCapability: string | null
  unresolvedReason: CandidateUnresolvedReason | string | null
  unresolvedDetail: string | null
}

export interface TestCandidate {
  id: string
  sequenceNumber: number
  category: TestCandidateCategory | string
  title: string
  description: string
  risk: TestCandidateRisk | string
  confidence: string
  readiness: TestCandidateReadiness | string
  verifiedExpectation: string
  preconditions: string[]
  binding: ExecutionBinding
  citations: KnowledgeCitation[]
  requiredEvidence: string[]
  dataLifecyclePlan: string | null
}

export interface TestCandidateGeneration {
  id: string
  targetSystemId: string
  knowledgeSnapshotId: string
  profileVersionId: string
  profileVersionActive: boolean
  source: string
  generatorVersion: string
  checksum: string
  createdBy: string
  createdAt: string
  candidates: TestCandidate[]
}

export type TestCandidateGenerationSummary = Omit<TestCandidateGeneration, 'candidates'>

export const CANDIDATE_CATEGORIES: ReadonlyArray<{ id: TestCandidateCategory; title: string }> = [
  { id: 'AVAILABILITY', title: '가용성' },
  { id: 'CONTRACT_INPUT', title: '계약·입력' },
  { id: 'WORKFLOW', title: '워크플로' },
  { id: 'RETRY_RECOVERY', title: '재시도·복구' },
  { id: 'IDEMPOTENCY', title: '멱등성' },
  { id: 'CONCURRENCY', title: '동시성' },
  { id: 'CONSISTENCY', title: '정합성' },
]

export const MAX_CANDIDATE_TITLE_CHARACTERS = 200
export const MAX_CANDIDATE_DESCRIPTION_CHARACTERS = 1_000
export const MAX_CANDIDATE_PATH_CHARACTERS = 500
export const MAX_CANDIDATE_STATEMENT_CHARACTERS = 500

/**
 * Only an EXECUTABLE candidate may be carried into a Test Plan.
 *
 * Readiness is recomputed by the server on every read from the stored binding and the Target's current capability, so
 * the screen must never cache an earlier verdict or infer executability from the binding kind alone.
 */
export function isCandidateExecutable(candidate: TestCandidate): boolean {
  return candidate.readiness === 'EXECUTABLE'
}

const READINESS_LABELS: Record<string, string> = {
  EXECUTABLE: '실행 가능',
  CAPABILITY_UNAVAILABLE: '기능 없음',
  NEEDS_USER_INPUT: '추가 입력 필요',
  UNSUPPORTED: '지원하지 않음',
}

export function readinessLabel(readiness: string): string {
  return READINESS_LABELS[readiness] ?? readiness
}

const UNRESOLVED_REASON_LABELS: Record<string, string> = {
  NO_SAFE_EXECUTION_PATH: '안전하게 실행할 경로가 아직 없습니다',
  MISSING_INVARIANT: '무엇이 참이어야 하는지가 정해지지 않았습니다',
  MISSING_TEST_DATA_LIFECYCLE: '테스트 데이터를 만들고 정리할 방법이 없습니다',
  MISSING_OBSERVATION_CAPABILITY: '결과를 관측할 수단이 없습니다',
  UNSUPPORTED_TEST_TYPE: '아직 지원하지 않는 테스트 종류입니다',
}

/** What is missing, in the user's terms — a candidate that cannot run must say why, not just refuse. */
export function unresolvedReasonLabel(reason: string | null): string | null {
  if (reason === null) return null
  return UNRESOLVED_REASON_LABELS[reason] ?? reason
}
