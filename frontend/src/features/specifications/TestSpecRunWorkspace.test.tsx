import { render, screen } from '@testing-library/react'
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
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(run))
  })

  it('판정 불가와 근거 문자열을 시행별로 생략 없이 보여준다', async () => {
    render(
      <TestSpecRunWorkspace
        api={new ApiClient({ viewer: '', profileEditor: '', executor: '' })}
        selectedSpecificationId="spec-1"
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
})

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
