import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { ExperimentRun } from '../../api/experiments'
import { ExperimentResultWorkspace } from './ExperimentResultWorkspace'

function invariantJson(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    invariantVersion: 'stock-concurrency-invariants-v1',
    outcome: 'FAILED', workloadCompleted: true, targetReportedStatus: 'COMPLETED',
    expectedSuccessCount: 10, expectedFinalStock: 0,
    verdicts: [
      { id: 'oversell-count-is-zero', title: '오버셀이 없었다', outcome: 'FAILED', expected: '0', observed: '3', detail: '3건 초과 판매' },
      { id: 'redis-stock-matches-expected', title: 'Redis 재고 일치', outcome: 'NOT_EVALUATED', expected: '0', observed: '보고 없음', detail: 'Target이 최종 재고를 보고하지 않았습니다' },
    ],
    ...overrides,
  })
}

function run(overrides: Partial<ExperimentRun> = {}): ExperimentRun {
  return {
    id: 'run-1', targetSystem: 'pilot-target', type: 'STOCK_CONCURRENCY',
    definitionVersion: 'stock-concurrency-v1', runStatus: 'COMPLETED', systemOutcome: 'FAILED',
    invariantResult: invariantJson(), outcomeReason: '2 STOCK_CONCURRENCY invariant(s) failed',
    cleanupStatus: 'VERIFIED', cleanupFailureCode: null,
    queuedAt: '2026-01-01T00:00:00Z', startedAt: '2026-01-01T00:00:01Z', completedAt: '2026-01-01T00:00:05Z',
    ...overrides,
  }
}

function apiStub(loaded: ExperimentRun = run()) {
  const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
  vi.spyOn(api, 'get').mockImplementation(async () => loaded as never)
  return api
}

async function load(api: ApiClient) {
  render(<ExperimentResultWorkspace api={api} />)
  await userEvent.type(screen.getByLabelText('Experiment Run ID'), 'run-1')
  await userEvent.click(screen.getByRole('button', { name: '불러오기' }))
}

describe('ExperimentResultWorkspace', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('불변식별로 기대와 관측을 나란히 보여준다', async () => {
    await load(apiStub())

    expect(await screen.findByText('오버셀이 없었다')).toBeInTheDocument()
    const item = screen.getByText('오버셀이 없었다').closest('li') as HTMLElement
    expect(item).toHaveTextContent('기대0')
    expect(item).toHaveTextContent('관측3')
    expect(item).toHaveTextContent('3건 초과 판매')
  })

  it('관측하지 못한 불변식은 위반이 아니라 판정하지 않음으로 표시한다', async () => {
    await load(apiStub())

    const item = (await screen.findByText('Redis 재고 일치')).closest('li') as HTMLElement
    expect(item).toHaveTextContent('판정하지 않음')
    expect(item).not.toHaveTextContent('위반')
  })

  it('INCONCLUSIVE는 불변식 위반이 아니라고 명시한다', async () => {
    await load(apiStub(run({ systemOutcome: 'INCONCLUSIVE' })))

    expect(await screen.findByText(/불변식이 깨졌다는 뜻이 아니라/)).toBeInTheDocument()
  })

  it('작업이 완료되지 않았으면 판정하지 않은 이유를 밝힌다', async () => {
    await load(apiStub(run({
      systemOutcome: 'INCONCLUSIVE',
      invariantResult: invariantJson({ workloadCompleted: false, targetReportedStatus: 'FAILED' }),
    })))

    expect(await screen.findByText(/Target이 작업을 끝내지 못했습니다/)).toBeInTheDocument()
  })

  it('정리 실패의 두 원인을 구분해 안내하고 다음 실험 차단을 알린다', async () => {
    await load(apiStub(run({
      runStatus: 'FAILED', systemOutcome: 'PASSED',
      cleanupStatus: 'FAILED', cleanupFailureCode: 'CLEANUP_NO_RESOURCES',
    })))

    expect(await screen.findByText(/리소스를 하나도 보고하지 않아/)).toBeInTheDocument()
    expect(screen.getByText(/다음 실험은 정리가 해결될 때까지 시작할 수 없습니다/)).toBeInTheDocument()
  })

  it('판정 기록이 깨져 있어도 정리 상태는 계속 보여준다', async () => {
    await load(apiStub(run({
      invariantResult: '{not json', cleanupStatus: 'FAILED', cleanupFailureCode: 'CLEANUP_NOT_VERIFIED',
    })))

    expect(await screen.findByText('이 실행에는 불변식 판정 기록이 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/정리가 확인되지 않았습니다/)).toBeInTheDocument()
  })

  it('실행 ID를 세션에 남겨 다시 열어도 유지된다', async () => {
    const api = apiStub()
    await load(api)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/experiments/run-1'))
    expect(sessionStorage.getItem('arl.experiment-run-id')).toContain('run-1')
  })

  it('새로고침은 같은 실행을 실제로 다시 조회한다', async () => {
    const api = apiStub()
    await load(api)
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))

    await userEvent.click(screen.getByRole('button', { name: '새로고침' }))

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
  })

  it('위반과 통과를 서로 다른 표시로 구분한다', async () => {
    await load(apiStub())

    const failed = (await screen.findByText('오버셀이 없었다')).closest('li') as HTMLElement
    const unjudged = screen.getByText('Redis 재고 일치').closest('li') as HTMLElement
    expect(failed.className).toContain('failed')
    expect(unjudged.className).toContain('not_evaluated')
  })
})
