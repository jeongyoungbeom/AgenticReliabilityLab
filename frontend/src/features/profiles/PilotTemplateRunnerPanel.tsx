import { useEffect, useRef, useState } from 'react'
import { ApiClient, ApiError, formatApiError } from '../../api/ApiClient'
import type { PilotDiscovery } from '../../api/pilotDiscovery'
import type { PilotTemplateExecution } from '../../api/pilotTemplates'
import { preflightLabel, type TargetCredentialPreflightResult } from '../../api/targetCredentials'
import { FailureDiagnosisDetails } from '../../components/FailureDiagnosisDetails'
import { cleanupLabel } from '../../components/cleanupStatus'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'

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
  const currentTargetId = useRef<string | null>(targetSystemId)
  currentTargetId.current = targetSystemId
  const harnessReady = harnessPreflight?.status === 'READY'
  // Kept across a failed attempt on purpose: re-sending the same key replays the stored session instead of
  // executing against the Target a second time. It is renewed only when the request itself changes - a
  // different selection (toggle) or a completed execution - because the server rejects a reused key that
  // carries a different selection.
  const { key: idempotencyKey, renew: renewIdempotencyKey } = useIdempotencyKey('pilot-template')

  useEffect(() => {
    let current = true
    const targetId = targetSystemId
    setDiscovery(null)
    setSelected([])
    setResult(null)
    setMessage(null)
    if (!targetId) return () => { current = false }
    void load(targetId, () => current && currentTargetId.current === targetId)
    return () => { current = false }
  }, [api, targetSystemId, refreshKey])

  useEffect(() => {
    if (!harnessReady) setSelected([])
  }, [harnessReady])

  async function load(targetId: string, isCurrent: () => boolean = () => currentTargetId.current === targetId) {
    try {
      setBusy(true)
      setMessage(null)
      const loaded = await api.get<PilotDiscovery>(`/api/targets/${targetId}/pilot-discovery`)
      if (isCurrent()) setDiscovery(loaded)
    } catch (error) {
      if (isCurrent()) setMessage(errorMessage(error))
    } finally {
      if (isCurrent()) setBusy(false)
    }
  }

  function toggle(candidateId: string) {
    // The stored session is keyed by Target + selection, so a changed selection is a different request.
    renewIdempotencyKey()
    setSelected((current) => current.includes(candidateId)
      ? current.filter((id) => id !== candidateId)
      : [...current, candidateId])
  }

  async function execute() {
    if (!targetSystemId || !harnessReady || selected.length === 0) return
    const targetId = targetSystemId
    const candidateIds = [...selected]
    const accepted = window.confirm(
      `${candidateIds.length}개 고정 템플릿을 순서대로 실행합니다. 각 실행 전 reset 검증을 하고, 끝나면 reset/fault 해제를 검증합니다. 계속할까요?`,
    )
    if (!accepted) return
    try {
      setBusy(true)
      setMessage(null)
      const completed = await api.post<PilotTemplateExecution>(
        `/api/targets/${targetId}/pilot-template-runs`,
        { candidateIds, confirmation: 'EXECUTE_PILOT_TEMPLATES' },
        'executor',
        idempotencyKey,
      )
      if (currentTargetId.current !== targetId) return
      setResult(completed)
      renewIdempotencyKey()
    } catch (error) {
      if (currentTargetId.current === targetId) setMessage(errorMessage(error))
    } finally {
      if (currentTargetId.current === targetId) setBusy(false)
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
        <button className="secondary-button" type="button" onClick={() => void load(targetSystemId)} disabled={busy}>후보 새로고침</button>
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
          <p className={sessionResultClass(result)}>
            파일럿 세션 {result.id.slice(0, 8)} · 판정 {result.resultOutcome ?? '-'} · 상태 {result.status} · 정리 {cleanupLabel(result.cleanupVerified)}
          </p>
          {result.diagnosis && <FailureDiagnosisDetails diagnosis={result.diagnosis} />}
          {result.failure && !result.diagnosis && <p className="notice error">{result.failure}</p>}
          <button className="secondary-button" type="button" onClick={() => onOpenSession(result.id)}>세션 결과 보기</button>
          <ul>
            {result.outcomes.map((outcome) => (
              <li key={outcome.candidateId}>
                <strong>{labelFor(outcome.candidateId)}</strong>
                <span className={outcomeResultClass(outcome.resultOutcome, outcome.status)}>판정 {outcome.resultOutcome ?? '-'}</span>
                <span className={outcome.status === 'COMPLETED' ? 'badge ok' : 'badge warn'}>상태 {outcome.status}</span>
                <small>정리 {cleanupLabel(outcome.cleanupVerified)}</small>
                {outcome.diagnosis && <FailureDiagnosisDetails diagnosis={outcome.diagnosis} />}
                {!outcome.diagnosis && (outcome.failureMessage || outcome.failureCode) && (
                  <small className="candidate-blocker">{failureText(outcome.failureCode, outcome.failureMessage)}</small>
                )}
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
  if (error instanceof ApiError) return formatApiError(error)
  return error instanceof Error ? error.message : '고정 템플릿 실행을 완료하지 못했습니다.'
}

function sessionResultClass(result: PilotTemplateExecution): string {
  if (result.status === 'RECOVERY_REQUIRED' || result.cleanupVerified !== true) return 'notice error'
  return result.resultOutcome === 'PASSED' ? 'notice success' : 'notice warning'
}

function outcomeResultClass(
  resultOutcome: PilotTemplateExecution['outcomes'][number]['resultOutcome'],
  status: PilotTemplateExecution['outcomes'][number]['status'],
): string {
  return status === 'COMPLETED' && resultOutcome === 'PASSED' ? 'badge ok' : 'badge warn'
}

function failureText(failureCode: string | null, failureMessage: string | null): string {
  return failureMessage ? `${failureCode ?? 'EXECUTION_FAILED'}: ${failureMessage}` : failureCode ?? 'EXECUTION_FAILED'
}
