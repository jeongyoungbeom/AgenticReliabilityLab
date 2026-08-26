import type { TestSpecRunResponse } from './testSpecifications'

export interface PilotTemplateExecutionOutcome {
  candidateId: string
  specificationId: string | null
  run: TestSpecRunResponse | null
  failureCode: string | null
  failureMessage: string | null
}

export interface PilotTemplateExecution {
  targetSystemId: string
  outcomes: PilotTemplateExecutionOutcome[]
}
