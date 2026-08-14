export interface TargetProfile {
  id: string
  targetSystemId: string
  source: string
  status: 'DRAFT' | 'ACTIVE' | 'SUPERSEDED'
  checksum: string
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
  genericHttpEnabled: boolean
  readOnlyOperationCount: number
  experimentProfilePresent: boolean
}
