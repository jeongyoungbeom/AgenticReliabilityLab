import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '../../api/ApiClient'
import { PilotTemplateRunnerPanel } from './PilotTemplateRunnerPanel'

const discovery = {
  targetSystemId: 'sideproject-local', profileVersionId: 'profile-1', openApiPath: '/api-docs/product',
  snapshotId: 'snapshot-1', snapshotChecksum: 'abc123', discoveredOperations: [], ignoredOperationCount: 0,
  candidates: [
    { id: 'availability', title: '가용성', description: 'safe read checks', readiness: 'READY' as const, operations: [], missingOperations: [] },
    { id: 'payment-success', title: '결제 성공', description: 'workflow', readiness: 'READY' as const, operations: [], missingOperations: [] },
    { id: 'not-ready', title: '미준비', description: 'blocked', readiness: 'NOT_READY' as const, operations: [], missingOperations: ['POST /missing'] },
  ],
}

function apiStub() {
  return {
    get: vi.fn().mockResolvedValue(discovery),
    post: vi.fn().mockResolvedValue({
      id: 'session-1', targetSystemId: 'sideproject-local', profileVersionId: 'profile-1', status: 'COMPLETED',
      resultOutcome: 'PASSED', cleanupVerified: true, createdAt: '2026-08-27T00:00:00Z', completedAt: '2026-08-27T00:00:01Z', failure: null,
      outcomes: [{
        candidateId: 'availability', specificationId: 'spec-1', testSpecRunId: 'run-1', status: 'COMPLETED',
        resultOutcome: 'PASSED', cleanupVerified: true, failureCode: null, failureMessage: null, completedAt: '2026-08-27T00:00:01Z',
      }],
    }),
  } as unknown as ApiClient
}

describe('PilotTemplateRunnerPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('selects only READY candidates and requires the explicit execution confirmation', async () => {
    const api = apiStub()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(
      <PilotTemplateRunnerPanel
        api={api}
        targetSystemId="sideproject-local"
        refreshKey={0}
        harnessPreflight={{ role: 'harness', status: 'READY', method: 'GET', path: '/api/harness/state', httpStatus: 200 }}
        onOpenRun={vi.fn()}
        onOpenSession={vi.fn()}
      />,
    )

    await screen.findByText('가용성')
    expect(screen.queryByText('미준비')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /가용성/ }))
    await userEvent.click(screen.getByRole('button', { name: '선택한 템플릿 실행' }))

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    expect(confirm).toHaveBeenCalledOnce()
    expect(api.post).toHaveBeenCalledWith(
      '/api/targets/sideproject-local/pilot-template-runs',
      { candidateIds: ['availability'], confirmation: 'EXECUTE_PILOT_TEMPLATES' },
      'executor',
      expect.stringMatching(/^pilot-template-/),
    )
    expect(await screen.findByText(/cleanup VERIFIED/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '세션 결과 보기' })).toBeInTheDocument()
  })

  it('does not show executable choices until the non-mutating Harness state preflight succeeds', async () => {
    const api = apiStub()
    render(
      <PilotTemplateRunnerPanel
        api={api}
        targetSystemId="sideproject-local"
        refreshKey={0}
        harnessPreflight={null}
        onOpenRun={vi.fn()}
        onOpenSession={vi.fn()}
      />,
    )

    expect(await screen.findByText(/Harness 실행 게이트/)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /가용성/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택한 템플릿 실행' })).toBeDisabled()
  })

  it('clears an existing choice and refuses execution when a later Harness preflight fails', async () => {
    const api = apiStub()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { rerender } = render(
      <PilotTemplateRunnerPanel
        api={api}
        targetSystemId="sideproject-local"
        refreshKey={0}
        harnessPreflight={{ role: 'harness', status: 'READY', method: 'GET', path: '/api/harness/state', httpStatus: 200 }}
        onOpenRun={vi.fn()}
        onOpenSession={vi.fn()}
      />,
    )

    await userEvent.click(await screen.findByRole('checkbox', { name: /가용성/ }))
    rerender(
      <PilotTemplateRunnerPanel
        api={api}
        targetSystemId="sideproject-local"
        refreshKey={0}
        harnessPreflight={{ role: 'harness', status: 'TARGET_UNREACHABLE', method: 'GET', path: '/api/harness/state', httpStatus: null }}
        onOpenRun={vi.fn()}
        onOpenSession={vi.fn()}
      />,
    )

    const execute = screen.getByRole('button', { name: '선택한 템플릿 실행' })
    expect(execute).toBeDisabled()
    await userEvent.click(execute)
    expect(confirm).not.toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()
  })

})
