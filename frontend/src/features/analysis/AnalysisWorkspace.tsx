import { useCallback, useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { AnalysisComparison, AnalysisConfiguration, AnalysisRunDetails, RootCauseReport } from '../../api/analysis'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'
import { useSessionStorageState } from '../../hooks/useSessionStorageState'
import { AnalysisConfigurationSelector } from './AnalysisConfigurationSelector'
import { RootCausePanel } from './RootCausePanel'

interface AnalysisWorkspaceProps {
  api: ApiClient
  targetTestBatchId: string | null
}

interface AnalysisTracking {
  targetTestBatchId: string
  comparisonId: string | null
  runIds: string[]
}

export function AnalysisWorkspace({ api, targetTestBatchId }: AnalysisWorkspaceProps) {
  const [selected, setSelected] = useState<AnalysisConfiguration[]>([])
  const [tracking, setTracking] = useSessionStorageState<AnalysisTracking | null>('arl.analysis-tracking', null)
  const [comparison, setComparison] = useState<AnalysisComparison | null>(null)
  const [detail, setDetail] = useState<AnalysisRunDetails | null>(null)
  const [rootCause, setRootCause] = useState<RootCauseReport | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { key: analysisKey, renew: renewAnalysisKey } = useIdempotencyKey('analysis-request')
  const { key: rootCauseKey, renew: renewRootCauseKey } = useIdempotencyKey('root-cause-request')

  const refresh = useCallback(async () => {
    if (!tracking || tracking.targetTestBatchId !== targetTestBatchId) return
    try {
      if (tracking.comparisonId) {
        setComparison(await api.get<AnalysisComparison>(`/api/analysis-comparisons/${tracking.comparisonId}`))
      }
      if (tracking.runIds.length === 1) {
        setDetail(await api.get<AnalysisRunDetails>(`/api/analysis-runs/${tracking.runIds[0]}`))
      }
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }, [api, targetTestBatchId, tracking])

  useEffect(() => { void refresh() }, [refresh])

  useEffect(() => {
    const statuses = comparison?.runs.map((run) => run.status) ?? [detail?.status]
    if (!statuses.some(isPending)) return
    const timer = window.setTimeout(() => void refresh(), 1_500)
    return () => window.clearTimeout(timer)
  }, [comparison, detail, refresh])

  useEffect(() => {
    if (!rootCause || !isPending(rootCause.status)) return
    const timer = window.setTimeout(() => void refreshRootCause(rootCause.id), 1_500)
    return () => window.clearTimeout(timer)
  }, [rootCause, api])

  async function startAnalysis() {
    if (!targetTestBatchId || selected.length === 0) return
    await run(async () => {
      let next: AnalysisTracking
      if (selected.length === 1) {
        const [configuration] = selected
        const path = configuration.architecture === 'SINGLE'
          ? `/api/test-batches/${targetTestBatchId}/analyses`
          : `/api/test-batches/${targetTestBatchId}/multi-analyses`
        const result = await api.post<{ id: string }>(path, { modelKey: configuration.modelKey }, 'executor', analysisKey)
        next = { targetTestBatchId, comparisonId: null, runIds: [result.id] }
        setDetail(null)
        setComparison(null)
      } else {
        const result = await api.post<AnalysisComparison>(
          `/api/test-batches/${targetTestBatchId}/analysis-comparisons`,
          { configurations: selected.map(({ architecture, modelKey }) => ({ architecture, modelKey })) },
          'executor',
          analysisKey,
        )
        next = { targetTestBatchId, comparisonId: result.id, runIds: result.runs.map((entry) => entry.analysisRunId) }
        setComparison(result)
        setDetail(null)
      }
      setTracking(next)
      setRootCause(null)
      setMessage('선택한 분석 조합만 요청했습니다. 다른 모델이나 구조는 자동으로 실행하지 않습니다.')
    })
  }

  async function loadRun(runId: string) {
    await run(async () => {
      setDetail(await api.get<AnalysisRunDetails>(`/api/analysis-runs/${runId}`))
      setRootCause(null)
    })
  }

  async function requestRootCause() {
    if (!detail) return
    await run(async () => {
      const report = await api.post<RootCauseReport>(
        `/api/analysis-runs/${detail.id}/root-cause-reports`,
        { modelKey: detail.modelKey },
        'executor',
        rootCauseKey,
      )
      setRootCause(report)
      setMessage('근거 기반 원인 가설과 개선 제안을 요청했습니다. 구현이나 승인 작업은 수행하지 않습니다.')
    })
  }

  async function refreshRootCause(reportId: string) {
    try {
      setRootCause(await api.get<RootCauseReport>(`/api/root-cause-reports/${reportId}`))
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }

  function updateSelection(configurations: AnalysisConfiguration[]) {
    setSelected(configurations)
    renewAnalysisKey()
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setMessage(null)
      await action()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  if (!targetTestBatchId) {
    return <section className="card future-workspace"><h2>먼저 완료된 Batch를 선택하세요.</h2><p>안전 테스트 화면에서 Batch를 만들고 결과를 확인한 뒤 분석 화면으로 이동하세요.</p></section>
  }

  return (
    <div className="analysis-workspace">
      <section className="card">
        <p className="eyebrow">1. 분석 조합 선택</p>
        <h2>선택한 조합만 실행</h2>
        <p className="muted">Single/Multi와 GPT_OSS/QWEN을 원하는 만큼 조합할 수 있습니다. 선택하지 않은 조합은 실행하지 않습니다.</p>
        <AnalysisConfigurationSelector selected={selected} onChange={updateSelection} />
        <div className="button-row">
          <button type="button" onClick={() => void startAnalysis()} disabled={busy || selected.length === 0}>
            {selected.length === 1 ? '선택한 분석 시작' : `${selected.length}개 조합 비교 시작`}
          </button>
          <button type="button" className="secondary-button" onClick={() => void refresh()} disabled={busy}>상태 새로고침</button>
        </div>
        {message && <p className={message.includes(':') && !message.startsWith('선택한') && !message.startsWith('근거') ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      {comparison && <ComparisonPanel comparison={comparison} onSelectRun={(runId) => void loadRun(runId)} />}
      {detail && <RunDetailPanel detail={detail} onRequestRootCause={() => void requestRootCause()} onRenewRootCauseKey={renewRootCauseKey} busy={busy} />}
      {rootCause && <RootCausePanel report={rootCause} />}
    </div>
  )
}

function ComparisonPanel({ comparison, onSelectRun }: { comparison: AnalysisComparison; onSelectRun: (runId: string) => void }) {
  return (
    <section className="card comparison-panel">
      <p className="eyebrow">비교 결과</p>
      <h2>{comparison.runs.length}개 선택 조합 · Evidence {comparison.evidenceCount}개</h2>
      <div className="comparison-grid">
        {comparison.runs.map((run) => (
          <button key={run.analysisRunId} type="button" className="comparison-run" onClick={() => onSelectRun(run.analysisRunId)}>
            <span className="status-badge">{run.status}</span>
            <strong>{run.architecture} · {run.modelKey}</strong>
            <small>{run.summary ?? '분석 결과를 기다리는 중입니다.'}</small>
            <small>{run.durationMillis === null ? '—' : `${run.durationMillis} ms`} · token {formatTokens(run.promptTokenCount, run.completionTokenCount)}</small>
          </button>
        ))}
      </div>
    </section>
  )
}

function RunDetailPanel({ detail, onRequestRootCause, onRenewRootCauseKey, busy }: {
  detail: AnalysisRunDetails
  onRequestRootCause: () => void
  onRenewRootCauseKey: () => void
  busy: boolean
}) {
  return (
    <section className="card run-detail-panel">
      <p className="eyebrow">분석 상세</p>
      <h2>{detail.agentType} · {detail.modelKey} · <span className="status-badge">{detail.status}</span></h2>
      {detail.summary && <p>{detail.summary}</p>}
      {detail.failureMessage && <p className="notice error">{detail.failureMessage}</p>}
      <p className="muted">입력 {detail.promptTokenCount ?? '—'} token · 출력 {detail.completionTokenCount ?? '—'} token · {detail.durationMillis ?? '—'} ms</p>
      <div className="button-row">
        <button type="button" onClick={onRequestRootCause} disabled={busy || detail.status !== 'COMPLETED'}>원인 가설·개선 제안 보기</button>
        <button type="button" className="secondary-button" onClick={onRenewRootCauseKey}>새 Root-cause 요청 키</button>
      </div>
      <ResultList title="Findings" entries={detail.findings.map((item) => ({ title: `[${item.severity}] ${item.title}`, detail: item.detail }))} />
      <ResultList title="Recommendations" entries={detail.recommendations.map((item) => ({ title: `[${item.priority}] ${item.title}`, detail: item.recommendation }))} />
    </section>
  )
}

function ResultList({ title, entries }: { title: string; entries: Array<{ title: string; detail: string }> }) {
  if (entries.length === 0) return null
  return <section className="result-list"><h3>{title}</h3>{entries.map((entry) => <article key={entry.title}><strong>{entry.title}</strong><p>{entry.detail}</p></article>)}</section>
}

function isPending(status: string | undefined): boolean {
  return status === 'REQUESTED' || status === 'RUNNING' || status === 'PENDING'
}

function formatTokens(prompt: number | null, completion: number | null): string {
  return `${prompt ?? '—'} / ${completion ?? '—'}`
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
