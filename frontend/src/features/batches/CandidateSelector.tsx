import type { TargetTestCandidate } from '../../api/targetTests'

interface CandidateSelectorProps {
  candidates: TargetTestCandidate[]
  selectedIds: Set<string>
  onChange: (selected: Set<string>) => void
}

export function CandidateSelector({ candidates, selectedIds, onChange }: CandidateSelectorProps) {
  if (candidates.length === 0) return <p className="empty-state">이 Target에 등록된 안전한 GET 후보가 없습니다.</p>

  function toggle(candidateId: string) {
    const next = new Set(selectedIds)
    if (next.has(candidateId)) next.delete(candidateId)
    else next.add(candidateId)
    onChange(next)
  }

  return (
    <ul className="candidate-list">
      {candidates.map((candidate) => (
        <li key={candidate.id}>
          <label>
            <input
              type="checkbox"
              checked={selectedIds.has(candidate.id)}
              onChange={() => toggle(candidate.id)}
            />
            <span>
              <strong>{candidate.title}</strong>
              <small><code>{candidate.method} {candidate.path}</code> · 예상 {candidate.expectedStatusCodes.join(', ')} · {candidate.timeoutMs} ms</small>
              <small>{candidate.description}</small>
            </span>
          </label>
        </li>
      ))}
    </ul>
  )
}
