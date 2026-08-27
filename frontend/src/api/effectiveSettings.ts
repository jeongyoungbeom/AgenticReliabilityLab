export interface EffectiveTargetProfile {
  targetSystemId: string
  targetName: string
  environment: string
  baseUrl: string
  allowedOrigin: string | null
  allowedCidrs: string[]
  healthPath: string | null
  openApiPaths: string[]
  harnessStatePath: string | null
  harnessStateFields: string[]
  harnessResetPath: string | null
  harnessFaultPath: string | null
  harnessFaultReleasePath: string | null
  authProfiles: string[]
  supportedFaults: string[]
  allowedCalls: string[]
  requestTimeout: string | null
  maxConcurrency: number | null
  maxRequestCount: number | null
  maxTrials: number | null
  generatedYaml: string
}
