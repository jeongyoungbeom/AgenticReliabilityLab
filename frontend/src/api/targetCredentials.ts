/**
 * The credential session id is intentionally absent: it lives only in an HttpOnly cookie the browser attaches
 * automatically, so no page script can read it and a reload keeps reaching the same server-side session.
 */
export interface TargetRuntimeCredentialStatus {
  targetSystemId: string
  storedRoles: string[]
  sessionActive: boolean
}

export type TargetCredentialPreflightStatus =
  | 'READY'
  | 'TARGET_CREDENTIAL_MISSING'
  | 'TARGET_CREDENTIAL_EXPIRED'
  | 'PREFLIGHT_NOT_CONFIGURED'
  | 'TARGET_PREFLIGHT_FAILED'
  | 'TARGET_UNREACHABLE'

export interface TargetCredentialPreflightResult {
  role: string
  status: TargetCredentialPreflightStatus
  method: string | null
  path: string | null
  httpStatus: number | null
}

const PREFLIGHT_LABELS: Record<TargetCredentialPreflightStatus, string> = {
  READY: '인증 확인됨',
  TARGET_CREDENTIAL_MISSING: '자격증명 없음',
  TARGET_CREDENTIAL_EXPIRED: '만료 또는 권한 없음',
  PREFLIGHT_NOT_CONFIGURED: '안전한 preflight 미설정',
  TARGET_PREFLIGHT_FAILED: 'Target 응답 오류',
  TARGET_UNREACHABLE: 'Target 연결 실패',
}

export function preflightLabel(status: TargetCredentialPreflightStatus): string {
  return PREFLIGHT_LABELS[status]
}
