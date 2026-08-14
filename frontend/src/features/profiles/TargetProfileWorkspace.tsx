import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetProfile, TargetProfileValidation } from '../../api/targetProfile'
import { ActiveProfileList } from './ActiveProfileList'
import { ProfileValidationSummary } from './ProfileValidationSummary'
import { ProfileYamlInput } from './ProfileYamlInput'
import { SourceDraftWorkspace } from './SourceDraftWorkspace'

interface TargetProfileWorkspaceProps {
  api: ApiClient
  selectedTargetId: string | null
  onSelectTarget: (targetSystemId: string) => void
}

export function TargetProfileWorkspace({ api, selectedTargetId, onSelectTarget }: TargetProfileWorkspaceProps) {
  const [yaml, setYaml] = useState('')
  const [validation, setValidation] = useState<TargetProfileValidation | null>(null)
  const [draft, setDraft] = useState<TargetProfile | null>(null)
  const [activeProfiles, setActiveProfiles] = useState<TargetProfile[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    void refreshActiveProfiles()
  }, [api])

  async function refreshActiveProfiles() {
    try {
      setActiveProfiles(await api.get<TargetProfile[]>('/api/target-profiles'))
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

  return (
    <div className="workspace-grid profile-workspace">
      <ProfileYamlInput yaml={yaml} onChange={setYaml} onError={setMessage} />
      <section className="card profile-actions">
        <p className="eyebrow">2. 검증 및 등록</p>
        <h2>실행 전 안전 확인</h2>
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
        {message && <p className={message.startsWith('안전') || message.startsWith('Draft') || message.startsWith('Profile') ? 'notice success' : 'notice error'}>{message}</p>}
      </section>
      <section className="card active-profiles">
        <div className="section-heading">
          <div>
            <p className="eyebrow">3. 활성 Target</p>
            <h2>다시 등록하지 않고 선택</h2>
          </div>
          <button className="secondary-button" type="button" onClick={() => void refreshActiveProfiles()} disabled={busy}>새로고침</button>
        </div>
        <ActiveProfileList
          profiles={activeProfiles}
          selectedTargetId={selectedTargetId}
          onSelectTarget={onSelectTarget}
        />
      </section>
      <SourceDraftWorkspace
        api={api}
        onUseYaml={(value) => {
          setYaml(value)
          setValidation(null)
          setDraft(null)
          setMessage('제안 YAML을 불러왔습니다. 내용을 검토한 뒤 정상 Profile 검증을 실행하세요.')
        }}
      />
    </div>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
