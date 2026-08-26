export interface PilotDiscoveredOperation {
  method: string
  swaggerPath: string
  executionPath: string
  operationId: string | null
  authProfile: string | null
  summary: string | null
}

export interface PilotTestCandidate {
  id: string
  title: string
  description: string
  readiness: 'READY' | 'NOT_READY'
  operations: PilotDiscoveredOperation[]
  missingOperations: string[]
}

export interface PilotDiscovery {
  targetSystemId: string
  profileVersionId: string
  openApiPath: string
  openApiPaths?: string[]
  snapshotId: string
  snapshotChecksum: string
  snapshotChecksums?: string[]
  discoveredOperations: PilotDiscoveredOperation[]
  ignoredOperationCount: number
  candidates: PilotTestCandidate[]
}
