import {
  isCandidateExecutable,
  readinessLabel,
  unresolvedReasonLabel,
  type TestCandidate,
} from '../../api/testCandidates'

interface CandidateCardProps {
  candidate: TestCandidate
  selected: boolean
  onToggle: (candidateId: string) => void
}

/**
 * One recommended test, shown with why it can or cannot run.
 *
 * A candidate that is not EXECUTABLE stays visible rather than being hidden: knowing that ARL thought of a concurrency
 * test and could not run it yet is more useful than a shorter list, so the missing precondition is named instead.
 */
export function CandidateCard({ candidate, selected, onToggle }: CandidateCardProps) {
  const executable = isCandidateExecutable(candidate)
  const missing = unresolvedReasonLabel(candidate.binding.unresolvedReason)

  return (
    <li className={executable ? 'candidate-card' : 'candidate-card blocked'}>
      <div className="candidate-heading">
        <label className="candidate-select">
          <input
            type="checkbox"
            checked={selected}
            disabled={!executable}
            onChange={() => onToggle(candidate.id)}
            aria-label={`${candidate.title} 선택`}
          />
          <strong>{candidate.title}</strong>
        </label>
        <span className={executable ? 'badge ok' : 'badge warn'}>{readinessLabel(candidate.readiness)}</span>
      </div>

      <p className="candidate-meta">
        <span className="badge">{candidate.category}</span>
        <span className={candidate.risk === 'SAFE' ? 'badge ok' : 'badge warn'}>{candidate.risk}</span>
        <span className="badge">{candidate.binding.kind}</span>
        {candidate.binding.experimentType && <span className="badge">{candidate.binding.experimentType}</span>}
      </p>

      {candidate.description && <p>{candidate.description}</p>}

      <p className="candidate-expectation">
        <span className="label">확인하려는 것</span>
        {candidate.verifiedExpectation}
      </p>

      {!executable && (
        <div className="notice warning">
          <strong>지금은 실행할 수 없습니다.</strong>
          {missing && <span> {missing}</span>}
          {candidate.binding.unresolvedDetail && <span> ({candidate.binding.unresolvedDetail})</span>}
          {candidate.binding.requiredCapability && (
            <span> 필요한 기능: <code>{candidate.binding.requiredCapability}</code></span>
          )}
        </div>
      )}

      {candidate.preconditions.length > 0 && (
        <details>
          <summary>선행 조건 ({candidate.preconditions.length})</summary>
          <ul>
            {candidate.preconditions.map((precondition, index) => (
              <li key={`${index}-${precondition}`}>{precondition}</li>
            ))}
          </ul>
        </details>
      )}

      {candidate.dataLifecyclePlan && (
        <details>
          <summary>테스트 데이터 처리</summary>
          <p>{candidate.dataLifecyclePlan}</p>
        </details>
      )}

      {candidate.citations.length > 0 && (
        <details>
          <summary>근거 ({candidate.citations.length})</summary>
          <ul className="citation-list">
            {candidate.citations.map((citation, index) => (
              <li key={`${index}-${citation.location}`}>
                <span className="citation-source">{citation.sourceType} · {citation.location}</span>
                <q>{citation.excerpt}</q>
              </li>
            ))}
          </ul>
        </details>
      )}
    </li>
  )
}
