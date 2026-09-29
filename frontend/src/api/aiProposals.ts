import type { ApiClient } from './ApiClient'

export interface KnowledgeSnapshot {
  id: string
  profileVersionId: string
  profileVersionActive: boolean
  confirmed: boolean
  sources: Array<{ type: string; checksum: string }>
  operations: Array<{ method: string; path: string; summary: string | null }>
  workflows: Array<{ title: string }>
  invariants: Array<{ statement: string }>
  warnings: Array<{ code: string; message: string }>
}

export interface AiProposalCandidate {
  ordinal: number
  outcome: 'ACCEPTED' | 'REJECTED'
  specKey: string
  title: string
  document: Record<string, unknown>
  rejectionReason: string | null
  specificationId: string | null
}

export interface AiProposalRun {
  id: string
  knowledgeSnapshotIds: string[]
  status: 'REQUESTED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  failureCode: string | null
  failureMessage: string | null
  candidates: AiProposalCandidate[]
}

export interface AiSpecification {
  id: string
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'SUPERSEDED'
  requiredConfirmation: string
  profileVersionActive: boolean
  risk: string
  document: Record<string, unknown>
  unfoundedThresholds: string[]
}

export function listKnowledgeSnapshots(api: ApiClient, targetSystemId: string) {
  return api.get<KnowledgeSnapshot[]>(`/api/targets/${targetSystemId}/knowledge-snapshots`)
}

export function confirmKnowledgeSnapshot(api: ApiClient, snapshotId: string) {
  return api.post<KnowledgeSnapshot>(
    `/api/target-knowledge-snapshots/${snapshotId}/confirmation`,
    { confirmation: 'CONFIRM_TARGET_KNOWLEDGE' },
    'profileEditor',
  )
}

export function listAiProposalRuns(api: ApiClient, targetSystemId: string) {
  return api.get<AiProposalRun[]>(`/api/targets/${targetSystemId}/test-specification-generations`)
}

export function startAiProposalRun(api: ApiClient, targetSystemId: string, snapshotIds: string[], key: string) {
  return api.post<AiProposalRun>(
    `/api/targets/${targetSystemId}/test-specification-generations`,
    { knowledgeSnapshotIds: snapshotIds },
    'profileEditor',
    key,
  )
}

export function findAiProposalRun(api: ApiClient, runId: string) {
  return api.get<AiProposalRun>(`/api/test-specification-generations/${runId}`)
}

export function findAiSpecification(api: ApiClient, specificationId: string) {
  return api.get<AiSpecification>(`/api/test-specifications/${specificationId}`)
}

export function approveAiSpecification(api: ApiClient, specificationId: string, confirmation: string) {
  return api.post<AiSpecification>(
    `/api/test-specifications/${specificationId}/approve`,
    { confirmation },
    'executor',
  )
}
