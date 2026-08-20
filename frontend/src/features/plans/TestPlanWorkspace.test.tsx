import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TestCandidate, TestCandidateGeneration } from '../../api/testCandidates'
import type { TestPlan } from '../../api/testPlans'
import { TestPlanWorkspace } from './TestPlanWorkspace'

function candidate(overrides: Partial<TestCandidate> = {}): TestCandidate {
  return {
    id: 'cand-1', sequenceNumber: 1, category: 'AVAILABILITY', title: '상품 목록 조회',
    description: '', risk: 'SAFE', confidence: 'INFERRED', readiness: 'EXECUTABLE',
    verifiedExpectation: '200을 반환한다', preconditions: [],
    binding: {
      kind: 'READ_ONLY_BATCH', targetTestCandidateIds: ['catalog-list'], experimentType: null,
      requiredCapability: null, unresolvedReason: null, unresolvedDetail: null,
    },
    citations: [], requiredEvidence: [], dataLifecyclePlan: null, ...overrides,
  }
}

function generation(candidates: TestCandidate[], active = true): TestCandidateGeneration {
  return {
    id: 'gen-1', targetSystemId: 'pilot-target', knowledgeSnapshotId: 'snap-1', profileVersionId: 'pv-1',
    profileVersionActive: active, source: 'SNAPSHOT_RULES', generatorVersion: 'v1', checksum: 'cccc1111',
    createdBy: 'EXECUTOR', createdAt: '2026-01-01T00:00:00Z', candidates,
  }
}

function plan(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: '33333333-3333-3333-3333-333333333333', targetSystemId: 'pilot-target', knowledgeSnapshotId: 'snap-1',
    generationId: 'gen-1', profileVersionId: 'pv-1', profileVersionActive: true, status: 'PENDING_APPROVAL',
    requiredConfirmation: 'EXECUTE_SAFE_TEST_PLAN', createdBy: 'EXECUTOR', createdAt: '2026-01-01T00:00:00Z',
    approvedBy: null, approvedAt: null, dispatchedAt: null, terminalReason: null,
    items: [{
      id: 'item-1', sequenceNumber: 1, candidateId: 'cand-1', category: 'AVAILABILITY', risk: 'SAFE',
      bindingKind: 'READ_ONLY_BATCH', targetTestCandidateIds: ['catalog-list'],
    }],
    executionReferences: [], ...overrides,
  }
}

function renderWorkspace(api: ApiClient, options: {
  generation?: TestCandidateGeneration | null
  selected?: string[]
  onDispatched?: (batchId: string) => void
} = {}) {
  const onDispatched = options.onDispatched ?? vi.fn()
  render(
    <TestPlanWorkspace
      api={api}
      generation={options.generation === undefined ? generation([candidate()]) : options.generation}
      selectedCandidateIds={options.selected ?? ['cand-1']}
      onDispatched={onDispatched}
    />,
  )
  return onDispatched
}

function apiStub(posts: TestPlan[] = [plan()]) {
  const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
  let index = 0
  vi.spyOn(api, 'post').mockImplementation(async () => (posts[Math.min(index++, posts.length - 1)]) as never)
  vi.spyOn(api, 'get').mockImplementation(async () => plan() as never)
  return api
}

