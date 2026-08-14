import type { TargetTestBatch } from '../../api/targetTests'

interface BatchResultPanelProps {
  batch: TargetTestBatch
  onRefresh: () => void
  onRequestApproval: () => void
  onNewBatch: () => void
}

export function BatchResultPanel({ batch, onRefresh, onRequestApproval, onNewBatch }: BatchResultPanelProps) {
  const canApprove = batch.status === 'PENDING_APPROVAL'
  return (
    <section className="card batch-result-panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Batch 상태</p>
          <h2><StatusBadge status={batch.status} /></h2>
        </div>
        <div className="button-row compact">
          <button type="button" className="secondary-button" onClick={onRefresh}>새로고침</button>
          <button type="button" className="secondary-button" onClick={onNewBatch}>새 Batch</button>
        </div>
      </div>
      <p className="muted">Batch ID: <code>{batch.id}</code></p>
      {batch.failureMessage && <p className="notice error">{batch.failureMessage}</p>}
      {canApprove && <button type="button" onClick={onRequestApproval}>선택한 GET 테스트 승인</button>}
      {batch.status === 'RECOVERY_REQUIRED' && <p className="notice error">실행 중 앱이 재시작되어 결과를 자동으로 재시도하지 않았습니다. 새 Batch를 만들어 다시 진행하세요.</p>}
      <ul className="batch-item-list">
        {batch.items.map((item) => (
          <li key={item.id}>
            <span><StatusBadge status={item.status} /></span>
            <span><strong>{item.sequenceNumber}. {item.title}</strong><small><code>{item.method} {item.path}</code></small></span>
            <span>{item.httpStatus ? `HTTP ${item.httpStatus}` : '—'}<small>{item.latencyMs === null ? '—' : `${item.latencyMs} ms`}</small></span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function StatusBadge({ status }: { status: string }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{status}</span>
}
