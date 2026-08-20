import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  blocksNextRun,
  cleanupFailureLabel,
  cleanupLabel,
  outcomeLabel,
  parseInvariantResult,
  type ExperimentRun,
} from '../../api/experiments'
import { useSessionStorageState } from '../../hooks/useSessionStorageState'

interface ExperimentResultWorkspaceProps {
  api: ApiClient
}

/**
 * Shows why an Experiment passed or failed, one invariant at a time.
 *
 * "The concurrency test failed" is not an answer an operator can act on, so each invariant is reported with what was
 * expected, what was observed, and the detail behind it. An invariant the Target never reported on stays NOT_EVALUATED
 * rather than being counted as a violation, which is why an unjudged run reads as INCONCLUSIVE and not FAILED.
 */
export function ExperimentResultWorkspace({ api }: ExperimentResultWorkspaceProps) {
  const [runId, setRunId] = useSessionStorageState<string>('arl.experiment-run-id', '')
  const [input, setInput] = useState(runId)
  const [run, setRun] = useState<ExperimentRun | null>(null)
  // Setting runId to its current value is a no-op for React, so an explicit nonce is what re-runs the fetch.
  const [reloadNonce, setReloadNonce] = useState(0)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    if (runId.trim() === '') { setRun(null); return () => { current = false } }
    setBusy(true)
    api.get<ExperimentRun>(`/api/experiments/${runId}`)
      .then((loaded) => { if (current) { setRun(loaded); setMessage(null) } })
      .catch((error: unknown) => {
        if (!current) return
        setRun(null)
        setMessage(error instanceof ApiError ? `${error.code}: ${error.message}` : '실행을 불러오지 못했습니다.')
      })
      .finally(() => { if (current) setBusy(false) })
    return () => { current = false }
  }, [api, runId, reloadNonce])

  const invariant = run ? parseInvariantResult(run.invariantResult) : null

  return (
    <div className="workspace-grid experiment-workspace">
      <section className="card">
        <p className="eyebrow">실행 조회</p>
        <h2>Experiment 결과 보기</h2>
        <p className="muted">
          실험 ID를 넣으면 판정 근거를 보여줍니다. 현재 Test Plan은 읽기 전용 Batch만 인계하므로, 여기에는 상태 변경
          실험을 직접 시작했을 때의 ID를 넣게 됩니다.
        </p>
        <label>
          Experiment Run ID
          <input type="text" value={input} onChange={(event) => setInput(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" onClick={() => setRunId(input.trim())} disabled={busy || input.trim() === ''}>
            불러오기
          </button>
          <button
            type="button"
            className="secondary-button"
            onClick={() => setReloadNonce((nonce) => nonce + 1)}
            disabled={busy || runId === ''}
          >
            새로고침
          </button>
        </div>
        {message && <p className="notice error">{message}</p>}
      </section>

      {run && (
        <section className="card">
          <div className="section-heading">
            <div>
              <p className="eyebrow">{run.type} · {run.definitionVersion}</p>
              <h2>{outcomeLabel(run.systemOutcome)}</h2>
            </div>
            <span className={run.runStatus === 'COMPLETED' ? 'badge ok' : 'badge warn'}>{run.runStatus}</span>
          </div>

          {run.systemOutcome === 'INCONCLUSIVE' && (
            <p className="notice warning">
              판정할 수 없는 실행입니다. 불변식이 깨졌다는 뜻이 아니라, 판정에 필요한 관측이 부족했다는 뜻입니다.
            </p>
          )}

          {run.outcomeReason && <p className="outcome-reason">{run.outcomeReason}</p>}

          <dl className="meta-grid">
            <div><dt>Target</dt><dd>{run.targetSystem}</dd></div>
            <div><dt>정리 상태</dt><dd>{cleanupLabel(run.cleanupStatus)}</dd></div>
            <div><dt>불변식 버전</dt><dd>{invariant?.invariantVersion ?? '-'}</dd></div>
            <div><dt>Target 보고 상태</dt><dd>{invariant?.targetReportedStatus ?? '-'}</dd></div>
          </dl>

          {run.cleanupFailureCode && (
            <div className="notice error">
              <strong>{cleanupLabel(run.cleanupStatus)}</strong>
              <p>{cleanupFailureLabel(run.cleanupFailureCode)}</p>
              {blocksNextRun(run.cleanupStatus) && (
                <p>이 Target의 다음 실험은 정리가 해결될 때까지 시작할 수 없습니다.</p>
              )}
            </div>
          )}

          {invariant === null ? (
            <p className="muted">이 실행에는 불변식 판정 기록이 없습니다.</p>
          ) : (
            <>
              {!invariant.workloadCompleted && (
                <p className="notice warning">
                  Target이 작업을 끝내지 못했습니다({invariant.targetReportedStatus}). 완료되지 않은 작업의 관측값으로는
                  불변식을 판정하지 않습니다.
                </p>
              )}
              <ul className="verdict-list">
                {invariant.verdicts.map((verdict, index) => (
                  <li key={`${index}-${verdict.id}`} className={`verdict ${verdict.outcome.toLowerCase()}`}>
                    <div className="verdict-heading">
                      <strong>{verdict.title}</strong>
                      <span className={badgeClass(verdict.outcome)}>{outcomeLabel(verdict.outcome)}</span>
                    </div>
                    <p className="verdict-values">
                      <span><span className="label">기대</span>{verdict.expected}</span>
                      <span><span className="label">관측</span>{verdict.observed}</span>
                    </p>
                    {verdict.detail && <p className="verdict-detail">{verdict.detail}</p>}
                    <code className="verdict-id">{verdict.id}</code>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </div>
  )
}

function badgeClass(outcome: string): string {
  if (outcome === 'PASSED') return 'badge ok'
  if (outcome === 'FAILED') return 'badge danger'
  return 'badge warn'
}
