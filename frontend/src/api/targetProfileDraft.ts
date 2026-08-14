export interface TargetProfileDraft {
  source: 'OPENAPI' | 'README'
  suggestedTargetId: string
  suggestedTargetName: string
  suggestedBaseUrl: string | null
  readOnlyOperations: DraftReadOnlyOperation[]
  yaml: string
  warnings: string[]
}

export interface DraftReadOnlyOperation {
  id: string
  title: string
  description: string
  path: string
  expectedStatusCodes: number[]
}
