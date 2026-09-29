import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '../../api/ApiClient'
import type { AiProposalRun, KnowledgeSnapshot } from '../../api/aiProposals'
import { AiProposalPanel } from './AiProposalPanel'

const snapshots: KnowledgeSnapshot[] = ['one', 'two'].map((id) => ({
  id, profileVersionId: 'profile-1', profileVersionActive: true, confirmed: true,
  sources: [{ type: 'OPENAPI', checksum: 'abcdef1234567890' }],
  operations: [{ method: 'GET', path: '/health', summary: 'Health' }],
  workflows: [], invariants: [], warnings: [],
}))

const completed: AiProposalRun = {
  id: 'generation-1', knowledgeSnapshotIds: ['one', 'two'], status: 'COMPLETED',
  failureCode: null, failureMessage: null,
  candidates: [{
    ordinal: 1, outcome: 'ACCEPTED', specKey: 'ai-health', title: '상태 응답 판정',
    document: {
      risk: 'SAFE', setup: [], workload: [{ kind: 'CALL', call: { method: 'GET', path: '/health' } }],
      evidence: [{ excerpt: 'Health' }], invariants: [{ condition: 'status == 200' }],
    },
    rejectionReason: null, specificationId: 'spec-1',
  }],
}

function apiStub(runs: AiProposalRun[] = []) {
  return {
    get: vi.fn((path: string) => {
      if (path.endsWith('/knowledge-snapshots')) return Promise.resolve(snapshots)
      if (path.endsWith('/test-specification-generations')) return Promise.resolve(runs)
      if (path.includes('/test-specifications/spec-1')) {
        return Promise.resolve({
          id: 'spec-1', status: 'PENDING_APPROVAL', requiredConfirmation: 'APPROVE_SAFE_TEST_SPECIFICATION',
          profileVersionActive: true, risk: 'SAFE', document: completed.candidates[0].document,
          unfoundedThresholds: [],
        })
      }
      return Promise.resolve(completed)
    }),
    post: vi.fn((path: string) => {
      if (path.endsWith('/test-specification-generations')) return Promise.resolve(completed)
      if (path.endsWith('/approve')) return Promise.resolve({ id: 'spec-1', status: 'APPROVED' })
      return Promise.resolve({ id: 'run-1' })
    }),
  } as unknown as ApiClient
}

describe('AiProposalPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('uses multiple confirmed snapshots and requires separate approval and execution', async () => {
    const api = apiStub()
    const openRun = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(
      <AiProposalPanel api={api} targetSystemId="target-1" refreshKey={0}
        credentialPreflight={[]} onOpenRun={openRun} />,
    )

    expect(await screen.findByText(/Snapshot one/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'AI 추가 후보 생성' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(
      '/api/targets/target-1/test-specification-generations',
      { knowledgeSnapshotIds: ['one', 'two'] },
      'profileEditor',
      expect.stringMatching(/^ai-proposals-/),
    ))
    expect(await screen.findByText('상태 응답 판정')).toBeInTheDocument()
    expect(openRun).not.toHaveBeenCalled()

    await userEvent.click(screen.getByText('근거 · 순서 · 입력 · 위험 · 기대 결과 검토'))
    expect(screen.getByText('기대 결과: status == 200')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '명세 승인' }))
    expect(await screen.findByRole('button', { name: '승인한 명세 실행' })).toBeInTheDocument()
    expect(openRun).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '승인한 명세 실행' }))
    await waitFor(() => expect(openRun).toHaveBeenCalledWith('run-1'))
    expect(api.post).toHaveBeenCalledWith(
      '/api/test-specifications/spec-1/runs', {}, 'executor', expect.stringMatching(/^ai-spec-/),
    )
  })

  it('shows stored rejection and leaves additional candidates empty when the model fails', async () => {
    const failed: AiProposalRun = {
      ...completed, id: 'failed', status: 'FAILED', failureCode: 'MODEL_UNAVAILABLE',
      failureMessage: 'Ollama unavailable', candidates: [],
    }
    render(
      <AiProposalPanel api={apiStub([failed])} targetSystemId="target-1" refreshKey={0}
        credentialPreflight={[]} onOpenRun={vi.fn()} />,
    )
    expect(await screen.findByText('추가로 검증 가능한 후보 없음')).toBeInTheDocument()
    expect(screen.getByText(/MODEL_UNAVAILABLE/)).toBeInTheDocument()
  })

  it('requires review confirmation before an unconfirmed Snapshot can be selected', async () => {
    const pending = { ...snapshots[0], confirmed: false }
    const api = {
      get: vi.fn((path: string) => Promise.resolve(path.endsWith('/knowledge-snapshots') ? [pending] : [])),
      post: vi.fn().mockResolvedValue({ ...pending, confirmed: true }),
    } as unknown as ApiClient
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(
      <AiProposalPanel api={api} targetSystemId="target-1" refreshKey={0}
        credentialPreflight={[]} onOpenRun={vi.fn()} />,
    )
    const generate = await screen.findByRole('button', { name: 'AI 추가 후보 생성' })
    expect(generate).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '검토 후 Snapshot 확인' }))
    await waitFor(() => expect(generate).toBeEnabled())
    expect(api.post).toHaveBeenCalledWith(
      '/api/target-knowledge-snapshots/one/confirmation',
      { confirmation: 'CONFIRM_TARGET_KNOWLEDGE' },
      'profileEditor',
    )
  })

  it('shows the reason for a stored rejected proposal', async () => {
    const rejected: AiProposalRun = {
      ...completed, candidates: [{
        ...completed.candidates[0], outcome: 'REJECTED', specificationId: null,
        rejectionReason: 'Duplicates a basic or previous candidate',
      }],
    }
    render(
      <AiProposalPanel api={apiStub([rejected])} targetSystemId="target-1" refreshKey={0}
        credentialPreflight={[]} onOpenRun={vi.fn()} />,
    )
    expect(await screen.findByText('Duplicates a basic or previous candidate')).toBeInTheDocument()
    expect(screen.getByText('추가로 검증 가능한 후보 없음')).toBeInTheDocument()
  })
})
