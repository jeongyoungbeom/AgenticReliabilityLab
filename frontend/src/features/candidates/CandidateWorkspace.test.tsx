import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TargetKnowledgeSnapshot } from '../../api/targetKnowledge'
import type { TestCandidate, TestCandidateGeneration } from '../../api/testCandidates'
import { CandidateWorkspace } from './CandidateWorkspace'

function snapshot(overrides: Partial<TargetKnowledgeSnapshot> = {}): TargetKnowledgeSnapshot {
  return {
    id: 'snap-1', targetSystemId: 'pilot-target', profileVersionId: 'pv-1', profileVersionActive: true,
    checksum: 'aaaaaaaaaaaa1111', extractionVersion: 'v1', confirmed: true, confirmedBy: 'EXECUTOR',
    confirmedAt: '2026-01-01T00:00:00Z', createdBy: 'EXECUTOR', createdAt: '2026-01-01T00:00:00Z',
    sources: [], operations: [], workflows: [], domainHypotheses: [], invariants: [], riskSignals: [], warnings: [],
    ...overrides,
  }
}

function candidate(overrides: Partial<TestCandidate> = {}): TestCandidate {
  return {
    id: 'cand-1', sequenceNumber: 1, category: 'AVAILABILITY', title: '상품 목록 조회 가능 여부',
    description: '', risk: 'SAFE', confidence: 'INFERRED', readiness: 'EXECUTABLE',
    verifiedExpectation: '200을 반환한다', preconditions: [],
    binding: {
      kind: 'READ_ONLY_BATCH', targetTestCandidateIds: ['catalog-list'], experimentType: null,
      requiredCapability: null, unresolvedReason: null, unresolvedDetail: null,
    },
    citations: [], requiredEvidence: [], dataLifecyclePlan: null,
    ...overrides,
  }
}

function generation(candidates: TestCandidate[]): TestCandidateGeneration {
  return {
    id: 'gen-1', targetSystemId: 'pilot-target', knowledgeSnapshotId: 'snap-1', profileVersionId: 'pv-1',
    profileVersionActive: true, source: 'SNAPSHOT_RULES', generatorVersion: 'candidate-rules-v1',
    checksum: 'bbbbbbbbbbbb2222', createdBy: 'EXECUTOR', createdAt: '2026-01-01T00:00:00Z', candidates,
  }
}

function apiStub(options: { snapshots?: TargetKnowledgeSnapshot[]; post?: TestCandidateGeneration } = {}) {
  const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
  vi.spyOn(api, 'get').mockImplementation(async (path: string) =>
    (path.includes('knowledge-snapshots') ? options.snapshots ?? [snapshot()] : []) as never,
  )
  vi.spyOn(api, 'post').mockImplementation(async () => (options.post ?? generation([candidate()])) as never)
  return api
}

function renderWorkspace(api: ApiClient, onSelectionChange = vi.fn()) {
  render(
    <CandidateWorkspace
      api={api}
      targetSystemId="pilot-target"
      selectedCandidateIds={[]}
      onSelectionChange={onSelectionChange}
      onGenerationLoaded={vi.fn()}
    />,
  )
  return onSelectionChange
}

