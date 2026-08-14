import type { AccessTokens } from '../api/ApiClient'

interface AccessTokenPanelProps {
  tokens: AccessTokens
  onChange: (tokens: AccessTokens) => void
}

const fields: Array<{ key: keyof AccessTokens; label: string; description: string }> = [
  { key: 'viewer', label: 'Viewer', description: '등록된 Profile·후보·결과를 조회합니다.' },
  { key: 'profileEditor', label: 'Profile editor', description: 'Profile을 검증·등록·활성화합니다.' },
  { key: 'executor', label: 'Executor', description: 'Batch 또는 Plan을 만들고 승인합니다.' },
]

export function AccessTokenPanel({ tokens, onChange }: AccessTokenPanelProps) {
  return (
    <details className="access-panel">
      <summary>접근 토큰 (SECURED 환경에서만 필요)</summary>
      <p>토큰은 이 탭의 메모리에만 머물며 브라우저 저장소에는 기록하지 않습니다.</p>
      <div className="access-token-grid">
        {fields.map((field) => (
          <label key={field.key}>
            <span>{field.label}</span>
            <small>{field.description}</small>
            <input
              type="password"
              autoComplete="off"
              value={tokens[field.key]}
              onChange={(event) => onChange({ ...tokens, [field.key]: event.target.value })}
            />
          </label>
        ))}
      </div>
    </details>
  )
}
