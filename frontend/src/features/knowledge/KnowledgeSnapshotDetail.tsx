import { useState } from 'react'
import { isSnapshotConfirmable, type KnowledgeCitation, type TargetKnowledgeSnapshot } from '../../api/targetKnowledge'

interface KnowledgeSnapshotDetailProps {
  snapshot: TargetKnowledgeSnapshot
  busy: boolean
  onConfirm: () => void
}

/**
 * Shows what ARL read out of the supplied documents, with the citation behind every item.
 *
 * Nothing here is a fact about the Target until the user confirms it, so hypotheses and invariants are labelled with
 * their confidence and always carry the excerpt they came from.
 */
export function KnowledgeSnapshotDetail({ snapshot, busy, onConfirm }: KnowledgeSnapshotDetailProps) {
  return (
    <section className="card knowledge-detail">
      <div className="section-heading">
        <div>
          <p className="eyebrow">이해 모델 상세</p>
          <h2>{snapshot.targetSystemId}</h2>
        </div>
        <span className={snapshot.profileVersionActive ? 'badge ok' : 'badge warn'}>
          {snapshot.profileVersionActive ? '사용 가능' : 'Profile 버전 대체됨'}
        </span>
      </div>

      <dl className="meta-grid">
        <div><dt>추출 버전</dt><dd>{snapshot.extractionVersion}</dd></div>
        <div><dt>체크섬</dt><dd>{snapshot.checksum.slice(0, 12)}</dd></div>
        <div><dt>생성자</dt><dd>{snapshot.createdBy}</dd></div>
        <div>
          <dt>검토 확인</dt>
          <dd>{snapshot.confirmed ? (snapshot.confirmedBy ?? '확인됨') : '미확인'}</dd>
        </div>
      </dl>

      {!snapshot.profileVersionActive && (
        <p className="notice error">
          이 Snapshot을 만든 Profile 버전이 더 이상 활성이 아닙니다. 새 후보 생성에는 사용할 수 없습니다.
        </p>
      )}

      {snapshot.warnings.length > 0 && (
        <ul className="warning-list">
          {snapshot.warnings.map((warning) => (
            <li key={`${warning.code}-${warning.message}`}>
              <strong>{warning.code}</strong> {warning.message}
            </li>
          ))}
        </ul>
      )}

      <Group title="읽어낸 Operation" count={snapshot.operations.length}>
        <ul className="fact-list">
          {snapshot.operations.map((operation) => (
            <li key={`${operation.method} ${operation.path}`}>
              <span className={operation.mutability === 'READ_ONLY' ? 'badge ok' : 'badge warn'}>
                {operation.mutability}
              </span>
              <code>{operation.method} {operation.path}</code>
              {operation.summary && <p>{operation.summary}</p>}
              <Citations citations={[operation.citation]} />
            </li>
          ))}
        </ul>
      </Group>

      <Group title="워크플로" count={snapshot.workflows.length}>
        <ul className="fact-list">
          {snapshot.workflows.map((workflow, index) => (
            <li key={`${index}-${workflow.title}`}>
              <span className="badge">{workflow.confidence}</span>
              <strong>{workflow.title}</strong>
              <ol>{workflow.steps.map((step, stepIndex) => <li key={`${stepIndex}-${step}`}>{step}</li>)}</ol>
              <Citations citations={[workflow.citation]} />
            </li>
          ))}
        </ul>
      </Group>

      <Group title="도메인 가설" count={snapshot.domainHypotheses.length}>
        <ul className="fact-list">
          {snapshot.domainHypotheses.map((hypothesis) => (
            <li key={hypothesis.concept}>
              <span className="badge">{hypothesis.confidence}</span>
              <strong>{hypothesis.concept}</strong>
              <p>{hypothesis.description}</p>
              <Citations citations={hypothesis.citations} />
            </li>
          ))}
        </ul>
      </Group>

      <Group title="불변식 후보" count={snapshot.invariants.length}>
        <ul className="fact-list">
          {snapshot.invariants.map((invariant) => (
            <li key={invariant.statement}>
              <span className="badge">{invariant.confidence}</span>
              <p>{invariant.statement}</p>
              <Citations citations={invariant.citations} />
            </li>
          ))}
        </ul>
      </Group>

      <Group title="위험 신호" count={snapshot.riskSignals.length}>
        <ul className="fact-list">
          {snapshot.riskSignals.map((signal, index) => (
            <li key={`${index}-${signal.type}`}>
              <span className="badge warn">{signal.type}</span>
              <Citations citations={[signal.citation]} />
            </li>
          ))}
        </ul>
      </Group>

      {isSnapshotConfirmable(snapshot) && (
        <div className="approval-box">
          <strong>검토 확인</strong>
          <p>
            위 내용은 제출한 문서에서 읽어낸 해석입니다. 확인하면 후보 생성의 근거로 사용되며, Target에는 아무 요청도
            보내지 않습니다.
          </p>
          <button type="button" onClick={onConfirm} disabled={busy}>
            내용을 확인했습니다
          </button>
        </div>
      )}
    </section>
  )
}

function Group({ title, count, children }: { title: string; count: number; children: React.ReactNode }) {
  const [open, setOpen] = useState(count > 0)
  return (
    <details className="fact-group" open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>{title} ({count})</summary>
      {count === 0 ? <p className="muted">읽어낸 항목이 없습니다.</p> : children}
    </details>
  )
}

function Citations({ citations }: { citations: KnowledgeCitation[] }) {
  if (citations.length === 0) return null
  return (
    <ul className="citation-list">
      {citations.map((citation, index) => (
        <li key={`${index}-${citation.location}`}>
          <span className="citation-source">{citation.sourceType} · {citation.location}</span>
          <q>{citation.excerpt}</q>
        </li>
      ))}
    </ul>
  )
}
