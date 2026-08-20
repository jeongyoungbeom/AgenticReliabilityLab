import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TestCandidate, TestCandidateGeneration } from '../../api/testCandidates'
import { isCandidateExecutable } from '../../api/testCandidates'
import { highestRisk, isPlanTerminal, planStatusLabel, type TestPlan } from '../../api/testPlans'
import { ConfirmationDialog } from '../../components/ConfirmationDialog'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'
import { useSessionStorageState } from '../../hooks/useSessionStorageState'

interface TestPlanWorkspaceProps {
  api: ApiClient
  generation: TestCandidateGeneration | null
  selectedCandidateIds: string[]
  onDispatched: (batchId: string) => void
}

/**
 * Turns a selection of candidates into one approved, auditable plan and hands it to the execution engine.
 *
 * Approval is the last point a human can stop this, so the screen shows what will run and at what risk before the
 * confirmation phrase is accepted, and it never approves and dispatches in a single click.
 */
export function TestPlanWorkspace({ api, generation, selectedCandidateIds, onDispatched }: TestPlanWorkspaceProps) {
  // There is no list endpoint for plans, so a plan id that falls out of memory cannot be recovered and its
  // pending approval would be stranded. The id is kept in session storage and the plan reloaded from it.
  const [planId, setPlanId] = useSessionStorageState<string | null>('arl.test-plan-id', null)
  const [plan, setPlan] = useState<TestPlan | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [approving, setApproving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const { key: idempotencyKey, renew } = useIdempotencyKey('test-plan')

  useEffect(() => {
    let current = true
    if (planId === null) { setPlan(null); return () => { current = false } }
    // Already holding this plan (it was just created or reloaded), so re-fetching would only spend a round-trip.
    if (plan?.id === planId) return () => { current = false }
    api.get<TestPlan>(`/api/test-plans/${planId}`)
      .then((loaded) => { if (current) setPlan(loaded) })
      .catch(() => { if (current) setPlanId(null) })
    return () => { current = false }
  }, [api, planId, plan])

  const chosen = (generation?.candidates ?? []).filter((candidate) => selectedCandidateIds.includes(candidate.id))
  const blocked = chosen.filter((candidate) => !isCandidateExecutable(candidate))
  const planRisk = plan ? highestRisk(plan.items) : null

  async function createPlan() {
    if (!generation) return
    await run(async () => {
      const created = await api.post<TestPlan>(
        '/api/test-plans',
        { generationId: generation.id, candidateIds: selectedCandidateIds },
        'executor',
        idempotencyKey,
      )
      setPlan(created)
      setPlanId(created.id)
      setConfirmation('')
      // Renew only after the created plan is recorded: retrying a request whose reply was lost must reuse the same
      // key so the server returns the existing plan instead of creating a second one.
      renew()
      succeed('계획을 만들었습니다. 아직 아무것도 실행하지 않았습니다.')
    })
  }

  async function approvePlan() {
    if (!plan) return
    // The phrase is the deliberate friction on an irreversible-ish step; checking it here avoids spending a
    // server round-trip to be told the user typed it wrong.
    if (confirmation !== plan.requiredConfirmation) {
      setMessage(`확인 문구가 다릅니다. '${plan.requiredConfirmation}'를 그대로 입력하세요.`)
      setFailed(true)
      return
    }
    await run(async () => {
      const approved = await api.post<TestPlan>(
        `/api/test-plans/${plan.id}/approve`,
        { confirmation },
        'executor',
      )
      setPlan(approved)
      setApproving(false)
      setConfirmation('')
      succeed('승인했습니다. 인계하기 전까지는 실행되지 않습니다.')
    })
  }

  async function dispatchPlan() {
    if (!plan) return
    await run(async () => {
      const dispatched = await api.post<TestPlan>(`/api/test-plans/${plan.id}/dispatch`, {}, 'executor')
      setPlan(dispatched)
      if (dispatched.status !== 'DISPATCHED') {
        setMessage(
          dispatched.status === 'SUPERSEDED'
            ? '인계 직전에 Profile 버전이 바뀌어 이 계획은 대체되었습니다. 실행되지 않았습니다.'
            : `계획이 ${planStatusLabel(dispatched.status)} 상태로 돌아왔습니다. 실행되지 않았습니다.`,
        )
        setFailed(true)
        return
      }
      const batch = dispatched.executionReferences.find((reference) => reference.kind === 'TARGET_TEST_BATCH')
      if (batch) onDispatched(batch.referenceId)
      succeed('실행 엔진으로 인계했습니다.')
    })
  }

  async function reloadPlan() {
    if (!plan) return
    await run(async () => setPlan(await api.get<TestPlan>(`/api/test-plans/${plan.id}`)))
  }

  function succeed(text: string) {
    setMessage(text)
    setFailed(false)
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setMessage(null)
      setFailed(false)
      await action()
    } catch (error) {
      setMessage(error instanceof ApiError ? `${error.code}: ${error.message}` : '요청을 완료하지 못했습니다.')
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  if (!generation) {
    return <p className="notice">먼저 테스트 후보 화면에서 후보를 만들고 선택하세요.</p>
  }

  return (
    <div className="workspace-grid plan-workspace">
      <section className="card">
        <p className="eyebrow">1. 선택 확인</p>
        <h2>계획에 담을 {chosen.length}개</h2>
        {chosen.length === 0 ? (
          <p className="muted">선택한 후보가 없습니다. 이전 화면에서 실행 가능한 후보를 고르세요.</p>
        ) : (
          <ul className="plan-item-list">
            {chosen.map((candidate: TestCandidate) => (
              <li key={candidate.id}>
                <strong>{candidate.title}</strong>
                <span className="badge">{candidate.category}</span>
                <span className={candidate.risk === 'SAFE' ? 'badge ok' : 'badge warn'}>{candidate.risk}</span>
                <span className="badge">{candidate.binding.kind}</span>
              </li>
            ))}
          </ul>
        )}

        {blocked.length > 0 && (
          <p className="notice error">
            선택 중 {blocked.length}개가 더 이상 실행 가능하지 않습니다. 이전 화면에서 다시 고르세요.
          </p>
        )}

        {!generation.profileVersionActive && (
          <p className="notice error">
            이 후보 목록의 Profile 버전이 더 이상 활성이 아닙니다. 계획을 만들 수 없습니다.
          </p>
        )}

        <div className="button-row">
          <button
            type="button"
            onClick={() => void createPlan()}
            disabled={busy || chosen.length === 0 || blocked.length > 0 || !generation.profileVersionActive}
          >
            계획 만들기
          </button>
        </div>
        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      {plan && (
        <section className="card plan-detail">
          <div className="section-heading">
            <div>
              <p className="eyebrow">2. 계획</p>
              <h2>{planStatusLabel(plan.status)}</h2>
            </div>
            <div className="button-row">
              <button className="secondary-button" type="button" onClick={() => void reloadPlan()} disabled={busy}>
                상태 새로고침
              </button>
              <button className="secondary-button" type="button" onClick={() => setPlanId(null)} disabled={busy}>
                다른 계획 만들기
              </button>
            </div>
          </div>

          <dl className="meta-grid">
            <div><dt>계획 ID</dt><dd>{plan.id.slice(0, 8)}</dd></div>
            <div><dt>필요한 승인 수준</dt><dd>{planRisk ?? '-'}</dd></div>
            <div><dt>승인자</dt><dd>{plan.approvedBy ?? '미승인'}</dd></div>
            <div><dt>항목</dt><dd>{plan.items.length}개</dd></div>
          </dl>

          {isPlanTerminal(plan) && (
            <p className="notice error">
              이 계획은 {planStatusLabel(plan.status)} 상태입니다. 다시 승인하거나 실행할 수 없습니다.
              {plan.terminalReason && ` (${plan.terminalReason})`}
            </p>
          )}

          <ul className="plan-item-list">
            {plan.items.map((item) => (
              <li key={item.id}>
                <span className="sequence">{item.sequenceNumber}</span>
                <span className="badge">{item.category}</span>
                <span className={item.risk === 'SAFE' ? 'badge ok' : 'badge warn'}>{item.risk}</span>
                <span className="badge">{item.bindingKind}</span>
                <code>{item.targetTestCandidateIds.join(', ')}</code>
              </li>
            ))}
          </ul>

          {plan.status === 'PENDING_APPROVAL' && (
            <div className="button-row">
              <button type="button" onClick={() => setApproving(true)} disabled={busy}>승인하기</button>
            </div>
          )}

          {plan.status === 'APPROVED' && (
            <div className="approval-box">
              <strong>실행 엔진으로 인계</strong>
              <p>
                인계하면 승인한 항목이 기존 실행 엔진에서 실행됩니다. 인계 사이에 Profile 버전이 바뀌면 이 계획은
                자동으로 대체되어 실행되지 않습니다.
              </p>
              <button type="button" onClick={() => void dispatchPlan()} disabled={busy}>인계하기</button>
            </div>
          )}

          {plan.executionReferences.length > 0 && (
            <div className="notice success">
              실행 참조: {plan.executionReferences.map((reference) => (
                <code key={reference.referenceId}>{reference.kind} {reference.referenceId.slice(0, 8)}</code>
              ))}
            </div>
          )}
        </section>
      )}

      {approving && plan && (
        <ConfirmationDialog
          title="이 계획을 승인합니다"
          confirmLabel="승인"
          busy={busy}
          onCancel={() => { setApproving(false); setConfirmation('') }}
          onConfirm={() => void approvePlan()}
        >
          <p>
            항목 {plan.items.length}개, 가장 높은 위험도는 <strong>{planRisk}</strong>입니다. 승인해도 곧바로
            실행되지 않으며, 인계는 별도 단계입니다.
          </p>
          <label>
            확인 문구 <code>{plan.requiredConfirmation}</code>를 그대로 입력하세요
            <input
              type="text"
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
              aria-label="확인 문구"
            />
          </label>
        </ConfirmationDialog>
      )}
    </div>
  )
}
