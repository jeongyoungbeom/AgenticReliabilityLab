import { useEffect, useRef, useState } from 'react'
import { ApiClient, ApiError, formatApiError, newIdempotencyKey } from '../../api/ApiClient'
import {
  approveAiSpecification, confirmKnowledgeSnapshot, findAiProposalRun, findAiSpecification,
  listAiProposalRuns, listKnowledgeSnapshots, startAiProposalRun,
  type AiProposalCandidate, type AiProposalRun, type KnowledgeSnapshot,
} from '../../api/aiProposals'
import { executeTestSpecification } from '../../api/testSpecifications'
import type { TargetCredentialPreflightResult } from '../../api/targetCredentials'

interface Props {
  api: ApiClient
  targetSystemId: string | null
  refreshKey: number
  credentialPreflight: TargetCredentialPreflightResult[]
  onOpenRun: (runId: string) => void
}

export function AiProposalPanel({ api, targetSystemId, refreshKey, credentialPreflight, onOpenRun }: Props) {
  const [snapshots, setSnapshots] = useState<KnowledgeSnapshot[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [runs, setRuns] = useState<AiProposalRun[]>([])
  const [currentRunId, setCurrentRunId] = useState<string | null>(null)
  const [approved, setApproved] = useState<string[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [generateFailed, setGenerateFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const generationKey = useRef<string | null>(null)
  const executionKeys = useRef<Record<string, string>>({})
  const run = runs.find((item) => item.id === currentRunId) ?? null

  useEffect(() => {
    let active = true
    setSnapshots([])
    setSelected([])
    setRuns([])
    setCurrentRunId(null)
    setApproved([])
    setMessage(null)
    setGenerateFailed(false)
    generationKey.current = null
    executionKeys.current = {}
    if (!targetSystemId) return () => { active = false }
    void Promise.all([listKnowledgeSnapshots(api, targetSystemId), listAiProposalRuns(api, targetSystemId)])
      .then(([loadedSnapshots, loadedRuns]) => {
        if (!active) return
        setSnapshots(loadedSnapshots)
        setSelected(loadedSnapshots.filter((item) => item.profileVersionActive && item.confirmed)
          .slice(0, 10).map((item) => item.id))
        setRuns(loadedRuns)
        setCurrentRunId(loadedRuns[0]?.id ?? null)
      })
      .catch((error: unknown) => { if (active) setMessage(errorMessage(error)) })
    return () => { active = false }
  }, [api, targetSystemId, refreshKey])

  useEffect(() => {
    if (!run || (run.status !== 'REQUESTED' && run.status !== 'RUNNING')) return
    let active = true
    const timer = window.setTimeout(() => {
      void findAiProposalRun(api, run.id)
        .then((updated) => {
          if (!active) return
          setRuns((current) => current.map((item) => item.id === updated.id ? updated : item))
        })
        .catch((error: unknown) => { if (active) setMessage(errorMessage(error)) })
    }, 1000)
    return () => { active = false; window.clearTimeout(timer) }
  }, [api, run])

  useEffect(() => {
    if (!run || run.status !== 'COMPLETED') return
    let active = true
    const ids = run.candidates.flatMap((candidate) => candidate.specificationId ? [candidate.specificationId] : [])
    void Promise.all(ids.map((id) => findAiSpecification(api, id).catch(() => null)))
      .then((specifications) => {
        if (active) setApproved(specifications.filter((item) => item?.status === 'APPROVED').map((item) => item!.id))
      })
    return () => { active = false }
  }, [api, run?.id, run?.status])

  if (!targetSystemId) return null

  async function confirm(snapshot: KnowledgeSnapshot) {
    if (!window.confirm(`Snapshot ${snapshot.id.slice(0, 8)}의 출처와 추출 내용을 확인했습니까?`)) return
    try {
      setBusy(true)
      const updated = await confirmKnowledgeSnapshot(api, snapshot.id)
      setSnapshots((current) => current.map((item) => item.id === updated.id ? updated : item))
      setSelected((current) => [...new Set([...current, updated.id])].slice(0, 10))
      setMessage(null)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function generate() {
    if (!targetSystemId || selected.length === 0) return
    try {
      setBusy(true)
      setMessage(null)
      setGenerateFailed(false)
      generationKey.current ??= newIdempotencyKey('ai-proposals')
      const started = await startAiProposalRun(api, targetSystemId, selected, generationKey.current)
      setRuns((current) => [started, ...current.filter((item) => item.id !== started.id)])
      setCurrentRunId(started.id)
      generationKey.current = null
    } catch (error) {
      setMessage(errorMessage(error))
      setGenerateFailed(true)
    } finally {
      setBusy(false)
    }
  }

  async function approve(candidate: AiProposalCandidate) {
    if (!candidate.specificationId) return
    try {
      setBusy(true)
      const specification = await findAiSpecification(api, candidate.specificationId)
      if (!specification.profileVersionActive) throw new Error('활성 Profile이 바뀌어 이 제안을 승인할 수 없습니다.')
      if (!window.confirm(`위험도 ${specification.risk}의 AI 제안 '${candidate.title}'을 승인합니까? 판정 조건과 입력을 확인하세요.`)) return
      const result = await approveAiSpecification(api, candidate.specificationId, specification.requiredConfirmation)
      if (result.status === 'APPROVED') setApproved((current) => [...new Set([...current, result.id])])
      setMessage(null)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function execute(candidate: AiProposalCandidate) {
    if (!candidate.specificationId) return
    if (!window.confirm(`AI 제안 '${candidate.title}'을 Target에서 실행합니까? 실행 뒤 정리 상태를 확인하세요.`)) return
    try {
      setBusy(true)
      const id = candidate.specificationId
      executionKeys.current[id] ??= newIdempotencyKey('ai-spec')
      const result = await executeTestSpecification(api, id, executionKeys.current[id])
      delete executionKeys.current[id]
      setMessage(null)
      onOpenRun(result.id)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  const accepted = run?.candidates.filter((candidate) => candidate.outcome === 'ACCEPTED') ?? []
  const rejected = run?.candidates.filter((candidate) => candidate.outcome === 'REJECTED') ?? []

  return (
    <section className="card ai-proposals">
      <div className="section-heading">
        <div>
          <p className="eyebrow">7. AI 추가 후보</p>
          <h2>기본 후보 외 테스트 제안</h2>
        </div>
      </div>
      <p className="muted">생성은 Target 실행이 아닙니다. 확정된 문서와 활성 Profile만 사용하며, 승인한 명세만 별도로 실행합니다.</p>
      {snapshots.length === 0 && <p className="notice warning">활성 Profile의 Knowledge Snapshot이 없습니다.</p>}
      {snapshots.filter((item) => item.profileVersionActive).map((snapshot) => (
        <article className="pilot-candidate" key={snapshot.id}>
          <label>
            <input
              type="checkbox"
              checked={selected.includes(snapshot.id)}
              disabled={!snapshot.confirmed || busy || (selected.length >= 10 && !selected.includes(snapshot.id))}
              onChange={() => {
                generationKey.current = null
                setSelected((current) => current.includes(snapshot.id)
                  ? current.filter((id) => id !== snapshot.id) : [...current, snapshot.id])
              }}
            />
            {' '}Snapshot {snapshot.id.slice(0, 8)} · {snapshot.confirmed ? '확인됨' : '확인 대기'}
          </label>
          <details>
            <summary>출처와 추출 내용 검토</summary>
            <p>출처: {snapshot.sources.map((source) => `${source.type} ${source.checksum.slice(0, 12)}`).join(', ')}</p>
            <ul>
              {snapshot.operations.map((operation, index) => (
                <li key={index}>{operation.method} {operation.path} · {operation.summary ?? '설명 없음'}</li>
              ))}
              {snapshot.workflows.map((workflow, index) => <li key={index}>Workflow: {workflow.title}</li>)}
              {snapshot.invariants.map((invariant, index) => <li key={index}>판정 근거: {invariant.statement}</li>)}
              {snapshot.warnings.map((warning, index) => <li key={index}>주의: {warning.message}</li>)}
            </ul>
          </details>
          {!snapshot.confirmed && (
            <button type="button" className="secondary-button" disabled={busy} onClick={() => void confirm(snapshot)}>
              검토 후 Snapshot 확인
            </button>
          )}
        </article>
      ))}
      <p className="muted">선택한 Snapshot {selected.length}/10개</p>
      <div className="button-row">
        <button type="button" disabled={busy || selected.length === 0} onClick={() => void generate()}>
          AI 추가 후보 생성
        </button>
      </div>
      {runs.length > 0 && (
        <div className="button-row">
          {runs.map((item) => (
            <button
              type="button" className="secondary-button" key={item.id}
              onClick={() => setCurrentRunId(item.id)}
            >생성 {item.id.slice(0, 8)} · {item.status}</button>
          ))}
        </div>
      )}
      {message && <p className="notice error">{message}</p>}
      {generateFailed && <p className="notice warning">추가로 검증 가능한 후보 없음</p>}
      {run && (
        <>
          {run.status === 'FAILED' && (
            <p className="notice warning">추가 제안 실패: {run.failureCode ?? 'MODEL_UNAVAILABLE'} · {run.failureMessage ?? '모델 응답을 확인하세요.'}</p>
          )}
          {(run.status === 'FAILED' || (run.status === 'COMPLETED' && accepted.length === 0)) && (
            <p className="notice warning">추가로 검증 가능한 후보 없음</p>
          )}
          {accepted.map((candidate) => (
            <article className="pilot-candidate ready" key={candidate.ordinal}>
              <h3>{candidate.title} <span className="badge ok">AI 제안 · 승인 대기</span></h3>
              <ProposalDetails candidate={candidate} />
              <div className="button-row">
                {!approved.includes(candidate.specificationId ?? '') ? (
                  <button type="button" disabled={busy} onClick={() => void approve(candidate)}>명세 승인</button>
                ) : (
                  <button type="button" disabled={busy || !rolesReady(candidate, credentialPreflight)} onClick={() => void execute(candidate)}>
                    승인한 명세 실행
                  </button>
                )}
              </div>
              {!rolesReady(candidate, credentialPreflight) && <p className="candidate-blocker">필요 역할의 credential preflight가 준비되지 않았습니다.</p>}
            </article>
          ))}
          {rejected.map((candidate) => (
            <article className="pilot-candidate not-ready" key={candidate.ordinal}>
              <h3>{candidate.title} <span className="badge warn">거부됨</span></h3>
              <p className="candidate-blocker">{candidate.rejectionReason}</p>
              <ProposalDetails candidate={candidate} />
            </article>
          ))}
        </>
      )}
    </section>
  )
}

function ProposalDetails({ candidate }: { candidate: AiProposalCandidate }) {
  const document = candidate.document
  const steps = [...array(document.setup), ...array(document.workload)]
  const calls = steps.flatMap((step) => {
    const call = record(step.call)
    return call.method && call.path ? [`${call.method} ${call.path} · ${call.authProfile ?? 'public'}`] : []
  })
  const inputs = steps.flatMap((step) => {
    const call = record(step.call)
    return call.body ? [`${call.method} ${call.path}: ${JSON.stringify(call.body)}`] : []
  })
  return (
    <details>
      <summary>근거 · 순서 · 입력 · 위험 · 기대 결과 검토</summary>
      <p>위험도: {String(document.risk ?? '표시 없음')}</p>
      <p>API 순서: {calls.join(' → ') || '호출 없음'}</p>
      <p>입력: {inputs.join('; ') || '요청 본문 없음'}</p>
      <p>근거: {array(document.evidence).map((item) =>
        `${item.sourceType ?? ''} ${item.location ?? ''}: ${item.excerpt ?? ''}`).join('; ') || '근거 없음'}</p>
      <p>기대 결과: {array(document.invariants).map((item) => String(item.condition ?? '')).join('; ') || '판정 조건 없음'}</p>
      <pre>{JSON.stringify(document, null, 2)}</pre>
    </details>
  )
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function array(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.map(record) : []
}

function rolesReady(candidate: AiProposalCandidate, preflight: TargetCredentialPreflightResult[]): boolean {
  const document = candidate.document
  const roles = [...array(document.setup), ...array(document.workload)]
    .map((step) => record(step.call).authProfile)
    .filter((role): role is string => typeof role === 'string')
  if ([...array(document.setup), ...array(document.workload)].some((step) => {
    const method = record(step.call).method
    return typeof method === 'string' && !['GET', 'HEAD'].includes(method.toUpperCase())
  })) roles.push('harness')
  return roles.every((role) => preflight.some((result) => result.role === role && result.status === 'READY'))
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return formatApiError(error)
  return error instanceof Error ? error.message : 'AI 추가 후보 요청을 완료하지 못했습니다.'
}
