import { useEffect, useMemo, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  approveTestSpecification,
  findSpecification,
  findSpecificationsByTarget,
  isRisky,
  isSpecApprovable,
  riskLabel,
  specificationStatusLabel,
  type TestSpecificationDocument,
  type TestSpecificationResponse,
} from '../../api/testSpecifications'

interface TestSpecApprovalWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
  selectedSpecificationId: string | null
  onSelectSpecification: (specificationId: string) => void
}

/**
 * Approval is a review of the criterion, not permission to send a request. The source document stays visible so the
 * operator can see the exact condition, unfounded threshold and exception that would become a regression asset.
 */
export function TestSpecApprovalWorkspace({
  api,
  targetSystemId,
  selectedSpecificationId,
  onSelectSpecification,
}: TestSpecApprovalWorkspaceProps) {
  const [specifications, setSpecifications] = useState<TestSpecificationResponse[]>([])
  const [specification, setSpecification] = useState<TestSpecificationResponse | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    setSpecifications([])
    setSpecification(null)
    setConfirmation('')
    if (!targetSystemId) return () => { current = false }

    findSpecificationsByTarget(api, targetSystemId)
      .then((loaded) => {
        if (!current) return
        setSpecifications(loaded)
        const nextId = selectedSpecificationId && loaded.some((item) => item.id === selectedSpecificationId)
          ? selectedSpecificationId
          : loaded[0]?.id
        if (nextId) onSelectSpecification(nextId)
      })
      .catch((error: unknown) => { if (current) report(error) })
    return () => { current = false }
  }, [api, targetSystemId])

  useEffect(() => {
    let current = true
    setSpecification(null)
    setConfirmation('')
    if (!selectedSpecificationId) return () => { current = false }
    findSpecification(api, selectedSpecificationId)
      .then((loaded) => { if (current) { setSpecification(loaded); setMessage(null); setFailed(false) } })
      .catch((error: unknown) => { if (current) report(error) })
    return () => { current = false }
  }, [api, selectedSpecificationId])

  const estimatedDuration = useMemo(() => (specification ? estimateDuration(specification.document) : null), [specification])

  async function approve() {
    if (!specification) return
    try {
      setBusy(true)
      const approved = await approveTestSpecification(api, specification.id, confirmation)
      setSpecification(approved)
      setSpecifications((items) => items.map((item) => item.id === approved.id ? approved : item))
      setFailed(false)
      setMessage('판정 기준을 승인했습니다. 이제 실행 화면에서 이 명세를 실행할 수 있습니다.')
    } catch (error) {
      report(error)
    } finally {
      setBusy(false)
    }
  }

  function report(error: unknown) {
    setFailed(true)
    setMessage(errorMessage(error))
  }

  if (!targetSystemId) return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>

  return (
    <div className="workspace-grid specification-approval-workspace">
      <section className="card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">명세 목록</p>
            <h2>{targetSystemId}</h2>
          </div>
          <button className="secondary-button" type="button" onClick={() => {
            findSpecificationsByTarget(api, targetSystemId).then(setSpecifications).catch(report)
          }} disabled={busy}>새로고침</button>
        </div>
        {specifications.length === 0 ? (
          <p className="muted">아직 등록한 명세가 없습니다. 등록 화면에서 판정 기준을 먼저 작성하세요.</p>
        ) : (
          <ul className="selection-list specification-list">
            {specifications.map((item) => (
              <li key={item.id} className={item.id === selectedSpecificationId ? 'selected' : undefined}>
                <button type="button" className="link-button" onClick={() => onSelectSpecification(item.id)}>
                  {item.title} <small>{item.specKey} · v{item.version}</small>
                </button>
                <span className={`status-badge status-${item.status.toLowerCase()}`}>{specificationStatusLabel(item.status)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {specification && (
        <section className="card specification-review">
          <div className="section-heading">
            <div>
              <p className="eyebrow">판정 기준 검토 · {specification.category}</p>
              <h2>{specification.title}</h2>
            </div>
            <span className={`badge ${specification.risk === 'SAFE' ? 'ok' : 'danger'}`}>{riskLabel(specification.risk)}</span>
          </div>

          {!specification.profileVersionActive && (
            <p className="notice error">
              이 명세의 Profile 버전은 더 이상 활성 상태가 아닙니다. 현재 Profile에서 다시 검증될 때까지 승인하거나 실행할 수 없습니다.
            </p>
          )}
          {isRisky(specification) && (
            <p className="notice warning">
              {riskLabel(specification.risk)} 명세입니다. 실행 동의가 아니라, 아래 조건을 시스템의 판정 기준으로 채택하는 승인입니다.
            </p>
          )}

          <dl className="meta-grid">
            <div><dt>상태</dt><dd>{specificationStatusLabel(specification.status)}</dd></div>
            <div><dt>체크섬</dt><dd><code>{specification.checksum}</code></dd></div>
            <div><dt>작성자</dt><dd>{specification.createdBy}</dd></div>
            <div><dt>승인자</dt><dd>{specification.approvedBy ?? '-'}</dd></div>
            <div><dt>예상 소요</dt><dd>{estimatedDuration}</dd></div>
          </dl>
          {specification.terminalReason && <p className="notice error">{specification.terminalReason}</p>}

          <SpecificationDocumentReview specification={specification} />

          {isSpecApprovable(specification) && (
            <div className="approval-box">
              <strong>확인 문구를 그대로 입력하세요</strong>
              <code>{specification.requiredConfirmation}</code>
              <label>
                승인 확인 문구
                <input
                  aria-label="승인 확인 문구"
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  autoComplete="off"
                />
              </label>
              <button
                type="button"
                onClick={() => void approve()}
                disabled={busy || confirmation !== specification.requiredConfirmation}
              >
                이 기준으로 승인
              </button>
            </div>
          )}
          {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
        </section>
      )}
    </div>
  )
}

export function SpecificationDocumentReview({ specification }: { specification: TestSpecificationResponse }) {
  const document = specification.document
  const unfounded = new Set(specification.unfoundedThresholds)
  const invariants = records(document.invariants)
  const observations = records(document.observations)
  const setup = records(document.setup)
  const workload = records(document.workload)
  const faultSteps = workload.filter((step) => step.kind === 'INJECT_FAULT')

  return (
    <div className="specification-document-review">
      {specification.unfoundedThresholds.length > 0 && (
        <p className="notice warning">
          <strong>근거: 없음</strong> 임계값이 있습니다. 아래 항목 옆에서 해당 값이 무엇을 재는지 확인하세요.
        </p>
      )}
      {faultSteps.length > 0 && (
        <p className="notice error">
          장애 주입 단계 {faultSteps.length}개가 포함되어 있습니다. TTL과 해제·정리 조건을 검토하세요.
        </p>
      )}

      <h3>관측과 불변식</h3>
      <ul className="specification-item-list">
        {observations.map((observation, index) => <DocumentItem key={`observation-${index}`} item={observation} unfounded={unfounded} kind="관측" />)}
        {invariants.map((invariant, index) => <DocumentItem key={`invariant-${index}`} item={invariant} unfounded={unfounded} kind="불변식" />)}
      </ul>

      <h3>실행 영향</h3>
      {setup.length + workload.length === 0 ? <p className="muted">선언된 setup 또는 workload 단계가 없습니다.</p> : (
        <ul className="specification-item-list">
          {setup.map((step, index) => <SetupImpact key={`setup-${index}`} step={step} />)}
          {workload.map((step, index) => <WorkloadImpact key={`workload-${index}`} step={step} />)}
        </ul>
      )}

      <details>
        <summary>원본 명세 JSON 보기</summary>
        <pre className="document-json">{JSON.stringify(document, null, 2)}</pre>
      </details>
    </div>
  )
}

function DocumentItem({ item, unfounded, kind }: { item: Record<string, unknown>; unfounded: Set<string>; kind: string }) {
  const id = stringValue(item.id)
  const description = stringValue(item.description) ?? stringValue(item.title) ?? id ?? kind
  const condition = stringValue(item.condition)
  const exceptions = kind === '불변식' ? records(item.exceptions) : []
  return (
    <li>
      <div className="section-heading">
        <strong>{description}</strong>
        {id && unfounded.has(id) && <span className="badge warn">근거: 없음</span>}
      </div>
      {id && <code>{id}</code>}
      {condition && <p><code>{condition}</code></p>}
      {exceptions.length > 0 && (
        <div className="invariant-exceptions">
          <strong>이 불변식에 승인된 예외</strong>
          <ul>
            {exceptions.map((exception, index) => (
              <li key={`${id ?? 'invariant'}-exception-${index}`}>
                <p><code>{stringValue(exception.condition) ?? '조건 미제공'}</code></p>
                <p>{stringValue(exception.description) ?? '설명 미제공'}</p>
                <small>승인자: {stringValue(exception.approvedBy) ?? '미제공'} · 승인 시각: 명세 API 미제공</small>
              </li>
            ))}
          </ul>
        </div>
      )}
      <code>{JSON.stringify(item)}</code>
    </li>
  )
}

function WorkloadImpact({ step }: { step: Record<string, unknown> }) {
  const kind = stringValue(step.kind) ?? '알 수 없는 단계'
  const name = stringValue(step.name) ?? '이름 미제공'
  const call = record(step.call)
  const method = stringValue(call?.method)
  const path = stringValue(call?.path)
  const requestCount = numberValue(step.requestCount) ?? 1
  const concurrency = numberValue(step.concurrency) ?? 1

  return (
    <li>
      <strong>{kind} · {name}</strong>
      {kind === 'CALL' && (
        <p>HTTP 호출: <code>{method ?? 'METHOD 미제공'} {path ?? 'PATH 미제공'}</code> · {requestCount}회 · 동시성 {concurrency}</p>
      )}
      {kind === 'WAIT' && <p>명시 대기: {formatMilliseconds(numberValue(step.duration))}</p>}
      {kind === 'INJECT_FAULT' && (
        <p>장애 주입: <code>{stringValue(step.faultType) ?? '유형 미제공'}</code> · 범위 {stringValue(step.scope) ?? '미제공'} · TTL {formatMilliseconds(numberValue(step.ttl))}</p>
      )}
      {kind === 'RELEASE_FAULT' && <p>장애 해제 handle: <code>{stringValue(step.handle) ?? '미제공'}</code></p>}
      {kind === 'INFRA_ACTION' && (
        <p>인프라 제어: <code>{stringValue(step.action) ?? '동작 미제공'} · {stringValue(step.target) ?? '대상 미제공'}</code> · 최대 유지 {formatMilliseconds(numberValue(step.maxHold))}</p>
      )}
      {kind === 'INFRA_RESTORE' && <p>인프라 복구 handle: <code>{stringValue(step.handle) ?? '미제공'}</code></p>}
    </li>
  )
}

function SetupImpact({ step }: { step: Record<string, unknown> }) {
  const call = record(step.call)
  return (
    <li>
      <strong>SETUP · {stringValue(step.name) ?? '이름 미제공'}</strong>
      <p>
        HTTP 준비 호출: <code>{stringValue(call?.method) ?? 'METHOD 미제공'} {stringValue(call?.path) ?? 'PATH 미제공'}</code> · 시행마다 1회
      </p>
    </li>
  )
}

function estimateDuration(document: TestSpecificationDocument): string {
  const policy = record(document.policy)
  const trials = numberValue(policy?.trials) ?? 1
  const waitPerTrial = records(document.workload)
    .filter((step) => step.kind === 'WAIT')
    .reduce((total, step) => total + (numberValue(step.duration) ?? 0), 0)
  const observationMaxWait = records(document.observations)
    .reduce((total, observation) => total + (numberValue(record(observation.readAt)?.maxWait) ?? 0), 0)
  const interval = numberValue(policy?.interval) ?? 0
  const knownDuration = trials * (waitPerTrial + observationMaxWait) + Math.max(0, trials - 1) * interval
  const knownParts = [
    `${trials}회`,
    `명시 WAIT ${formatMilliseconds(trials * waitPerTrial)}`,
    `관측 최대 대기 ${formatMilliseconds(trials * observationMaxWait)}`,
    `시행 간격 ${formatMilliseconds(Math.max(0, trials - 1) * interval)}`,
  ]
  return `${knownParts.join(' · ')} · 알려진 최대 ${formatMilliseconds(knownDuration)}; HTTP 호출 및 Profile 리셋 예상 시간은 API 미제공`
}

function records(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object' && !Array.isArray(item)) : []
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function numberValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function formatMilliseconds(value: number | null): string {
  if (value === null) return '미제공'
  if (value < 1_000) return `${value}ms`
  if (value % 1_000 === 0) return `${value / 1_000}초`
  return `${(value / 1_000).toFixed(1)}초`
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '명세를 불러오지 못했습니다.'
}