describe('TestPlanWorkspace', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('후보 목록이 없으면 앞 단계로 돌려보낸다', () => {
    renderWorkspace(apiStub(), { generation: null })
    expect(screen.getByText(/먼저 테스트 후보 화면에서/)).toBeInTheDocument()
  })

  it('선택이 없으면 계획을 만들 수 없다', () => {
    renderWorkspace(apiStub(), { selected: [] })
    expect(screen.getByRole('button', { name: '계획 만들기' })).toBeDisabled()
  })

  it('실행 불가 후보가 섞이면 계획 생성을 막는다', () => {
    renderWorkspace(apiStub(), {
      generation: generation([candidate({ readiness: 'CAPABILITY_UNAVAILABLE' })]),
    })
    expect(screen.getByText(/1개가 더 이상 실행 가능하지 않습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '계획 만들기' })).toBeDisabled()
  })

  it('Profile 버전이 대체된 후보 목록으로는 계획을 만들 수 없다', () => {
    renderWorkspace(apiStub(), { generation: generation([candidate()], false) })
    expect(screen.getByRole('button', { name: '계획 만들기' })).toBeDisabled()
  })

  it('계획 생성은 EXECUTOR 역할과 멱등키를 함께 보낸다', async () => {
    const api = apiStub()
    renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    const [path, body, role, key] = vi.mocked(api.post).mock.calls[0]
    expect(path).toBe('/api/test-plans')
    expect(role).toBe('executor')
    expect(key).toBeTruthy()
    expect(body).toEqual({ generationId: 'gen-1', candidateIds: ['cand-1'] })
  })

  it('가장 높은 위험도를 승인 수준으로 보여준다', async () => {
    const risky = plan({
      items: [
        { id: 'i1', sequenceNumber: 1, candidateId: 'c1', category: 'AVAILABILITY', risk: 'SAFE', bindingKind: 'READ_ONLY_BATCH', targetTestCandidateIds: [] },
        { id: 'i2', sequenceNumber: 2, candidateId: 'c2', category: 'CONCURRENCY', risk: 'MODERATE', bindingKind: 'EXPERIMENT', targetTestCandidateIds: [] },
      ],
    })
    renderWorkspace(apiStub([risky]))

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))

    const label = await screen.findByText('필요한 승인 수준')
    expect(label.parentElement).toHaveTextContent('MODERATE')
  })

  it('확인 문구가 다르면 승인 요청을 보내지 않는다', async () => {
    const api = apiStub()
    renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '승인하기' }))
    await userEvent.type(screen.getByLabelText('확인 문구'), 'YES')
    await userEvent.click(screen.getByRole('button', { name: '승인' }))

    expect(await screen.findByText(/확인 문구가 다릅니다/)).toBeInTheDocument()
    expect(vi.mocked(api.post).mock.calls).toHaveLength(1)
  })

  it('승인만으로는 실행되지 않고 인계가 별도 단계다', async () => {
    const api = apiStub([plan(), plan({ status: 'APPROVED', approvedBy: 'EXECUTOR' })])
    renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '승인하기' }))
    await userEvent.type(screen.getByLabelText('확인 문구'), 'EXECUTE_SAFE_TEST_PLAN')
    await userEvent.click(screen.getByRole('button', { name: '승인' }))

    expect(await screen.findByRole('button', { name: '인계하기' })).toBeInTheDocument()
    expect(screen.getByText(/인계하기 전까지는 실행되지 않습니다/)).toBeInTheDocument()
  })

  it('인계 중 대체되면 실행되지 않았다고 알린다', async () => {
    const api = apiStub([
      plan(), plan({ status: 'APPROVED' }),
      plan({ status: 'SUPERSEDED', terminalReason: 'PROFILE_VERSION_CHANGED' }),
    ])
    const onDispatched = renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '승인하기' }))
    await userEvent.type(screen.getByLabelText('확인 문구'), 'EXECUTE_SAFE_TEST_PLAN')
    await userEvent.click(screen.getByRole('button', { name: '승인' }))
    await userEvent.click(await screen.findByRole('button', { name: '인계하기' }))

    expect(await screen.findByText(/실행되지 않았습니다/)).toBeInTheDocument()
    expect(onDispatched).not.toHaveBeenCalled()
  })

  it('인계에 성공하면 만들어진 Batch로 이어준다', async () => {
    const api = apiStub([
      plan(), plan({ status: 'APPROVED' }),
      plan({
        status: 'DISPATCHED',
        executionReferences: [{ kind: 'TARGET_TEST_BATCH', referenceId: '44444444-4444-4444-4444-444444444444' }],
      }),
    ])
    const onDispatched = renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '승인하기' }))
    await userEvent.type(screen.getByLabelText('확인 문구'), 'EXECUTE_SAFE_TEST_PLAN')
    await userEvent.click(screen.getByRole('button', { name: '승인' }))
    await userEvent.click(await screen.findByRole('button', { name: '인계하기' }))

    await waitFor(() =>
      expect(onDispatched).toHaveBeenCalledWith('44444444-4444-4444-4444-444444444444'),
    )
  })

  it('계획 ID를 세션에 남겨 화면을 벗어나도 되찾는다', async () => {
    const api = apiStub()
    renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await screen.findByRole('button', { name: '승인하기' })

    expect(sessionStorage.getItem('arl.test-plan-id')).toContain('33333333')
  })

  it('저장된 계획 ID가 있으면 다시 불러온다', async () => {
    sessionStorage.setItem('arl.test-plan-id', JSON.stringify('33333333-3333-3333-3333-333333333333'))
    const api = apiStub()
    renderWorkspace(api)

    expect(await screen.findByRole('button', { name: '승인하기' })).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/test-plans/33333333-3333-3333-3333-333333333333')
  })

  it('DISPATCHED가 아닌 상태로 돌아오면 성공으로 보고하지 않는다', async () => {
    const api = apiStub([
      plan(), plan({ status: 'APPROVED' }),
      plan({ status: 'CANCELLED', terminalReason: 'OPERATOR_CANCELLED' }),
    ])
    const onDispatched = renderWorkspace(api)

    await userEvent.click(screen.getByRole('button', { name: '계획 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '승인하기' }))
    await userEvent.type(screen.getByLabelText('확인 문구'), 'EXECUTE_SAFE_TEST_PLAN')
    await userEvent.click(screen.getByRole('button', { name: '승인' }))
    await userEvent.click(await screen.findByRole('button', { name: '인계하기' }))

    expect(await screen.findByText(/실행되지 않았습니다/)).toBeInTheDocument()
    expect(onDispatched).not.toHaveBeenCalled()
  })
})
