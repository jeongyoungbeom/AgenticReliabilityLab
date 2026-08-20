export type KnowledgeConfidence = 'STATED' | 'INFERRED' | 'UNCERTAIN' | string

export interface KnowledgeCitation {
  sourceType: string
  location: string
  excerpt: string
}

export interface KnowledgeSourceDocument {
  type: string
  byteCount: number
  checksum: string
}

export interface ExtractedOperation {
  method: string
  path: string
  operationId: string | null
  summary: string | null
  requestMediaTypes: string[]
  responseStatusCodes: number[]
  mutability: string
  citation: KnowledgeCitation
}

export interface ExtractedWorkflow {
  title: string
  steps: string[]
  confidence: KnowledgeConfidence
  citation: KnowledgeCitation
}

export interface DomainHypothesis {
  concept: string
  description: string
  confidence: KnowledgeConfidence
  confirmationRequired: boolean
  citations: KnowledgeCitation[]
}

export interface ExtractedInvariant {
  statement: string
  confidence: KnowledgeConfidence
  confirmationRequired: boolean
  citations: KnowledgeCitation[]
}

export interface RiskSignal {
  type: string
  confidence: KnowledgeConfidence
  citation: KnowledgeCitation
}

export interface ExtractionWarning {
  code: string
  message: string
}

export interface TargetKnowledgeSnapshot {
  id: string
  targetSystemId: string
  profileVersionId: string
  profileVersionActive: boolean
  checksum: string
  extractionVersion: string
  confirmed: boolean
  confirmedBy: string | null
  confirmedAt: string | null
  createdBy: string
  createdAt: string
  sources: KnowledgeSourceDocument[]
  operations: ExtractedOperation[]
  workflows: ExtractedWorkflow[]
  domainHypotheses: DomainHypothesis[]
  invariants: ExtractedInvariant[]
  riskSignals: RiskSignal[]
  warnings: ExtractionWarning[]
}

export interface TargetBriefWorkflowInput {
  title: string
  steps: string[]
}

export interface CreateTargetKnowledgeSnapshotRequest {
  targetSystemId: string
  openApiDocument?: string
  readmeDocument?: string
  brief?: {
    domainTerms?: string[]
    workflows?: TargetBriefWorkflowInput[]
    invariants?: string[]
    components?: string[]
  }
}

export const KNOWLEDGE_CONFIRMATION = 'CONFIRM_TARGET_KNOWLEDGE'

/** Mirrors the server-side @Size limits so an oversized paste is named here instead of failing as a bare HTTP error. */
export const MAX_OPENAPI_DOCUMENT_CHARACTERS = 1_048_576
export const MAX_README_DOCUMENT_CHARACTERS = 262_144

/**
 * A Snapshot may only be confirmed while the Profile Version behind it is still active.
 *
 * The server rejects a late confirmation outright, so the screen hides the action rather than offering a button that
 * is guaranteed to fail.
 */
export function isSnapshotConfirmable(snapshot: TargetKnowledgeSnapshot): boolean {
  return !snapshot.confirmed && snapshot.profileVersionActive
}

/**
 * A Snapshot is only usable for planning while the Profile Version it was built from is still active.
 *
 * The backend recomputes this per request rather than storing it, so the screen must read the flag it returns instead
 * of remembering an earlier answer.
 */
export function isSnapshotUsable(snapshot: TargetKnowledgeSnapshot): boolean {
  return snapshot.profileVersionActive
}
