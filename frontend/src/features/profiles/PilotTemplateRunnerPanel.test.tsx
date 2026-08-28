import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '../../api/ApiClient'
import type { PilotTemplateExecution } from '../../api/pilotTemplates'
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

const completedSession: PilotTemplateExecution = {
  id: 'session-1', targetSystemId: 'sideproject-local', profileVersionId: 'profile-1', status: 'COMPLETED',
  resultOutcome: 'PASSED', cleanupVerified: true, createdAt: '2026-08-27T00:00:00Z', completedAt: '2026-08-27T00:00:01Z', failure: null,
  outcomes: [{
    candidateId: 'availability', specificationId: 'spec-1', testSpecRunId: 'run-1', status: 'COMPLETED',
    resultOutcome: 'PASSED', cleanupVerified: true, failureCode: null, failureMessage: null, completedAt: '2026-08-27T00:00:01Z',
  }],
}

function apiStub(execution: PilotTemplateExecution = completedSession) {
  return {
    get: vi.fn().mockResolvedValue(discovery),
    post: vi.fn().mockResolvedValue(execution),
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
    expect(await screen.findByText(/파일럿 세션 .* 정리 확인됨/)).toBeInTheDocument()
    expect(screen.getAllByText(/정리 확인됨/)).toHaveLength(2)
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

  it('shows recovery-required execution separately from a passed run judgement', async () => {
    const api = apiStub({
      ...completedSession,
      status: 'RECOVERY_REQUIRED',
      cleanupVerified: false,
      failure: 'Target cleanup requires recovery',
      outcomes: [{
        ...completedSession.outcomes[0],
        status: 'RECOVERY_REQUIRED',
        cleanupVerified: false,
        failureCode: 'TEST_SPEC_RUN_RECOVERY_REQUIRED',
        failureMessage: null,
      }],
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
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

    await userEvent.click(await screen.findByRole('checkbox', { name: /가용성/ }))
    await userEvent.click(screen.getByRole('button', { name: '선택한 템플릿 실행' }))

    expect(await screen.findByText('상태 RECOVERY_REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('정리 미확인')).toBeInTheDocument()
    expect(screen.getByText('TEST_SPEC_RUN_RECOVERY_REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('Target cleanup requires recovery')).toBeInTheDocument()
  })

  it('discards a late discovery response for a previously selected Target', async () => {
    let resolveFirst!: (value: typeof discovery) => void
    const first = new Promise<typeof discovery>((resolve) => { resolveFirst = resolve })
    const second = {
      ...discovery,
      targetSystemId: 'second-target',
      candidates: [{ ...discovery.candidates[0], title: '두 번째 Target 가용성' }],
    }
    const api = {
      get: vi.fn().mockReturnValueOnce(first).mockResolvedValueOnce(second),
      post: vi.fn(),
    } as unknown as ApiClient
    const props = {
      api,
      refreshKey: 0,
      harnessPreflight: { role: 'harness', status: 'READY' as const, method: 'GET', path: '/api/harness/state', httpStatus: 200 },
      onOpenRun: vi.fn(),
      onOpenSession: vi.fn(),
    }
    const { rerender } = render(<PilotTemplateRunnerPanel {...props} targetSystemId="first-target" />)

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    rerender(<PilotTemplateRunnerPanel {...props} targetSystemId="second-target" />)
    expect(await screen.findByText('두 번째 Target 가용성')).toBeInTheDocument()
    resolveFirst(discovery)

    await waitFor(() => expect(screen.queryByText('가용성')).not.toBeInTheDocument())
    expect(screen.getByText('두 번째 Target 가용성')).toBeInTheDocument()
  })

})
