import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TestSpecRunResponse } from '../../api/testSpecifications'
import { TestSpecRunWorkspace } from './TestSpecRunWorkspace'

const run: TestSpecRunResponse = {
  id: 'run-1',
  specificationId: 'spec-1',
  targetSystemId: 'commerce',
  profileVersionId: 'profile-1',
  status: 'COMPLETED',
  requestedTrials: 3,
  resultOutcome: 'INCONCLUSIVE',
  trialsRun: 3,
  trialsViolated: 0,
  trialsInconclusive: 1,
  cleanupVerified: true,
  createdBy: 'operator',
  createdAt: '2026-08-25T00:00:00Z',
  startedAt: '2026-08-25T00:00:01Z',
  completedAt: '2026-08-25T00:00:02Z',
  failure: null,
  resets: [],
  trials: [{
    trialNumber: 1,
    outcome: 'INCONCLUSIVE',
    stateChanged: true,
    completed: true,
    failure: null,
    timings: [],
    verdicts: [{
      invariantId: 'every-order-traced',
      description: '모든 주문이 트레이스되어야 한다',
      outcome: 'NOT_EVALUATED',
      condition: 'traceCount(reserveSpans) == 3',
      observedValues: { reserveSpans: '[{traceId=t0, ...}] (12 spans across 3 traces)' },
      notEvaluatedReason: 'OBSERVATION_MISSING',
      detail: 'no trace carries both spans',
      appliedException: null,
    }],
  }],
}

describe('TestSpecRunWorkspace', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
    // A Response body can be read only once, so polling would reuse a disturbed one.
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse(run))
  })

  it('판정 불가와 근거 문자열을 시행별로 생략 없이 보여준다', async () => {
    render(
      <TestSpecRunWorkspace
        api={new ApiClient({ viewer: '', profileEditor: '', executor: '' })}
        selectedTargetId={null}
        selectedPilotTestSessionId={null}
        onSelectPilotTestSession={vi.fn()}
        selectedRunId="run-1"
        onSelectRun={vi.fn()}
      />,
    )

    expect(await screen.findByText('실행 판정 불가')).toBeInTheDocument()
    expect(screen.getByText('시행 판정 불가')).toBeInTheDocument()
    expect(screen.getByText('판정 불가')).toBeInTheDocument()
    expect(screen.getByText('관측 없음')).toBeInTheDocument()
    expect(screen.getByText('[{traceId=t0, ...}] (12 spans across 3 traces)')).toBeInTheDocument()
    expect(screen.getByText('no trace carries both spans')).toBeInTheDocument()
  })

  it('명세 실행은 세션 헤더 없이 쿠키로만 나간다', async () => {
    render(
      <TestSpecRunWorkspace
        api={new ApiClient({ viewer: '', profileEditor: '', executor: '' })}
        selectedTargetId={null}
        selectedPilotTestSessionId={null}
        onSelectPilotTestSession={vi.fn()}
        selectedRunId={null}
        onSelectRun={vi.fn()}
      />,
    )

    await userEvent.type(screen.getByLabelText('Test Specification ID'), 'spec-1')
    await userEvent.click(screen.getByRole('button', { name: '실행 시작' }))

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    const [, init] = vi.mocked(globalThis.fetch).mock.calls[0]
    expect(new Headers(init?.headers).get('X-ARL-Target-Credential-Session')).toBeNull()
  })
})

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
