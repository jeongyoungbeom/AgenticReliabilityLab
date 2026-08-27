import { useEffect, useState } from 'react'
import { ApiClient, ApiError, newIdempotencyKey } from '../../api/ApiClient'
import type { PilotDiscovery } from '../../api/pilotDiscovery'
import type { PilotTemplateExecution } from '../../api/pilotTemplates'
import { preflightLabel, type TargetCredentialPreflightResult } from '../../api/targetCredentials'

interface PilotTemplateRunnerPanelProps {
  api: ApiClient
  targetSystemId: string | null
  refreshKey: number
  harnessPreflight: TargetCredentialPreflightResult | null
  onOpenRun: (runId: string) => void
  onOpenSession: (sessionId: string) => void
}

export function PilotTemplateRunnerPanel({
  api, targetSystemId, refreshKey, harnessPreflight, onOpenRun, onOpenSession,
}: PilotTemplateRunnerPanelProps) {
  const [discovery, setDiscovery] = useState<PilotDiscovery | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [result, setResult] = useState<PilotTemplateExecution | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const harnessReady = harnessPreflight?.status === 'READY'

  useEffect(() => {
    setDiscovery(null)
    setSelected([])
    setResult(null)
    setMessage(null)
    if (!targetSystemId) return
    void load()
  }, [api, targetSystemId, refreshKey])

  useEffect(() => {
    if (!harnessReady) setSelected([])
  }, [harnessReady])

  async function load() {
    if (!targetSystemId) return
    try {
      setBusy(true)
      setMessage(null)
      setDiscovery(await api.get<PilotDiscovery>(`/api/targets/${targetSystemId}/pilot-discovery`))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  function toggle(candidateId: string) {
    setSelected((current) => current.includes(candidateId)
      ? current.filter((id) => id !== candidateId)
      : [...current, candidateId])
  }

  async function execute() {
    if (!targetSystemId || !harnessReady || selected.length === 0) return
    const accepted = window.confirm(
      `${selected.length}개 고정 템플릿을 순서대로 실행합니다. 각 실행 전 reset 검증을 하고, 끝나면 reset/fault 해제를 검증합니다. 계속할까요?`,
    )
    if (!accepted) return
    try {
      setBusy(true)
      setMessage(null)
      setResult(await api.post<PilotTemplateExecution>(
        `/api/targets/${targetSystemId}/pilot-template-runs`,
        { candidateIds: selected, confirmation: 'EXECUTE_PILOT_TEMPLATES' },
        'executor',
        newIdempotencyKey('pilot-template'),
      ))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  if (!targetSystemId) return null
  const ready = harnessReady ? discovery?.candidates.filter((candidate) => candidate.readiness === 'READY') ?? [] : []

  return (
    <section className="card pilot-template-runner">
      <div className="section-heading">
        <div>
          <p className="eyebrow">6. 고정 템플릿 실행</p>
          <h2>선택 → 명시 승인 → 순차 결과</h2>
        </div>
        <button className="secondary-button" type="button" onClick={() => void load()} disabled={busy}>후보 새로고침</button>
      </div>
      <p className="notice warning">
        READY 후보만 실행합니다. seller/buyer/harness runtime credential과 preflight가 준비되어야 하며, Target 토큰은 이 화면이나 결과에 저장되지 않습니다.
      </p>
      {discovery && !harnessReady && (
        <p className="notice warning">
          Harness 실행 게이트: {harnessPreflight
            ? `${preflightLabel(harnessPreflight.status)} (${harnessPreflight.method ?? 'GET'} ${harnessPreflight.path ?? '/state'})`
            : 'Harness GET state preflight가 필요합니다.'}
          {' '}Profile에는 state, reset, fault, fault release 네 경로가 모두 선언돼야 합니다.
        </p>
      )}
      {ready.length > 0 && (
        <div className="pilot-template-choice-list">
          {ready.map((candidate) => (
            <label key={candidate.id} className="pilot-template-choice">
              <input type="checkbox" checked={selected.includes(candidate.id)} onChange={() => toggle(candidate.id)} disabled={busy} />
              <span><strong>{candidate.title}</strong><small>{candidate.description}</small></span>
            </label>
          ))}
        </div>
      )}
      {discovery && harnessReady && ready.length === 0 && (
        <p className="notice warning">현재 Swagger allowlist에서 실행 가능한 후보가 없습니다.</p>
      )}
      <div className="button-row">
        <button type="button" onClick={() => void execute()} disabled={busy || !harnessReady || selected.length === 0}>선택한 템플릿 실행</button>
      </div>
      {message && <p className="notice error">{message}</p>}
      {result && (
        <div className="pilot-template-result-list">
          <p className={result.resultOutcome === 'PASSED' ? 'notice success' : 'notice warning'}>
            파일럿 세션 {result.id.slice(0, 8)} · {result.resultOutcome ?? result.status} · 정리 {result.cleanupVerified ? 'VERIFIED' : 'REQUIRED'}
          </p>
          <button className="secondary-button" type="button" onClick={() => onOpenSession(result.id)}>세션 결과 보기</button>
          <ul>
            {result.outcomes.map((outcome) => (
              <li key={outcome.candidateId}>
                <strong>{labelFor(outcome.candidateId)}</strong>
                <span className={outcome.resultOutcome === 'PASSED' ? 'badge ok' : 'badge warn'}>{outcome.resultOutcome ?? outcome.status}</span>
                <small>cleanup {outcome.cleanupVerified ? 'VERIFIED' : 'REQUIRED'}</small>
                {outcome.failureMessage && <small className="candidate-blocker">{outcome.failureCode}: {outcome.failureMessage}</small>}
                {outcome.testSpecRunId && (
                  <button className="text-button" type="button" onClick={() => onOpenRun(outcome.testSpecRunId!)}>
                    시행 상세 보기
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function labelFor(candidateId: string): string {
  const labels: Record<string, string> = {
    availability: '가용성', 'product-create': '상품 생성', 'order-workflow': '주문 workflow', 'payment-success': '결제 성공',
    'order-idempotency': '주문 idempotency', 'order-concurrency': '주문 동시성', 'payment-failure-recovery': '결제 장애·복구',
  }
  return labels[candidateId] ?? candidateId
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '고정 템플릿 실행을 완료하지 못했습니다.'
}
