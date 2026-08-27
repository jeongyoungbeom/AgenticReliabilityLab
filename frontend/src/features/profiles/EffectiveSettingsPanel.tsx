import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { EffectiveTargetProfile } from '../../api/effectiveSettings'
import type { TargetProfile } from '../../api/targetProfile'

interface EffectiveSettingsPanelProps {
  api: ApiClient
  profile: TargetProfile | null
  onUseGeneratedYaml: (yaml: string) => void
}

/**
 * Quick registration fills in Swagger paths, Harness endpoints, network allowlists and execution limits the user
 * never typed. A default that decides whether a run is allowed has to be readable, or a refused run is unexplainable.
 */
export function EffectiveSettingsPanel({ api, profile, onUseGeneratedYaml }: EffectiveSettingsPanelProps) {
  const [settings, setSettings] = useState<EffectiveTargetProfile | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [showYaml, setShowYaml] = useState(false)

  useEffect(() => {
    let disposed = false
    setSettings(null)
    setMessage(null)
    setShowYaml(false)
    if (!profile) return () => { disposed = true }
    void (async () => {
      try {
        const loaded = await api.get<EffectiveTargetProfile>(`/api/target-profiles/${profile.id}/effective-settings`)
        if (!disposed) setSettings(loaded)
      } catch (error) {
        if (!disposed) {
          setMessage(error instanceof ApiError ? `${error.code}: ${error.message}` : '적용 설정을 불러오지 못했습니다.')
        }
      }
    })()
    return () => { disposed = true }
  }, [api, profile?.id])

  if (!profile) return null

  return (
    <section className="card effective-settings">
      <div className="section-heading">
        <div>
          <p className="eyebrow">3. 현재 적용 설정</p>
          <h2>ARL이 자동으로 채운 값</h2>
        </div>
        <button
          className="secondary-button"
          type="button"
          onClick={() => setShowYaml((shown) => !shown)}
          disabled={settings === null}
        >
          {showYaml ? 'YAML 접기' : '생성된 전체 YAML 보기'}
        </button>
        <button
          className="secondary-button"
          type="button"
          onClick={() => settings && onUseGeneratedYaml(settings.generatedYaml)}
          disabled={settings === null}
        >
          이 설정으로 고급 YAML 시작
        </button>
      </div>
      {message && <p className="notice error">{message}</p>}
      {settings && (
        <>
          <dl className="settings-grid">
            <div>
              <dt>Swagger 문서</dt>
              <dd>{settings.openApiPaths.length > 0 ? settings.openApiPaths.join(', ') : '없음'}</dd>
            </div>
            <div>
              <dt>Harness 상태</dt>
              <dd>{settings.harnessStatePath ?? '미설정'}</dd>
            </div>
            <div>
              <dt>Harness 초기화</dt>
              <dd>{settings.harnessResetPath ?? '미설정'}</dd>
            </div>
            <div>
              <dt>Harness 장애 주입 / 해제</dt>
              <dd>{settings.harnessFaultPath ?? '미설정'} / {settings.harnessFaultReleasePath ?? '미설정'}</dd>
            </div>
            <div>
              <dt>허용 네트워크</dt>
              <dd>{settings.allowedOrigin ?? settings.baseUrl}{settings.allowedCidrs.length > 0 ? ` · ${settings.allowedCidrs.join(', ')}` : ''}</dd>
            </div>
            <div>
              <dt>역할</dt>
              <dd>{settings.authProfiles.join(', ') || '없음'}</dd>
            </div>
            <div>
              <dt>실행 상한</dt>
              <dd>
                동시 {settings.maxConcurrency ?? '-'} · 요청 {settings.maxRequestCount ?? '-'} · 시도 {settings.maxTrials ?? '-'}
                {settings.requestTimeout ? ` · timeout ${settings.requestTimeout}` : ''}
              </dd>
            </div>
            <div>
              <dt>실행 허용 호출</dt>
              <dd>{settings.allowedCalls.length}개</dd>
            </div>
          </dl>
          <ul className="allowed-call-list">
            {settings.allowedCalls.map((call) => <li key={call}><code>{call}</code></li>)}
          </ul>
          {showYaml && <pre className="generated-yaml">{settings.generatedYaml}</pre>}
        </>
      )}
    </section>
  )
}
