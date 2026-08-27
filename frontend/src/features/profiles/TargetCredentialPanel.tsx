import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  preflightLabel,
  TARGET_CREDENTIAL_SESSION_HEADER,
  type TargetCredentialPreflightResult,
  type TargetRuntimeCredentialStatus,
} from '../../api/targetCredentials'

interface TargetCredentialPanelProps {
  api: ApiClient
  targetSystemId: string | null
  onCredentialSessionChange: (credentialSessionId: string | null) => void
}

type TargetCredentialInputs = Record<'seller' | 'buyer' | 'harness', string>

const EMPTY_CREDENTIALS: TargetCredentialInputs = { seller: '', buyer: '', harness: '' }

export function TargetCredentialPanel({ api, targetSystemId, onCredentialSessionChange }: TargetCredentialPanelProps) {
  const [values, setValues] = useState<TargetCredentialInputs>(EMPTY_CREDENTIALS)
  const [status, setStatus] = useState<TargetRuntimeCredentialStatus | null>(null)
  const [preflight, setPreflight] = useState<TargetCredentialPreflightResult[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setValues(EMPTY_CREDENTIALS)
    setStatus(null)
    setPreflight([])
    setMessage(null)
    setFailed(false)
    onCredentialSessionChange(null)
  }, [targetSystemId, onCredentialSessionChange])

  async function save() {
    if (!targetSystemId) return
    await run(async () => {
      const saved = await api.put<TargetRuntimeCredentialStatus>(
        `/api/targets/${targetSystemId}/runtime-credentials`,
        values,
        'executor',
        credentialSessionHeaders(status?.credentialSessionId),
      )
      setStatus(saved)
      onCredentialSessionChange(saved.credentialSessionId || null)
      setValues(EMPTY_CREDENTIALS)
      setMessage('Target 자격증명을 만료형 런타임 메모리에만 보관했습니다. 입력칸은 지웠습니다.')
    })
  }

  async function check() {
    if (!targetSystemId) return
    await run(async () => {
      setPreflight(await api.post<TargetCredentialPreflightResult[]>(
        `/api/targets/${targetSystemId}/runtime-credentials/preflight`,
        {},
        'executor',
        undefined,
        credentialSessionHeaders(status?.credentialSessionId),
      ))
      setMessage('역할별 안전한 GET preflight를 완료했습니다.')
    })
  }

  async function clear() {
    if (!targetSystemId) return
    await run(async () => {
      setStatus(await api.delete<TargetRuntimeCredentialStatus>(
        `/api/targets/${targetSystemId}/runtime-credentials`,
        'executor',
        credentialSessionHeaders(status?.credentialSessionId),
      ))
      setPreflight([])
      setValues(EMPTY_CREDENTIALS)
      onCredentialSessionChange(null)
      setMessage('이 Target의 런타임 자격증명을 메모리에서 지웠습니다.')
    })
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setFailed(false)
      setMessage(null)
      await action()
    } catch (error) {
      setFailed(true)
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  if (!targetSystemId) return null
  const hasInput = Object.values(values).some((value) => value.trim().length > 0)

  return (
    <section className="card target-credentials">
      <p className="eyebrow">5. Target 접근 설정</p>
      <h2>seller / buyer / harness 테스트 자격증명</h2>
      <p className="notice warning">
        ARL 접근 토큰과 다른 값입니다. DB·YAML·Evidence·브라우저 저장소에 기록하지 않고 30분 뒤 또는 ARL 재시작 시 사라집니다.
      </p>
      <div className="access-token-grid">
        {(['seller', 'buyer', 'harness'] as const).map((role) => (
          <label key={role}>
            <span>Target {role}</span>
            <small>{role === 'harness' ? 'X-ARL-Harness-Key 값' : 'Target JWT access token'}</small>
            <input
              aria-label={`Target ${role}`}
              type="password"
              autoComplete="off"
              value={values[role]}
              onChange={(event) => setValues({ ...values, [role]: event.target.value })}
            />
          </label>
        ))}
      </div>
      <div className="button-row">
        <button type="button" onClick={() => void save()} disabled={busy || !hasInput}>런타임에 적용</button>
        <button type="button" className="secondary-button" onClick={() => void check()} disabled={busy}>역할별 preflight</button>
        <button
          type="button"
          className="secondary-button"
          onClick={() => void clear()}
          disabled={busy || status === null}
        >
          메모리에서 지우기
        </button>
      </div>
      {status && (
        <p className="muted">
          런타임 보관 역할: {status.storedRoles.join(', ') || '없음'}
          {status.expiresAt ? ` · 만료 ${new Date(status.expiresAt).toLocaleTimeString()}` : ''}
        </p>
      )}
      {preflight.length > 0 && (
        <ul className="preflight-list">
          {preflight.map((result) => (
            <li key={result.role}>
              <strong>{result.role}</strong>
              <span className={result.status === 'READY' ? 'badge ok' : 'badge warn'}>{preflightLabel(result.status)}</span>
              <small>{result.method && result.path ? `${result.method} ${result.path}` : '호출하지 않음'}{result.httpStatus ? ` · HTTP ${result.httpStatus}` : ''}</small>
            </li>
          ))}
        </ul>
      )}
      {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
    </section>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : 'Target 자격증명 요청을 완료하지 못했습니다.'
}

function credentialSessionHeaders(credentialSessionId: string | undefined): HeadersInit | undefined {
  return credentialSessionId ? { [TARGET_CREDENTIAL_SESSION_HEADER]: credentialSessionId } : undefined
}
