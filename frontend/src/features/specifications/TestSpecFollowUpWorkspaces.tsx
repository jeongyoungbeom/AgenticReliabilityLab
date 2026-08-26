import { useEffect, useRef, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  findRun,
  triggerRegressionRuns,
  type InvariantVerdict,
  type TestSpecRegressionRunsResponse,
  type TestSpecRunResponse,
} from '../../api/testSpecifications'
import {
  findTestSpecGeneration,
  findTestSpecMisjudgmentReport,
  isGenerationPolling,
  isMisjudgmentPolling,
  reportTestSpecMisjudgment,
  startTestSpecGeneration,
  type TestSpecGenerationRunResponse,
  type TestSpecMisjudgmentReportResponse,
} from '../../api/testSpecGenerations'
import type { TargetKnowledgeSnapshot } from '../../api/targetKnowledge'
import type { TestCandidateGeneration, TestCandidateGenerationSummary } from '../../api/testCandidates'
import { TestSpecJudgementBadge } from '../../components/TestSpecJudgement'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'

interface TargetWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
}

export function RegressionRunWorkspace({ api, targetSystemId }: TargetWorkspaceProps) {
  const [result, setResult] = useState<TestSpecRegressionRunsResponse | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const regressionRequestVersion = useRef(0)
  const { key, renew } = useIdempotencyKey('test-spec-regression')

  useEffect(() => {
    regressionRequestVersion.current += 1
    setResult(null)
    setMessage(null)
    setFailed(false)
    setBusy(false)
  }, [targetSystemId])

  async function trigger() {
    if (!targetSystemId) return
    const requestVersion = ++regressionRequestVersion.current
    try {
      setBusy(true)
      setMessage(null)
      const loaded = await triggerRegressionRuns(api, targetSystemId, key)
      if (requestVersion !== regressionRequestVersion.current) return
      setResult(loaded)
      renew()
      setFailed(false)
      setMessage(`승인된 명세 ${loaded.runs.length}개의 회귀 실행을 요청했습니다. 한 명세의 실패가 다른 명세 결과를 지우지 않습니다.`)
    } catch (error) {
      if (requestVersion === regressionRequestVersion.current) {
        setFailed(true)
        setMessage(errorMessage(error, '회귀 실행을 요청하지 못했습니다.'))
      }
    } finally {
      if (requestVersion === regressionRequestVersion.current) setBusy(false)
    }
  }

  if (!targetSystemId) return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>

  return (
    <section className="card regression-workspace">
      <p className="eyebrow">회귀 재실행</p>
      <h2>승인된 최신 명세를 각각 실행합니다</h2>
      <p className="muted">같은 specKey는 최고 버전만 실행합니다. 한 명세가 Target 보호 상태로 거부돼도 나머지 outcome은 그대로 남습니다.</p>
      <div className="button-row">
        <button type="button" onClick={() => void trigger()} disabled={busy}>회귀 실행 요청</button>
      </div>
      {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      {result && (
        <ul className="regression-list">
          {result.runs.map((outcome) => (
            <li key={outcome.specificationId}>
              <div className="section-heading">
                <strong>{outcome.specKey} · v{outcome.version}</strong>
                {outcome.run?.resultOutcome ? <TestSpecJudgementBadge kind="run" value={outcome.run.resultOutcome} /> : <span className="badge danger">실행 불가</span>}
              </div>
              {outcome.run ? <p className="muted">Run {outcome.run.id} · {outcome.run.status}</p> : <p className="notice error">{outcome.failureCode}: {outcome.failureMessage}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

interface TestSpecGenerationWorkspaceProps extends TargetWorkspaceProps {
  onOpenApproval: (specificationId: string) => void
}

export function TestSpecGenerationWorkspace({ api, targetSystemId, onOpenApproval }: TestSpecGenerationWorkspaceProps) {
  const [snapshots, setSnapshots] = useState<TargetKnowledgeSnapshot[]>([])
  const [snapshotId, setSnapshotId] = useState('')
  const [openApiDocument, setOpenApiDocument] = useState('')
  const [modelKey, setModelKey] = useState('')
  const [generation, setGeneration] = useState<TestSpecGenerationRunResponse | null>(null)
  const [ruleCandidates, setRuleCandidates] = useState<TestCandidateGeneration | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const activeGenerationId = useRef<string | null>(null)
  const generationRequestVersion = useRef(0)
  const { key, renew } = useIdempotencyKey('test-spec-generation')

  useEffect(() => {
    let current = true
    setSnapshots([])
    setSnapshotId('')
    setRuleCandidates(null)
    setGeneration(null)
    setBusy(false)
    activeGenerationId.current = null
    generationRequestVersion.current += 1
    if (!targetSystemId) return () => { current = false }

    Promise.all([
      api.get<TargetKnowledgeSnapshot[]>(`/api/targets/${targetSystemId}/knowledge-snapshots`),
      api.get<TestCandidateGenerationSummary[]>(`/api/targets/${targetSystemId}/test-candidate-generations`),
    ])
      .then(async ([loadedSnapshots, summaries]) => {
        if (!current) return
        setSnapshots(loadedSnapshots)
        const usable = loadedSnapshots.find((snapshot) => snapshot.confirmed && snapshot.profileVersionActive)
        if (usable) setSnapshotId(usable.id)
        const latest = summaries[0]
        if (latest) {
          const candidates = await api.get<TestCandidateGeneration>(`/api/test-candidate-generations/${latest.id}`)
          if (current) setRuleCandidates(candidates)
        }
      })
      .catch((error: unknown) => { if (current) report(error) })
    return () => {
      current = false
      activeGenerationId.current = null
      generationRequestVersion.current += 1
    }
  }, [api, targetSystemId])

  useEffect(() => {
    if (!generation || !isGenerationPolling(generation.status)) return
    const generationId = generation.id
    const timer = window.setTimeout(() => {
      findTestSpecGeneration(api, generationId)
        .then((loaded) => { if (activeGenerationId.current === generationId) setGeneration(loaded) })
        .catch((error: unknown) => { if (activeGenerationId.current === generationId) report(error) })
    }, 2_000)
    return () => window.clearTimeout(timer)
  }, [api, generation])

  async function start() {
    if (!targetSystemId || !snapshotId) return
    const openApiBytes = new TextEncoder().encode(openApiDocument).byteLength
    if (openApiBytes > 1_048_576) {
      setFailed(true)
      setMessage('OpenAPI 문서는 1 MiB를 넘길 수 없습니다.')
      return
    }
    const requestVersion = ++generationRequestVersion.current
    try {
      setBusy(true)
      const loaded = await startTestSpecGeneration(
        api,
        targetSystemId,
        {
          knowledgeSnapshotId: snapshotId,
          openApiDocument: openApiDocument.trim() || undefined,
          modelKey: modelKey.trim() || undefined,
        },
        key,
      )
      if (requestVersion !== generationRequestVersion.current) return
      activeGenerationId.current = loaded.id
      setGeneration(loaded)
      renew()
      setFailed(false)
      setMessage('모델 제안을 비동기로 요청했습니다. 승인 전에는 어떤 제안도 실행되지 않습니다.')
    } catch (error) {
      if (requestVersion === generationRequestVersion.current) report(error)
    } finally {
      if (requestVersion === generationRequestVersion.current) setBusy(false)
    }
  }

  function report(error: unknown) {
    setFailed(true)
    setMessage(errorMessage(error, 'LLM 제안을 처리하지 못했습니다.'))
  }

  if (!targetSystemId) return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>

  return (
    <div className="workspace-grid generation-workspace">
      <section className="card">
        <p className="eyebrow">LLM 명세 제안</p>
        <h2>규칙 기반 목록이 놓친 가설만 검토합니다</h2>
        <p className="muted">모델의 제안은 가설입니다. 기존 검증기를 통과한 ACCEPTED 후보도 별도 승인 전에는 실행할 수 없습니다.</p>
        <label>
          확정된 Target 이해 모델
          <select value={snapshotId} onChange={(event) => setSnapshotId(event.target.value)}>
            <option value="">선택하세요</option>
            {snapshots.map((snapshot) => (
              <option key={snapshot.id} value={snapshot.id} disabled={!snapshot.confirmed || !snapshot.profileVersionActive}>
                {snapshot.checksum.slice(0, 12)}{snapshot.confirmed ? '' : ' · 미확인'}{snapshot.profileVersionActive ? '' : ' · 대체됨'}
              </option>
            ))}
          </select>
        </label>
        <label>
          OpenAPI 원문 (선택 · 저장하지 않음)
          <textarea aria-label="LLM OpenAPI document" rows={8} spellCheck={false} value={openApiDocument} onChange={(event) => setOpenApiDocument(event.target.value)} />
        </label>
        <label>
          모델 키 (선택 · 서버 기본값 사용 가능)
          <input aria-label="LLM model key" value={modelKey} onChange={(event) => setModelKey(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" onClick={() => void start()} disabled={busy || !snapshotId || (generation !== null && isGenerationPolling(generation.status))}>제안 생성</button>
          <span className="field-note">{new TextEncoder().encode(openApiDocument).byteLength.toLocaleString()} / 1,048,576 bytes</span>
        </div>
        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      <section className="card">
        <p className="eyebrow">규칙 기반 후보와 비교</p>
        <h2>기존 목록</h2>
        {ruleCandidates ? (
          <ul className="candidate-comparison-list">
            {ruleCandidates.candidates.map((candidate) => <li key={candidate.id}><strong>{candidate.title}</strong><small>{candidate.category} · {candidate.readiness}</small></li>)}
          </ul>
        ) : <p className="muted">비교할 규칙 기반 후보가 아직 없습니다.</p>}
      </section>

      {generation && (
        <section className="card generation-results">
          <div className="section-heading">
            <div>
              <p className="eyebrow">모델 제안 · {generation.modelId}</p>
              <h2>{generation.status}</h2>
            </div>
            <span className={generation.status === 'COMPLETED' ? 'badge ok' : generation.status === 'FAILED' ? 'badge danger' : 'badge warn'}>{generation.candidates.length}개</span>
          </div>
          {generation.failureMessage && <p className="notice error">{generation.failureCode}: {generation.failureMessage}</p>}
          {generation.status !== 'COMPLETED' && <p className="notice warning">상태를 2초마다 확인합니다.</p>}
          <ul className="generation-candidate-list">
            {generation.candidates.map((candidate) => (
              <GenerationCandidate
                key={candidate.ordinal}
                candidate={candidate}
                onOpenApproval={onOpenApproval}
              />
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

interface MisjudgmentReportWorkspaceProps extends TargetWorkspaceProps {
  selectedRunId: string | null
  onOpenApproval: (specificationId: string) => void
}

export function MisjudgmentReportWorkspace({ api, targetSystemId, selectedRunId, onOpenApproval }: MisjudgmentReportWorkspaceProps) {
  const [runId, setRunId] = useState(selectedRunId ?? '')
  const [run, setRun] = useState<TestSpecRunResponse | null>(null)
  const [selectedVerdict, setSelectedVerdict] = useState<ViolationSelection | null>(null)
  const [reason, setReason] = useState('')
  const [report, setReport] = useState<TestSpecMisjudgmentReportResponse | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const activeReportId = useRef<string | null>(null)
  const reportRequestVersion = useRef(0)
  const { key, renew } = useIdempotencyKey('test-spec-misjudgment')

  useEffect(() => {
    reportRequestVersion.current += 1
    activeReportId.current = null
    setReport(null)
    setRun(null)
    setSelectedVerdict(null)
    setBusy(false)
    return () => {
      reportRequestVersion.current += 1
      activeReportId.current = null
    }
  }, [targetSystemId])

  useEffect(() => {
    if (selectedRunId) setRunId(selectedRunId)
  }, [selectedRunId])

  useEffect(() => {
    if (!report || !isMisjudgmentPolling(report.status)) return
    const reportId = report.id
    const timer = window.setTimeout(() => {
      findTestSpecMisjudgmentReport(api, reportId)
        .then((loaded) => { if (activeReportId.current === reportId) setReport(loaded) })
        .catch((error: unknown) => { if (activeReportId.current === reportId) reportError(error) })
    }, 2_000)
    return () => window.clearTimeout(timer)
  }, [api, report])

  const violations = run ? violatedVerdicts(run) : []

  async function loadRun() {
    if (!runId.trim()) return
    const requestVersion = ++reportRequestVersion.current
    activeReportId.current = null
    setReport(null)
    try {
      setBusy(true)
      const loaded = await findRun(api, runId.trim())
      if (requestVersion !== reportRequestVersion.current) return
      if (loaded.targetSystemId !== targetSystemId) {
        setRun(null)
        setSelectedVerdict(null)
        setFailed(true)
        setMessage('선택한 Run은 현재 Target에 속하지 않습니다. 해당 Target을 선택한 뒤 다시 신고하세요.')
        return
      }
      setRun(loaded)
      setSelectedVerdict(violatedVerdicts(loaded)[0] ?? null)
      setFailed(false)
      setMessage(violatedVerdicts(loaded).length === 0 ? '이 실행에는 신고할 VIOLATED verdict가 없습니다.' : '위반 verdict를 선택하고, 왜 정상 동작인지 기록하세요.')
    } catch (error) {
      if (requestVersion === reportRequestVersion.current) reportError(error)
    } finally {
      if (requestVersion === reportRequestVersion.current) setBusy(false)
    }
  }

  async function submit() {
    if (!targetSystemId || !run || !selectedVerdict || !reason.trim()) return
    if (run.targetSystemId !== targetSystemId) {
      setFailed(true)
      setMessage('선택한 Run은 현재 Target에 속하지 않습니다. 해당 Target을 선택한 뒤 다시 신고하세요.')
      return
    }
    const requestVersion = ++reportRequestVersion.current
    try {
      setBusy(true)
      const created = await reportTestSpecMisjudgment(api, targetSystemId, {
        specificationId: run.specificationId,
        runId: run.id,
        trialNumber: selectedVerdict.trialNumber,
        invariantId: selectedVerdict.verdict.invariantId,
        reason: reason.trim(),
      }, key)
      if (requestVersion !== reportRequestVersion.current) return
      activeReportId.current = created.id
      setReport(created)
      renew()
      setFailed(false)
      setMessage('오판 신고를 기록했습니다. 좁은 예외 초안이 기존 검증기를 거친 뒤 결과를 표시합니다.')
    } catch (error) {
      if (requestVersion === reportRequestVersion.current) reportError(error)
    } finally {
      if (requestVersion === reportRequestVersion.current) setBusy(false)
    }
  }

  function reportError(error: unknown) {
    setFailed(true)
    setMessage(errorMessage(error, '오판 신고를 처리하지 못했습니다.'))
  }

  if (!targetSystemId) return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>

  return (
    <div className="workspace-grid misjudgment-workspace">
      <section className="card">
        <p className="eyebrow">오판 신고</p>
        <h2>위반 판정이 왜 정상인지 기록합니다</h2>
        <p className="muted">이 흐름은 위반을 지우지 않습니다. 선택한 시행과 불변식에 한정된 예외 초안을 만들고, 검증과 별도 승인을 거칩니다.</p>
        <label>
          Test Spec Run ID
          <input aria-label="오판 신고 Run ID" value={runId} onChange={(event) => setRunId(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" className="secondary-button" onClick={() => void loadRun()} disabled={busy || !runId.trim()}>위반 불러오기</button>
        </div>
        {violations.length > 0 && (
          <label>
            신고할 위반
            <select
              aria-label="신고할 위반"
              value={selectedVerdict ? selectionValue(selectedVerdict) : ''}
              onChange={(event) => setSelectedVerdict(violations.find((item) => selectionValue(item) === event.target.value) ?? null)}
            >
              {violations.map((item) => <option key={selectionValue(item)} value={selectionValue(item)}>{item.trialNumber}회차 · {item.verdict.invariantId} · {item.verdict.description}</option>)}
            </select>
          </label>
        )}
        <label>
          정상인 이유
          <textarea aria-label="정상인 이유" rows={5} maxLength={2_000} value={reason} onChange={(event) => setReason(event.target.value)} />
        </label>
        <div className="button-row">
          <button type="button" onClick={() => void submit()} disabled={busy || !selectedVerdict || !reason.trim() || (report !== null && isMisjudgmentPolling(report.status))}>오판 신고 및 예외 초안 요청</button>
        </div>
        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      {report && (
        <section className="card misjudgment-result">
          <p className="eyebrow">예외 초안 결과</p>
          <h2>{report.status}</h2>
          {(report.status === 'REQUESTED' || report.status === 'RUNNING') && <p className="notice warning">상태를 2초마다 확인합니다.</p>}
          {report.draftedDescription && <p><strong>{report.draftedDescription}</strong></p>}
          {report.draftedCondition && <code>{report.draftedCondition}</code>}
          {report.rejectionReason && <p className="notice error">{report.rejectionReason}</p>}
          {report.failureMessage && <p className="notice error">{report.failureCode}: {report.failureMessage}</p>}
          <ResultingSpecificationButton specificationId={report.resultingSpecificationId} onOpenApproval={onOpenApproval} />
        </section>
      )}
    </div>
  )
}

interface ViolationSelection {
  trialNumber: number
  verdict: InvariantVerdict
}

function GenerationCandidate({
  candidate,
  onOpenApproval,
}: {
  candidate: TestSpecGenerationRunResponse['candidates'][number]
  onOpenApproval: (specificationId: string) => void
}) {
  const specificationId = candidate.specificationId
  return (
    <li>
      <div className="section-heading">
        <strong>{candidate.ordinal}. {candidate.title}</strong>
        <span className={candidate.outcome === 'ACCEPTED' ? 'badge ok' : 'badge danger'}>{candidate.outcome === 'ACCEPTED' ? '채택됨' : '거부됨'}</span>
      </div>
      <code>{candidate.specKey}</code>
      {candidate.rejectionReason && <p className="notice error">{candidate.rejectionReason}</p>}
      <pre className="document-json">{JSON.stringify(candidate.document, null, 2)}</pre>
      {specificationId && <button type="button" className="secondary-button" onClick={() => onOpenApproval(specificationId)}>승인 화면에서 검토</button>}
    </li>
  )
}

function ResultingSpecificationButton({
  specificationId,
  onOpenApproval,
}: {
  specificationId: string | null
  onOpenApproval: (specificationId: string) => void
}) {
  if (!specificationId) return null
  return <button type="button" onClick={() => onOpenApproval(specificationId)}>새 명세 버전 승인 화면으로 이동</button>
}

function violatedVerdicts(run: TestSpecRunResponse): ViolationSelection[] {
  return run.trials.flatMap((trial) => trial.verdicts
    .filter((verdict) => verdict.outcome === 'VIOLATED')
    .map((verdict) => ({ trialNumber: trial.trialNumber, verdict })))
}

function selectionValue(selection: ViolationSelection): string {
  return `${selection.trialNumber}:${selection.verdict.invariantId}`
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : fallback
}
