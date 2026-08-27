import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import { listPilotTestSessions, type PilotTestSession } from '../../api/pilotTemplates'

interface PilotTestSessionResultsPanelProps {
  api: ApiClient
  targetSystemId: string | null
  selectedSessionId: string | null
  onSelectSession: (sessionId: string) => void
  onOpenRun: (runId: string) => void
}

export function PilotTestSessionResultsPanel({
  api, targetSystemId, selectedSessionId, onSelectSession, onOpenRun,
}: PilotTestSessionResultsPanelProps) {
  const [sessions, setSessions] = useState<PilotTestSession[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    setSessions([])
    setMessage(null)
    if (!targetSystemId) return () => { current = false }
    setBusy(true)
    listPilotTestSessions(api, targetSystemId)
      .then((loaded) => { if (current) setSessions(loaded) })
      .catch((error: unknown) => { if (current) setMessage(errorMessage(error)) })
      .finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [api, targetSystemId])

  if (!targetSystemId) {
    return (
      <section className="card">
        <p className="eyebrow">파일럿 세션 결과</p>
        <h2>먼저 Target을 선택하세요</h2>
        <p className="muted">파일럿 실행 결과는 Target별 세션으로 보존됩니다.</p>
      </section>
    )
  }

  const selected = sessions.find((session) => session.id === selectedSessionId) ?? sessions[0] ?? null
  return (
    <section className="card pilot-test-session-results">
      <div className="section-heading">
        <div>
          <p className="eyebrow">파일럿 세션 결과</p>
          <h2>명시 승인한 선택을 한 단위로 보관합니다</h2>
        </div>
        <button type="button" className="secondary-button" onClick={() => reload(api, targetSystemId, setSessions, setMessage, setBusy)} disabled={busy}>
          새로고침
        </button>
      </div>
      <p className="muted">각 후보는 연결된 Test Spec Run으로 상세 감사 기록을 열 수 있습니다.</p>
      {message && <p className="notice error">{message}</p>}
      {!busy && sessions.length === 0 && !message && <p className="muted">이 Target에 저장된 파일럿 세션이 없습니다.</p>}
      {sessions.length > 0 && (
        <div className="button-row" aria-label="파일럿 세션 선택">
          {sessions.map((session) => (
            <button
              key={session.id}
              type="button"
              className={session.id === selected?.id ? 'active' : 'secondary-button'}
              onClick={() => onSelectSession(session.id)}
            >
              {session.id.slice(0, 8)} · {session.resultOutcome ?? session.status}
            </button>
          ))}
        </div>
      )}
      {selected && <SessionDetail session={selected} onOpenRun={onOpenRun} />}
    </section>
  )
}

function SessionDetail({ session, onOpenRun }: { session: PilotTestSession; onOpenRun: (runId: string) => void }) {
  return (
    <div className="pilot-session-detail">
      {session.status === 'RECOVERY_REQUIRED' && (
        <p className="notice error">세션이 중단되어 Target의 정리 상태를 확인해야 합니다. 다음 실행보다 복구 확인이 우선입니다.</p>
      )}
      {session.cleanupVerified === false && (
        <p className="notice error">세션 정리가 검증되지 않았습니다. 연결된 실행 기록을 확인하세요.</p>
      )}
      {session.failure && <p className="notice error">{session.failure}</p>}
      <dl className="meta-grid">
        <div><dt>세션 ID</dt><dd><code>{session.id}</code></dd></div>
        <div><dt>판정</dt><dd>{session.resultOutcome ?? session.status}</dd></div>
        <div><dt>정리 검증</dt><dd>{session.cleanupVerified === null ? '-' : session.cleanupVerified ? '확인됨' : '미확인'}</dd></div>
        <div><dt>완료 시각</dt><dd>{session.completedAt ? formatInstant(session.completedAt) : '-'}</dd></div>
      </dl>
      <h3>선택한 후보</h3>
      <ol className="pilot-template-result-list">
        {session.outcomes.map((outcome) => (
          <li key={outcome.candidateId}>
            <strong>{labelFor(outcome.candidateId)}</strong>
            <span className={outcome.resultOutcome === 'PASSED' ? 'badge ok' : 'badge warn'}>{outcome.resultOutcome ?? outcome.status}</span>
            <small>정리 {outcome.cleanupVerified === null ? '-' : outcome.cleanupVerified ? '확인됨' : '미확인'}</small>
            {outcome.failureMessage && <p className="candidate-blocker">{outcome.failureCode}: {outcome.failureMessage}</p>}
            {outcome.testSpecRunId && (
              <button className="text-button" type="button" onClick={() => onOpenRun(outcome.testSpecRunId!)}>시행 상세 보기</button>
            )}
          </li>
        ))}
      </ol>
    </div>
  )
}

async function reload(
  api: ApiClient,
  targetSystemId: string,
  setSessions: (sessions: PilotTestSession[]) => void,
  setMessage: (message: string | null) => void,
  setBusy: (busy: boolean) => void,
) {
  try {
    setBusy(true)
    setMessage(null)
    setSessions(await listPilotTestSessions(api, targetSystemId))
  } catch (error) {
    setMessage(errorMessage(error))
  } finally {
    setBusy(false)
  }
}

function labelFor(candidateId: string): string {
  const labels: Record<string, string> = {
    availability: '가용성', 'product-create': '상품 생성', 'order-workflow': '주문 workflow', 'payment-success': '결제 성공',
    'order-idempotency': '주문 idempotency', 'order-concurrency': '주문 동시성', 'payment-failure-recovery': '결제 장애·복구',
  }
  return labels[candidateId] ?? candidateId
}

function formatInstant(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : date.toLocaleString()
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '파일럿 세션 결과를 불러오지 못했습니다.'
}
