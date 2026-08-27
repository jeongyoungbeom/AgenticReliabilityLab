import { useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetProfile } from '../../api/targetProfile'

interface QuickTargetRegistrationProps {
  api: ApiClient
  busy: boolean
  onRegistered: (profile: TargetProfile) => void
  onError: (message: string) => void
}

export function QuickTargetRegistration({ api, busy, onRegistered, onError }: QuickTargetRegistrationProps) {
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [environment, setEnvironment] = useState<'LOCAL' | 'TEST'>('LOCAL')
  const [submitting, setSubmitting] = useState(false)

  async function register() {
    try {
      setSubmitting(true)
      const profile = await api.post<TargetProfile>(
        '/api/target-profiles/quick-register',
        { name, baseUrl, environment },
        'profileEditor',
      )
      onRegistered(profile)
    } catch (error) {
      onError(errorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  const disabled = busy || submitting || name.trim().length === 0 || baseUrl.trim().length === 0

  return (
    <section className="card quick-target-registration">
      <p className="eyebrow">1. 간편 등록</p>
      <h2>Target 이름과 URL만 등록하세요</h2>
      <p className="muted">
        표준 seller / buyer / harness 계약과 안전한 테스트 후보를 자동으로 준비합니다. 등록 중에는 이 URL의 허용된
        Swagger/OpenAPI 경로만 확인합니다.
      </p>
      <div className="quick-target-fields">
        <label>
          <span>Target 이름</span>
          <input aria-label="Target 이름" value={name} onChange={(event) => setName(event.target.value)} placeholder="예: SideProject local" />
        </label>
        <label>
          <span>Target URL</span>
          <input aria-label="Target URL" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="http://host.docker.internal:18080" />
        </label>
        <label>
          <span>환경</span>
          <select aria-label="Target 환경" value={environment} onChange={(event) => setEnvironment(event.target.value as 'LOCAL' | 'TEST')}>
            <option value="LOCAL">LOCAL</option>
            <option value="TEST">TEST</option>
          </select>
        </label>
      </div>
      <div className="button-row">
        <button type="button" onClick={() => void register()} disabled={disabled}>
          {submitting ? 'Swagger 확인·등록 중…' : '간편 등록'}
        </button>
      </div>
    </section>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '간편 등록을 완료하지 못했습니다.'
}
