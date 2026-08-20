import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetKnowledgeSnapshot } from '../../api/targetKnowledge'
import {
  CANDIDATE_CATEGORIES,
  MAX_CANDIDATE_TITLE_CHARACTERS,
  isCandidateExecutable,
  type TestCandidateCategory,
  type TestCandidateGeneration,
  type TestCandidateGenerationSummary,
} from '../../api/testCandidates'
import { CandidateCard } from './CandidateCard'

interface CandidateWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
  selectedCandidateIds: string[]
  onSelectionChange: (candidateIds: string[]) => void
  onGenerationLoaded: (generation: TestCandidateGeneration | null) => void
}

/**
 * Generates test candidates from a confirmed understanding model and lets the user pick what to plan.
 *
 * Only a Snapshot whose Profile Version is still active can produce candidates, and only an EXECUTABLE candidate can
 * be selected, so both gates are applied here rather than left for the Plan endpoint to reject.
 */
export function CandidateWorkspace({
  api,
  targetSystemId,
  selectedCandidateIds,
  onSelectionChange,
  onGenerationLoaded,
}: CandidateWorkspaceProps) {
  const [snapshots, setSnapshots] = useState<TargetKnowledgeSnapshot[]>([])
  const [snapshotId, setSnapshotId] = useState('')
  const [generations, setGenerations] = useState<TestCandidateGenerationSummary[]>([])
  const [generation, setGeneration] = useState<TestCandidateGeneration | null>(null)
  const [directOpen, setDirectOpen] = useState(false)
  const [directRequested, setDirectRequested] = useState<TestCandidateGeneration | null>(null)
  const [directCategory, setDirectCategory] = useState<TestCandidateCategory>('CONCURRENCY')
  const [directTitle, setDirectTitle] = useState('')
  const [directInvariant, setDirectInvariant] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    setSnapshots([])
    setGenerations([])
    setSnapshotId('')
    publish(null)
    if (!targetSystemId) return () => { current = false }

    Promise.all([
      api.get<TargetKnowledgeSnapshot[]>(`/api/targets/${targetSystemId}/knowledge-snapshots`),
      api.get<TestCandidateGenerationSummary[]>(`/api/targets/${targetSystemId}/test-candidate-generations`),
    ])
      .then(([loadedSnapshots, loadedGenerations]) => {
        if (!current) return
        setSnapshots(loadedSnapshots)
        setGenerations(loadedGenerations)
        // Confirmation is an audit record, not a precondition the server enforces, so an unconfirmed Snapshot is
        // still selectable; a confirmed one is merely preferred.
        const usable = loadedSnapshots.find((s) => s.profileVersionActive && s.confirmed)
          ?? loadedSnapshots.find((s) => s.profileVersionActive)
        if (usable) setSnapshotId(usable.id)
      })
      .catch((error: unknown) => { if (current) report(error) })

    return () => { current = false }
  }, [api, targetSystemId])

  function publish(loaded: TestCandidateGeneration | null) {
    setGeneration(loaded)
    setDirectRequested(null)
    onGenerationLoaded(loaded)
    onSelectionChange([])
  }

  async function generate() {
    await run(async () => {
      const loaded = await api.post<TestCandidateGeneration>(
        '/api/test-candidate-generations',
        { knowledgeSnapshotId: snapshotId },
        'profileEditor',
      )
      publish(loaded)
      succeed(`후보 ${loaded.candidates.length}개를 만들었습니다. 아직 아무것도 실행하지 않았습니다.`)
      await refreshGenerations()
    })
  }

  async function requestDirect() {
    await run(async () => {
      const loaded = await api.post<TestCandidateGeneration>(
        '/api/test-candidate-requests',
        {
          knowledgeSnapshotId: snapshotId,
          category: directCategory,
          title: directTitle,
          invariantStatement: directInvariant.trim() || undefined,
        },
        'profileEditor',
      )
      setDirectTitle('')
      setDirectInvariant('')
      setDirectRequested(loaded)
      succeed('요청한 테스트를 후보로 기록했습니다. 아래에서 실행 가능 여부를 확인하세요.')
      await refreshGenerations()
    })
  }

  async function refreshGenerations() {
    if (!targetSystemId) return
    setGenerations(
      await api.get<TestCandidateGenerationSummary[]>(`/api/targets/${targetSystemId}/test-candidate-generations`),
    )
  }

  async function openGeneration(generationId: string) {
    await run(async () => {
      publish(await api.get<TestCandidateGeneration>(`/api/test-candidate-generations/${generationId}`))
    })
  }

  // Readiness is recomputed server-side on every read, so a candidate selected earlier can stop being executable.
  // Dropping it here keeps the selection honest instead of letting the Plan endpoint reject it later.
  useEffect(() => {
    if (generation === null) return
    const executableIds = new Set(generation.candidates.filter(isCandidateExecutable).map((c) => c.id))
    const kept = selectedCandidateIds.filter((id) => executableIds.has(id))
    if (kept.length !== selectedCandidateIds.length) onSelectionChange(kept)
  }, [generation, selectedCandidateIds])

  function toggleCandidate(candidateId: string) {
    const next = selectedCandidateIds.includes(candidateId)
      ? selectedCandidateIds.filter((id) => id !== candidateId)
      : [...selectedCandidateIds, candidateId]
    onSelectionChange(next)
  }

  function succeed(text: string) {
    setMessage(text)
    setFailed(false)
  }

  function report(error: unknown) {
    setMessage(errorMessage(error))
    setFailed(true)
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setMessage(null)
      setFailed(false)
      await action()
    } catch (error) {
      report(error)
    } finally {
      setBusy(false)
    }
  }

  if (!targetSystemId) {
    return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>
  }

  const chosenSnapshot = snapshots.find((snapshot) => snapshot.id === snapshotId) ?? null
  const snapshotUsable = chosenSnapshot !== null && chosenSnapshot.profileVersionActive
  const executableCount = generation?.candidates.filter(isCandidateExecutable).length ?? 0

  return (
    <div className="workspace-grid candidate-workspace">
      <section className="card">
        <p className="eyebrow">1. 근거 선택</p>
        <h2>어떤 이해 모델로 후보를 만들까요</h2>
        {snapshots.length === 0 ? (
          <p className="muted">이 Target에는 아직 이해 모델이 없습니다. 이전 단계에서 먼저 만드세요.</p>
        ) : (
          <label>
            이해 모델
            <select value={snapshotId} onChange={(event) => setSnapshotId(event.target.value)}>
              <option value="">선택하세요</option>
              {snapshots.map((snapshot) => (
                <option key={snapshot.id} value={snapshot.id} disabled={!snapshot.profileVersionActive}>
                  {snapshot.checksum.slice(0, 12)}
                  {snapshot.confirmed ? ' · 확인됨' : ' · 미확인'}
                  {snapshot.profileVersionActive ? '' : ' · 대체됨'}
                </option>
              ))}
            </select>
          </label>
        )}

        {chosenSnapshot && !chosenSnapshot.confirmed && (
          <p className="notice warning">
            아직 검토 확인을 하지 않은 이해 모델입니다. 후보의 근거가 되므로 먼저 내용을 확인하는 편이 좋습니다.
          </p>
        )}

        <div className="button-row">
          <button type="button" onClick={() => void generate()} disabled={busy || !snapshotUsable}>
            후보 생성
          </button>
          <button
            type="button"
            className="secondary-button"
            onClick={() => setDirectOpen((open) => !open)}
            disabled={busy || !snapshotUsable}
          >
            직접 요청
          </button>
        </div>

        {directOpen && (
          <div className="approval-box">
            <strong>테스트 직접 요청</strong>
            <p className="muted">
              등록된 분류만 요청할 수 있습니다. 요청해도 곧바로 실행되지 않고, 다른 후보와 같은 안전 검증을 거칩니다.
            </p>
            <label>
              분류
              <select
                value={directCategory}
                onChange={(event) => setDirectCategory(event.target.value as TestCandidateCategory)}
              >
                {CANDIDATE_CATEGORIES.map((category) => (
                  <option key={category.id} value={category.id}>{category.title}</option>
                ))}
              </select>
            </label>
            <label>
              제목
              <input
                type="text"
                value={directTitle}
                maxLength={MAX_CANDIDATE_TITLE_CHARACTERS}
                onChange={(event) => setDirectTitle(event.target.value)}
              />
            </label>
            <label>
              무엇이 참이어야 하나요 (선택)
              <input
                type="text"
                value={directInvariant}
                onChange={(event) => setDirectInvariant(event.target.value)}
                placeholder="재고는 0 미만이 될 수 없다"
              />
            </label>
            <button type="button" onClick={() => void requestDirect()} disabled={busy || !directTitle.trim()}>
              후보로 기록
            </button>
          </div>
        )}

        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      <section className="card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">2. 지난 생성 결과</p>
            <h2>{targetSystemId}</h2>
          </div>
          <button className="secondary-button" type="button" onClick={() => void refreshGenerations()} disabled={busy}>
            새로고침
          </button>
        </div>
        {generations.length === 0 ? (
          <p className="muted">아직 생성한 후보가 없습니다.</p>
        ) : (
          <ul className="selection-list">
            {generations.map((summary) => (
              <li key={summary.id}>
                <button type="button" className="link-button" onClick={() => void openGeneration(summary.id)}>
                  {summary.checksum.slice(0, 12)}
                </button>
                <span className="badge">{summary.source}</span>
                <span className={summary.profileVersionActive ? 'badge ok' : 'badge warn'}>
                  {summary.profileVersionActive ? '사용 가능' : '대체됨'}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {directRequested && (
        <section className="card">
          <p className="eyebrow">직접 요청 결과</p>
          <h2>{directRequested.candidates.length}개 기록됨</h2>
          <p className="muted">
            아래 후보는 방금 요청한 것입니다. 기존 후보 목록은 그대로 두었습니다.
          </p>
          <ul className="candidate-list">
            {directRequested.candidates.map((candidate) => (
              <CandidateCard
                key={candidate.id}
                candidate={candidate}
                selected={selectedCandidateIds.includes(candidate.id)}
                onToggle={toggleCandidate}
              />
            ))}
          </ul>
        </section>
      )}

      {generation && (
        <section className="card candidate-results">
          <div className="section-heading">
            <div>
              <p className="eyebrow">3. 후보</p>
              <h2>{generation.candidates.length}개 중 {executableCount}개 실행 가능</h2>
            </div>
            <span className="badge">{generation.generatorVersion}</span>
          </div>
          {!generation.profileVersionActive && (
            <p className="notice error">
              이 생성 결과의 Profile 버전이 더 이상 활성이 아닙니다. 계획에 사용할 수 없습니다.
            </p>
          )}
          <p className="muted">선택한 후보 {selectedCandidateIds.length}개는 다음 단계에서 계획으로 묶입니다.</p>
          <ul className="candidate-list">
            {generation.candidates.map((candidate) => (
              <CandidateCard
                key={candidate.id}
                candidate={candidate}
                selected={selectedCandidateIds.includes(candidate.id)}
                onToggle={toggleCandidate}
              />
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