describe('CandidateWorkspace', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('Target이 없으면 후보 생성을 막는다', () => {
    render(
      <CandidateWorkspace
        api={apiStub()} targetSystemId={null} selectedCandidateIds={[]}
        onSelectionChange={vi.fn()} onGenerationLoaded={vi.fn()}
      />,
    )
    expect(screen.getByText(/먼저 Target Profile 화면에서/)).toBeInTheDocument()
  })

  it('Profile 버전이 대체된 이해 모델로는 후보를 만들 수 없다', async () => {
    renderWorkspace(apiStub({ snapshots: [snapshot({ profileVersionActive: false })] }))

    await waitFor(() => expect(screen.getByRole('button', { name: '후보 생성' })).toBeDisabled())
    expect(screen.getByRole('option', { name: /대체됨/ })).toBeDisabled()
  })

  it('확인하지 않은 이해 모델은 경고하지만 막지는 않는다', async () => {
    renderWorkspace(apiStub({ snapshots: [snapshot({ confirmed: false })] }))

    expect(await screen.findByText(/아직 검토 확인을 하지 않은 이해 모델/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '후보 생성' })).toBeEnabled()
  })

  it('실행 불가 후보는 숨기지 않고 이유를 밝힌다', async () => {
    const blocked = candidate({
      id: 'cand-2', title: '재고 동시성', category: 'CONCURRENCY', risk: 'MODERATE',
      readiness: 'CAPABILITY_UNAVAILABLE',
      binding: {
        kind: 'UNBOUND', targetTestCandidateIds: [], experimentType: 'STOCK_CONCURRENCY',
        requiredCapability: 'TEST_HARNESS_V1', unresolvedReason: 'MISSING_TEST_DATA_LIFECYCLE',
        unresolvedDetail: null,
      },
    })
    renderWorkspace(apiStub({ post: generation([blocked]) }))

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    expect(await screen.findByText('재고 동시성')).toBeInTheDocument()
    expect(screen.getByText(/테스트 데이터를 만들고 정리할 방법이 없습니다/)).toBeInTheDocument()
    expect(screen.getByText('TEST_HARNESS_V1')).toBeInTheDocument()
  })

  it('실행 불가 후보는 선택할 수 없다', async () => {
    const blocked = candidate({ id: 'cand-3', title: '차단된 후보', readiness: 'NEEDS_USER_INPUT' })
    const onSelectionChange = renderWorkspace(apiStub({ post: generation([blocked, candidate()]) }))

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    expect(await screen.findByRole('checkbox', { name: '차단된 후보 선택' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '상품 목록 조회 가능 여부 선택' })).toBeEnabled()

    await userEvent.click(screen.getByRole('checkbox', { name: '상품 목록 조회 가능 여부 선택' }))
    expect(onSelectionChange).toHaveBeenCalledWith(['cand-1'])
  })

  it('실행 가능한 후보 수를 세어 보여준다', async () => {
    renderWorkspace(apiStub({
      post: generation([candidate(), candidate({ id: 'c2', title: '두번째', readiness: 'UNSUPPORTED' })]),
    }))

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    expect(await screen.findByText('2개 중 1개 실행 가능')).toBeInTheDocument()
  })

  it('직접 요청은 등록된 분류만 보내고 곧바로 실행하지 않는다', async () => {
    const api = apiStub()
    renderWorkspace(api)

    await userEvent.click(await screen.findByRole('button', { name: '직접 요청' }))
    await userEvent.type(screen.getByLabelText('제목'), '재고 동시성 확인')
    await userEvent.click(screen.getByRole('button', { name: '후보로 기록' }))

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    const [path, body, role] = vi.mocked(api.post).mock.calls[0]
    expect(path).toBe('/api/test-candidate-requests')
    expect(role).toBe('profileEditor')
    expect(body).toMatchObject({
      knowledgeSnapshotId: 'snap-1', category: 'CONCURRENCY', title: '재고 동시성 확인',
    })
  })

  it('생성 결과의 Profile 버전이 대체되면 계획에 못 쓴다고 알린다', async () => {
    const stale = { ...generation([candidate()]), profileVersionActive: false }
    renderWorkspace(apiStub({ post: stale }))

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    expect(await screen.findByText(/계획에 사용할 수 없습니다/)).toBeInTheDocument()
  })

  it('분류와 위험도, 실행 단위를 후보마다 표시한다', async () => {
    renderWorkspace(apiStub())

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    const card = (await screen.findByText('상품 목록 조회 가능 여부')).closest('li') as HTMLElement
    expect(within(card).getByText('AVAILABILITY')).toBeInTheDocument()
    expect(within(card).getByText('SAFE')).toBeInTheDocument()
    expect(within(card).getByText('READ_ONLY_BATCH')).toBeInTheDocument()
  })

  it('직접 요청이 기존 후보 목록과 선택을 지우지 않는다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockImplementation(async (path: string) =>
      (path.includes('knowledge-snapshots') ? [snapshot()] : []) as never,
    )
    const requested = generation([candidate({ id: 'direct-1', title: '직접 요청한 테스트' })])
    vi.spyOn(api, 'post').mockImplementation(async (path: string) =>
      (path.includes('test-candidate-requests')
        ? requested
        : generation([candidate(), candidate({ id: 'cand-9', title: '두번째 후보' })])) as never,
    )

    const onSelectionChange = vi.fn()
    render(
      <CandidateWorkspace
        api={api} targetSystemId="pilot-target" selectedCandidateIds={['cand-1']}
        onSelectionChange={onSelectionChange} onGenerationLoaded={vi.fn()}
      />,
    )

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))
    await screen.findByText('두번째 후보')

    await userEvent.click(screen.getByRole('button', { name: '직접 요청' }))
    await userEvent.type(screen.getByLabelText('제목'), '재고 동시성')
    await userEvent.click(screen.getByRole('button', { name: '후보로 기록' }))

    expect(await screen.findByText('직접 요청한 테스트')).toBeInTheDocument()
    expect(screen.getByText('두번째 후보')).toBeInTheDocument()
  })

  it('선택한 후보가 더 이상 실행 가능하지 않으면 선택에서 뺀다', async () => {
    const stale = candidate({ id: 'cand-1', readiness: 'CAPABILITY_UNAVAILABLE' })
    const onSelectionChange = vi.fn()
    render(
      <CandidateWorkspace
        api={apiStub({ post: generation([stale]) })} targetSystemId="pilot-target"
        selectedCandidateIds={['cand-1']}
        onSelectionChange={onSelectionChange} onGenerationLoaded={vi.fn()}
      />,
    )

    await userEvent.click(await screen.findByRole('button', { name: '후보 생성' }))

    await waitFor(() => expect(onSelectionChange).toHaveBeenCalledWith([]))
  })
})
