import type { TargetProfile } from '../../api/targetProfile'

interface ActiveProfileListProps {
  profiles: TargetProfile[]
  selectedTargetId: string | null
  onSelectTarget: (targetSystemId: string) => void
}

export function ActiveProfileList({ profiles, selectedTargetId, onSelectTarget }: ActiveProfileListProps) {
  if (profiles.length === 0) {
    return <p className="empty-state">활성화된 Target Profile이 없습니다.</p>
  }

  return (
    <ul className="profile-list">
      {profiles.map((profile) => (
        <li key={profile.id} className={selectedTargetId === profile.targetSystemId ? 'selected' : undefined}>
          <button type="button" onClick={() => onSelectTarget(profile.targetSystemId)}>
            <span>{profile.targetSystemId}</span>
            <small>{profile.readOnlyOperationCount}개 GET 후보 · {profile.genericHttpEnabled ? '실행 가능' : '실행 비활성'}</small>
          </button>
        </li>
      ))}
    </ul>
  )
}
