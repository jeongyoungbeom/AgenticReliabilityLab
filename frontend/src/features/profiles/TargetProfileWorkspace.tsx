import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetProfile, TargetProfileValidation } from '../../api/targetProfile'
import { ActiveProfileList } from './ActiveProfileList'
import { ProfileValidationSummary } from './ProfileValidationSummary'
import { ProfileYamlInput } from './ProfileYamlInput'
import { PilotDiscoveryPanel } from './PilotDiscoveryPanel'
import { TargetCredentialPanel } from './TargetCredentialPanel'
import { PilotTemplateRunnerPanel } from './PilotTemplateRunnerPanel'
import { QuickTargetRegistration } from './QuickTargetRegistration'
import { EffectiveSettingsPanel } from './EffectiveSettingsPanel'
import type { TargetCredentialPreflightResult, TargetRuntimeCredentialStatus } from '../../api/targetCredentials'

interface TargetProfileWorkspaceProps {
  api: ApiClient
  selectedTargetId: string | null
  onSelectTarget: (targetSystemId: string) => void
  onOpenRun: (runId: string) => void
  onOpenSession: (sessionId: string) => void
  yaml: string
  onYamlChange: (yaml: string) => void
  credentialStatus: TargetRuntimeCredentialStatus | null
  onCredentialStatusChange: (status: TargetRuntimeCredentialStatus | null) => void
  credentialPreflight: TargetCredentialPreflightResult[]
  onCredentialPreflightChange: (results: TargetCredentialPreflightResult[]) => void
}

export function TargetProfileWorkspace({
  api, selectedTargetId, onSelectTarget, onOpenRun, onOpenSession, yaml, onYamlChange,
  credentialStatus, onCredentialStatusChange,
  credentialPreflight, onCredentialPreflightChange,
}: TargetProfileWorkspaceProps) {
  const [validation, setValidation] = useState<TargetProfileValidation | null>(null)
  const [draft, setDraft] = useState<TargetProfile | null>(null)
  const [activeProfiles, setActiveProfiles] = useState<TargetProfile[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [discoveryRefreshKey, setDiscoveryRefreshKey] = useState(0)

  useEffect(() => {
    void refreshActiveProfiles()
  }, [api])

  async function refreshActiveProfiles() {
    try {
      setActiveProfiles(await api.get<TargetProfile[]>('/api/target-profiles?source=USER_IMPORT'))
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }

  async function validateProfile() {
    await run(async () => {
      const result = await api.post<TargetProfileValidation>(
        '/api/target-profiles/validate',
        { yaml },
        'profileEditor',
      )
      setValidation(result)
      setDraft(null)
      setMessage('안전 정책 검증을 통과했습니다. 아직 Target에는 아무 요청도 보내지 않았습니다.')
    })
  }

  async function importProfile() {
    await run(async () => {
      const result = await api.post<TargetProfile>('/api/target-profiles', { yaml }, 'profileEditor')
      setDraft(result)
      setMessage(`Draft Version ${result.id.slice(0, 8)}이 저장되었습니다. 활성화 전에는 실행에 사용되지 않습니다.`)
    })
  }

  async function activateDraft() {
    if (!draft) return
    const accepted = window.confirm(
      `Target '${draft.targetSystemId}'의 Profile Version을 활성화합니다. 이후 새 Batch는 이 Version의 정책을 사용합니다. 계속할까요?`,
    )
    if (!accepted) return

    await run(async () => {
      const activated = await api.post<TargetProfile>(
        `/api/target-profiles/${draft.id}/activate`,
        { confirmation: 'ACTIVATE_TARGET_PROFILE_VERSION' },
        'profileEditor',
      )
      setDraft(activated)
      setMessage(`Profile Version이 활성화되었습니다. Target '${activated.targetSystemId}'을 선택해 다음 단계로 진행할 수 있습니다.`)
      onSelectTarget(activated.targetSystemId)
      setDiscoveryRefreshKey((key) => key + 1)
      await refreshActiveProfiles()
    })
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setMessage(null)
      await action()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  const selectedProfile = activeProfiles.find((profile) => profile.targetSystemId === selectedTargetId) ?? null

  const successfulMessage = message?.startsWith('안전') || message?.startsWith('Draft') || message?.startsWith('Profile') ||
    message?.includes('등록하고 Swagger')

  return (
    <div className="workspace-grid profile-workspace">
      <QuickTargetRegistration
        api={api}
        busy={busy}
        onError={setMessage}
        onRegistered={async (profile) => {
          setMessage(`'${profile.targetName}'을 등록하고 Swagger 문서 ${profile.openApiPaths?.length ?? 0}개를 확인했습니다.`)
          onSelectTarget(profile.targetSystemId)
          setDiscoveryRefreshKey((key) => key + 1)
          await refreshActiveProfiles()
        }}
      />
      <ProfileYamlInput yaml={yaml} onChange={onYamlChange} onError={setMessage} />
      <section className="card profile-actions">
        <p className="eyebrow">고급 설정</p>
        <h2>직접 작성한 Profile YAML</h2>
        <ProfileValidationSummary validation={validation} />
        <div className="button-row">
          <button type="button" onClick={() => void validateProfile()} disabled={busy || yaml.trim().length === 0}>
            정책 검증
          </button>
          <button type="button" className="secondary-button" onClick={() => void importProfile()} disabled={busy || !validation}>
            Draft 저장
          </button>
        </div>
        {draft && (
          <div className="approval-box">
            <strong>Draft {draft.id.slice(0, 8)}</strong>
            <p>활성화하면 기존 활성 Version은 superseded가 되며, 이전 Version의 대기 Batch는 승인할 수 없습니다.</p>
            <button type="button" onClick={() => void activateDraft()} disabled={busy}>
              Profile Version 활성화
            </button>
          </div>
        )}
        {message && <p className={successfulMessage ? 'notice success' : 'notice error'}>{message}</p>}
      </section>
      <section className="card active-profiles">
        <div className="section-heading">
          <div>
            <p className="eyebrow">2. 등록한 Target</p>
            <h2>등록한 Target을 선택하세요</h2>
          </div>
          <button className="secondary-button" type="button" onClick={() => void refreshActiveProfiles()} disabled={busy}>새로고침</button>
        </div>
        <ActiveProfileList
          profiles={activeProfiles}
          selectedTargetId={selectedTargetId}
          onSelectTarget={onSelectTarget}
        />
      </section>
      <EffectiveSettingsPanel
        api={api}
        profile={selectedProfile}
        onUseGeneratedYaml={(generatedYaml) => {
          onYamlChange(generatedYaml)
          setValidation(null)
          setDraft(null)
          setMessage('현재 적용 설정을 고급 YAML로 불러왔습니다. 필요한 경로만 수정한 뒤 정책 검증을 실행하세요.')
        }}
      />
      <PilotDiscoveryPanel api={api} targetSystemId={selectedTargetId} refreshKey={discoveryRefreshKey} />
      <TargetCredentialPanel
        api={api}
        targetSystemId={selectedTargetId}
        credentialStatus={credentialStatus}
        preflight={credentialPreflight}
        onCredentialStatusChange={onCredentialStatusChange}
        onPreflightChange={onCredentialPreflightChange}
      />
      <PilotTemplateRunnerPanel
        api={api}
        targetSystemId={selectedTargetId}
        refreshKey={discoveryRefreshKey}
        harnessPreflight={credentialPreflight.find((result) => result.role === 'harness') ?? null}
        onOpenRun={onOpenRun}
        onOpenSession={onOpenSession}
      />
    </div>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
