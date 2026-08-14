import { useCallback, useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetTestBatch, TargetTestCandidate } from '../../api/targetTests'
import { ConfirmationDialog } from '../../components/ConfirmationDialog'
import { useIdempotencyKey } from '../../hooks/useIdempotencyKey'
import { useSessionStorageState } from '../../hooks/useSessionStorageState'
import { BatchResultPanel } from './BatchResultPanel'
import { CandidateSelector } from './CandidateSelector'

interface TargetTestWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
  onSelectBatch: (batchId: string | null) => void
}

export function TargetTestWorkspace({ api, targetSystemId, onSelectBatch }: TargetTestWorkspaceProps) {
  const [candidates, setCandidates] = useState<TargetTestCandidate[]>([])
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [batchId, setBatchId] = useSessionStorageState<string | null>('arl.selected-target-test-batch', null)
  const [batch, setBatch] = useState<TargetTestBatch | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [approvalOpen, setApprovalOpen] = useState(false)
  const { key: idempotencyKey, renew } = useIdempotencyKey('target-test-batch')

  const loadBatch = useCallback(async () => {
    if (!batchId) return
    try {
      const found = await api.get<TargetTestBatch>(`/api/test-batches/${batchId}`)
      setBatch(found)
      onSelectBatch(found.id)
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }, [api, batchId, onSelectBatch])

  useEffect(() => {
    if (!targetSystemId) {
      setCandidates([])
      return
    }
    void loadCandidates(targetSystemId)
  }, [api, targetSystemId])

  useEffect(() => {
    void loadBatch()
  }, [loadBatch])

  useEffect(() => {
    if (!batch || !['PENDING_APPROVAL', 'APPROVED', 'RUNNING'].includes(batch.status)) return
    const timer = window.setTimeout(() => void loadBatch(), 1_000)
    return () => window.clearTimeout(timer)
  }, [batch, loadBatch])

  async function loadCandidates(targetId: string) {
    try {
      setMessage(null)
      setCandidates(await api.get<TargetTestCandidate[]>(`/api/targets/${targetId}/test-candidates`))
      setSelectedIds(new Set())
      setBatch(null)
      setBatchId(null)
      onSelectBatch(null)
      renew()
    } catch (error) {
      setCandidates([])
      setMessage(errorMessage(error))
    }
  }

  function selectCandidates(next: Set<string>) {
    setSelectedIds(next)
    renew()
  }

  async function createBatch() {
    if (!targetSystemId || selectedIds.size === 0) return
    await run(async () => {
      const created = await api.post<TargetTestBatch>(
        '/api/test-batches',
        { targetSystemId, candidateIds: [...selectedIds] },
        'executor',
        idempotencyKey,
      )
      setBatch(created)
      setBatchId(created.id)
      onSelectBatch(created.id)
      setMessage('Batch가 생성되었습니다. 아래 승인 단계에서 실행 범위를 다시 확인하세요.')
    })
  }

  async function approveBatch() {
    if (!batch) return
    await run(async () => {
      const approved = await api.post<TargetTestBatch>(
        `/api/test-batches/${batch.id}/approve`,
        { confirmation: 'EXECUTE_SAFE_HTTP_BATCH' },
        'executor',
      )
      setBatch(approved)
      setApprovalOpen(false)
      setMessage('승인되었습니다. 등록된 Target에 안전한 GET 요청만 전송하며 상태를 자동으로 갱신합니다.')
    })
  }

  function startNewBatch() {
    setBatch(null)
    setBatchId(null)
    onSelectBatch(null)
    setSelectedIds(new Set())
    setMessage(null)
    renew()
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

  if (!targetSystemId) {
    return <section className="card future-workspace"><h2>먼저 활성 Target을 선택하세요.</h2><p>Target Profile 화면에서 활성화된 Target을 선택하면 등록된 GET 후보만 표시합니다.</p></section>
  }

  return (
    <div className="workspace-grid batch-workspace">
      <section className="card candidate-panel">
        <p className="eyebrow">1. 안전한 GET 후보 선택</p>
        <h2>{targetSystemId}</h2>
        <p className="muted">여러 후보를 한 Batch로 묶을 수 있습니다. 임의 URL, Header, Body 또는 쓰기 HTTP method는 입력할 수 없습니다.</p>
        <CandidateSelector candidates={candidates} selectedIds={selectedIds} onChange={selectCandidates} />
        <div className="button-row">
          <button type="button" onClick={() => void createBatch()} disabled={busy || selectedIds.size === 0 || batch !== null}>
            {selectedIds.size}개 후보로 Batch 만들기
          </button>
          <button type="button" className="secondary-button" onClick={() => void loadCandidates(targetSystemId)} disabled={busy}>후보 새로고침</button>
        </div>
        {message && <p className={message.includes(':') && !message.startsWith('Batch') && !message.startsWith('승인') ? 'notice error' : 'notice success'}>{message}</p>}
      </section>
      {batch ? (
        <BatchResultPanel
          batch={batch}
          onRefresh={() => void loadBatch()}
          onRequestApproval={() => setApprovalOpen(true)}
          onNewBatch={startNewBatch}
        />
      ) : (
        <section className="card result-placeholder"><p className="eyebrow">2. 명시적 승인과 결과</p><h2>아직 생성된 Batch가 없습니다.</h2><p>Batch는 PENDING_APPROVAL 상태로만 만들어집니다. 승인하기 전에는 Target에 요청을 보내지 않습니다.</p></section>
      )}
      {approvalOpen && batch && (
        <ConfirmationDialog
          title="안전한 HTTP Batch를 승인할까요?"
          confirmLabel="GET 테스트 승인"
          busy={busy}
          onCancel={() => setApprovalOpen(false)}
          onConfirm={() => void approveBatch()}
        >
          <p><strong>{batch.targetSystemId}</strong>에 다음 <strong>{batch.items.length}개</strong>의 등록된 GET 요청만 보냅니다.</p>
          <ul>{batch.items.map((item) => <li key={item.id}><code>{item.method} {item.path}</code> · 예상 HTTP {item.expectedStatusCodes.join(', ')}</li>)}</ul>
          <p className="muted">승인 기록에는 서버 actor, 시각, correlation ID와 Profile Version이 보존됩니다.</p>
        </ConfirmationDialog>
      )}
    </div>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
