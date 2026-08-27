import { useEffect, useRef, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  executeTestSpecification,
  findRun,
  isRunPolling,
  runStatusLabel,
  type TestSpecRunResponse,
} from '../../api/testSpecifications'
import { NotEvaluatedReasonBadge, TestSpecJudgementBadge } from '../../components/TestSpecJudgement'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'
import { useSessionStorageState } from '../../hooks/useSessionStorageState'
import { PilotTestSessionResultsPanel } from './PilotTestSessionResultsPanel'

interface TestSpecRunWorkspaceProps {
  api: ApiClient
  selectedTargetId: string | null
  selectedPilotTestSessionId: string | null
  onSelectPilotTestSession: (sessionId: string) => void
  selectedRunId: string | null
  onSelectRun: (runId: string) => void
}

export function TestSpecRunWorkspace({
  api, selectedTargetId, selectedPilotTestSessionId, onSelectPilotTestSession, selectedRunId, onSelectRun,
}: TestSpecRunWorkspaceProps) {
  const [storedRunId, setStoredRunId] = useSessionStorageState<string>('arl.test-spec-run-id', '')
  const [runInput, setRunInput] = useState(selectedRunId ?? storedRunId)
  const [specificationInput, setSpecificationInput] = useState('')
  const [run, setRun] = useState<TestSpecRunResponse | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [reloadNonce, setReloadNonce] = useState(0)
  const mounted = useRef(true)
  const { key, renew } = useIdempotencyKey('test-spec-run')

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])


  useEffect(() => {
    if (selectedRunId) {
      setRunInput(selectedRunId)
      setStoredRunId(selectedRunId)
    }
  }, [selectedRunId, setStoredRunId])

  useEffect(() => {
    let current = true
    if (!storedRunId) {
      setRun(null)
      return () => { current = false }
    }
    setBusy(true)
    findRun(api, storedRunId)
      .then((loaded) => {
        if (!current) return
        setRun(loaded)
        setMessage(null)
        setFailed(false)
        onSelectRun(loaded.id)
        if (isRunPolling(loaded.status)) {
          window.setTimeout(() => { if (current) setReloadNonce((value) => value + 1) }, 2_000)
        }
      })
      .catch((error: unknown) => { if (current) report(error) })
      .finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [api, storedRunId, reloadNonce])

  async function execute() {
    const specificationId = specificationInput.trim()
    if (!specificationId) return
    try {
      setBusy(true)
      setMessage(null)
      const created = await executeTestSpecification(api, specificationId, key)
      if (!mounted.current) return
      setRun(created)
      setRunInput(created.id)
      setStoredRunId(created.id)
      onSelectRun(created.id)
      renew()
      setFailed(false)
      setMessage('실행이 접수되었습니다. Target별 활성 실행 슬롯은 하나뿐이며, 진행 상태를 자동으로 새로고칩니다.')
    } catch (error) {
      if (!mounted.current) return
      if (error instanceof ApiError && error.code === 'TEST_SPECIFICATION_RECOVERY_REQUIRED') {
        setFailed(false)
        setMessage('이 Target에는 진행 중이거나 복구 확인이 필요한 실행이 있습니다. Target 상태와 정리를 확인한 뒤 다시 시도하세요.')
      } else {
        report(error)
      }
    } finally {
      if (mounted.current) setBusy(false)
    }
  }

  function loadRun() {
    const next = runInput.trim()
    if (next) {
      setStoredRunId(next)
      onSelectRun(next)
    }
  }

  function report(error: unknown) {
    setFailed(true)
    setMessage(errorMessage(error))
  }

  return (
    <div className="workspace-grid specification-run-workspace">
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId={selectedTargetId}
        selectedSessionId={selectedPilotTestSessionId}
        onSelectSession={onSelectPilotTestSession}
        onOpenRun={onSelectRun}
      />
      <section className="card">
        <p className="eyebrow">명세 실행</p>
        <h2>승인한 기준으로 실행합니다</h2>
        <p className="muted">
          동일 Target은 한 번에 하나만 실행합니다. 다른 실행이 진행 중이거나 복구가 확인되지 않았다면 오류가 아니라
          Target 보호 상태로 표시됩니다.
        </p>
        <label>
          Test Specification ID
          <input aria-label="Test Specification ID" value={specificationInput} onChange={(event) => setSpecificationInput(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" onClick={() => void execute()} disabled={busy || specificationInput.trim() === ''}>실행 시작</button>
        </div>
        <label>
          Test Spec Run ID
          <input aria-label="Test Spec Run ID" value={runInput} onChange={(event) => setRunInput(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" className="secondary-button" onClick={loadRun} disabled={busy || runInput.trim() === ''}>실행 결과 불러오기</button>
          <button
            type="button"
            className="secondary-button"
            onClick={() => setReloadNonce((value) => value + 1)}
            disabled={busy || storedRunId === ''}
          >
            새로고침
          </button>
        </div>
        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      {run && <RunResult run={run} />}
    </div>
  )
}

function RunResult({ run }: { run: TestSpecRunResponse }) {
  return (
    <section className="card test-spec-run-result">
      <div className="section-heading">
        <div>
          <p className="eyebrow">실행 결과</p>
          <h2>{runStatusLabel(run.status)}</h2>
        </div>
        {run.resultOutcome && <TestSpecJudgementBadge kind="run" value={run.resultOutcome} />}
      </div>

      {run.status === 'RECOVERY_REQUIRED' && (
        <p className="notice error">
          Target이 어떤 상태인지 확인되지 않았습니다. 리셋·장애 해제 상태를 검증하기 전에는 다음 실행을 시작하지 마세요.
        </p>
      )}
      {run.cleanupVerified === false && (
        <p className="notice error">
          정리가 검증되지 않았습니다. 이 Target의 다음 판정은 이전 실행의 상태에 오염될 수 있으므로 복구가 우선입니다.
        </p>
      )}
      {run.failure && <p className="notice error">{run.failure}</p>}

      <dl className="meta-grid">
        <div><dt>요청 시행</dt><dd>{run.requestedTrials}</dd></div>
        <div><dt>실행 시행</dt><dd>{run.trialsRun ?? '-'}</dd></div>
        <div><dt>위반 시행</dt><dd>{run.trialsViolated ?? '-'}</dd></div>
        <div><dt>판정 불가 시행</dt><dd>{run.trialsInconclusive ?? '-'}</dd></div>
        <div><dt>정리 검증</dt><dd>{run.cleanupVerified === null ? '-' : run.cleanupVerified ? '확인됨' : '미확인'}</dd></div>
      </dl>

      <h3>시행별 판정</h3>
      {run.trials.length === 0 ? <p className="muted">아직 완료된 시행이 없습니다.</p> : (
        <ol className="trial-list">
          {run.trials.map((trial) => (
            <li key={trial.trialNumber}>
              <div className="section-heading">
                <strong>{trial.trialNumber}회차</strong>
                <TestSpecJudgementBadge kind="trial" value={trial.outcome} />
              </div>
              {!trial.completed && <p className="notice warning">이 시행은 완료되지 않았습니다.</p>}
              {trial.failure && <p className="notice error">{trial.failure}</p>}
              <ul className="verdict-list">
                {trial.verdicts.map((verdict) => (
                  <li key={verdict.invariantId} className={`verdict ${verdict.outcome.toLowerCase()}`}>
                    <div className="verdict-heading">
                      <strong>{verdict.description}</strong>
                      <TestSpecJudgementBadge kind="invariant" value={verdict.outcome} />
                    </div>
                    <code>{verdict.condition}</code>
                    {verdict.notEvaluatedReason && <NotEvaluatedReasonBadge reason={verdict.notEvaluatedReason} />}
                    {verdict.appliedException && <p className="notice warning">적용 예외: {verdict.appliedException}</p>}
                    {verdict.detail && <p className="verdict-detail">{verdict.detail}</p>}
                    <dl className="observed-value-list">
                      {Object.entries(verdict.observedValues).map(([name, value]) => (
                        <div key={name}><dt>{name}</dt><dd>{value}</dd></div>
                      ))}
                    </dl>
                  </li>
                ))}
              </ul>
              {trial.timings.length > 0 && (
                <details>
                  <summary>단계 시간</summary>
                  <ul className="timing-list">
                    {trial.timings.map((timing, index) => <li key={`${timing.name}-${index}`}>{timing.role} · {timing.name}: {formatInstant(timing.startedAt)} → {formatInstant(timing.endedAt)}</li>)}
                  </ul>
                </details>
              )}
              {(trial.faultEvents ?? []).length > 0 && (
                <details>
                  <summary>장애 주입·해제 감사 기록</summary>
                  <ul className="timing-list">
                    {(trial.faultEvents ?? []).map((event, index) => (
                      <li key={`${event.action}-${event.faultId ?? 'none'}-${index}`}>
                        <strong>{faultAuditLabel(event.action)}</strong> · {event.description}
                        {event.faultId && <> · faultId <code>{event.faultId}</code></>}
                        {event.faultType && <> · 유형 {event.faultType}</>}
                        {event.injectionPoint && <> · 지점 {event.injectionPoint}</>}
                        {event.ttlMs !== null && <> · TTL {event.ttlMs}ms</>}
                        {event.scope && <> · scope {event.scope}</>}
                        {event.failure && <> · 실패: {event.failure}</>}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </li>
          ))}
        </ol>
      )}

      <h3>리셋 기록</h3>
      {run.resets.length === 0 ? <p className="muted">이 실행에는 리셋 기록이 없습니다.</p> : (
        <ul className="reset-list">
          {run.resets.map((reset) => (
            <li key={reset.sequenceNumber}>
              <strong>{reset.sequenceNumber}번째 리셋</strong> · {reset.performed ? '수행됨' : '미수행'} · {reset.verified ? '검증됨' : '미검증'}
              {reset.failure && <p className="notice error">{reset.failure}</p>}
              {reset.checks.length > 0 && <ul>{reset.checks.map((check) => <li key={check.id}>{check.id}: <code>{check.condition}</code> → {check.observed} ({check.satisfied ? '충족' : '불충족'})</li>)}</ul>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function formatInstant(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : date.toLocaleString()
}

function faultAuditLabel(action: 'INJECTED' | 'RELEASED' | 'RELEASE_FAILED'): string {
  if (action === 'INJECTED') return '주입'
  if (action === 'RELEASED') return '해제됨'
  return '해제 실패'
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '실행 정보를 불러오지 못했습니다.'
}
