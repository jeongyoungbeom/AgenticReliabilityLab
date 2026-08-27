import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  preflightLabel,
  type TargetCredentialPreflightResult,
  type TargetRuntimeCredentialStatus,
} from '../../api/targetCredentials'

interface TargetCredentialPanelProps {
  api: ApiClient
  targetSystemId: string | null
  credentialStatus: TargetRuntimeCredentialStatus | null
  preflight: TargetCredentialPreflightResult[]
  onCredentialStatusChange: (status: TargetRuntimeCredentialStatus | null) => void
  onPreflightChange: (results: TargetCredentialPreflightResult[]) => void
}

type TargetCredentialInputs = Record<'seller' | 'buyer' | 'harness', string>

const EMPTY_CREDENTIALS: TargetCredentialInputs = { seller: '', buyer: '', harness: '' }

export function TargetCredentialPanel({
  api, targetSystemId, credentialStatus, preflight, onCredentialStatusChange, onPreflightChange,
}: TargetCredentialPanelProps) {
  const [values, setValues] = useState<TargetCredentialInputs>(EMPTY_CREDENTIALS)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setValues(EMPTY_CREDENTIALS)
    setMessage(null)
    setFailed(false)
    if (!targetSystemId) return
    // The session cookie survives a reload, so ask the server what it is still holding for this Target.
    void restore(targetSystemId)
  }, [api, targetSystemId])

  async function restore(id: string) {
    try {
      const status = await api.get<TargetRuntimeCredentialStatus>(
        `/api/targets/${id}/runtime-credentials`,
        'executor',
      )
      onCredentialStatusChange(status.sessionActive ? status : null)
    } catch {
      onCredentialStatusChange(null)
    }
  }

  async function save() {
    if (!targetSystemId) return
    await run(async () => {
      // A write can replace any role, so no prior GET preflight can authorize subsequent execution.
      onPreflightChange([])
      onCredentialStatusChange(await api.put<TargetRuntimeCredentialStatus>(
        `/api/targets/${targetSystemId}/runtime-credentials`,
        values,
        'executor',
      ))
      setValues(EMPTY_CREDENTIALS)
      setMessage('Target 자격증명을 ARL 런타임 메모리에만 보관했습니다. 입력칸은 지웠습니다.')
    })
  }

  async function check() {
    if (!targetSystemId) return
    await run(async () => {
      onPreflightChange(await api.post<TargetCredentialPreflightResult[]>(
        `/api/targets/${targetSystemId}/runtime-credentials/preflight`,
        {},
        'executor',
      ))
      setMessage('역할별 안전한 GET preflight를 완료했습니다.')
    })
  }

  async function clear() {
    if (!targetSystemId) return
    await run(async () => {
      await api.delete<TargetRuntimeCredentialStatus>(
        `/api/targets/${targetSystemId}/runtime-credentials`,
        'executor',
      )
      onCredentialStatusChange(null)
      onPreflightChange([])
      setValues(EMPTY_CREDENTIALS)
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
        ARL 접근 토큰과 다른 값입니다. DB·YAML·Evidence·브라우저 저장소에 기록하지 않습니다. 세션 종료, ARL 재시작, 또는 8시간 동안 쓰지 않으면 사라집니다.
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
          disabled={busy || credentialStatus === null}
        >
          메모리에서 지우기
        </button>
      </div>
      {credentialStatus && (
        <p className="muted">
          런타임 보관 역할: {credentialStatus.storedRoles.join(', ') || '없음'} · 새로고침해도 유지되며, 쓰는 동안에는 만료되지 않습니다.
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
