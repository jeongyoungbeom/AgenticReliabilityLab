export interface TargetProfile {
  id: string
  targetSystemId: string
  source: string
  status: 'DRAFT' | 'ACTIVE' | 'SUPERSEDED'
  checksum: string
  openApiPath: string | null
  openApiPaths?: string[]
  genericHttpEnabled: boolean
  readOnlyOperationCount: number
  experimentProfilePresent: boolean
  createdAt: string
  activatedAt: string | null
}

export interface TargetProfileValidation {
  targetSystemId: string
  targetName: string
  environment: string
  openApiPath: string | null
  openApiPaths?: string[]
  genericHttpEnabled: boolean
  readOnlyOperationCount: number
  experimentProfilePresent: boolean
}
