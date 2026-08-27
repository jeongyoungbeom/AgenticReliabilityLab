import type { TargetProfile } from '../../api/targetProfile'

interface ActiveProfileListProps {
  profiles: TargetProfile[]
  selectedTargetId: string | null
  onSelectTarget: (targetSystemId: string) => void
}

function swaggerLabel(profile: TargetProfile): string {
  const count = profile.openApiPaths?.length ?? (profile.openApiPath ? 1 : 0)
  return count > 0 ? `문서 ${count}개 확인됨` : '문서 없음'
}

export function ActiveProfileList({ profiles, selectedTargetId, onSelectTarget }: ActiveProfileListProps) {
  const registeredProfiles = profiles.filter((profile) => profile.source === 'USER_IMPORT')

  if (registeredProfiles.length === 0) {
    return <p className="empty-state">등록한 Target이 없습니다. 위에서 Target 이름과 URL을 입력해 등록하세요.</p>
  }

  return (
    <ul className="profile-list">
      {registeredProfiles.map((profile) => (
        <li key={profile.id} className={selectedTargetId === profile.targetSystemId ? 'selected' : undefined}>
          <button type="button" onClick={() => onSelectTarget(profile.targetSystemId)}>
            <span>{profile.targetName || profile.targetSystemId}</span>
            <small>
              {profile.baseUrl} · {profile.environment} · Swagger {swaggerLabel(profile)} · {profile.readOnlyOperationCount}개 GET 후보
            </small>
          </button>
        </li>
      ))}
    </ul>
  )
}
