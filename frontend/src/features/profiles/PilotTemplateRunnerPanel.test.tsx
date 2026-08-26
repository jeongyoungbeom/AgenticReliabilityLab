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
      targetSystemId: 'sideproject-local',
      outcomes: [{ candidateId: 'availability', specificationId: 'spec-1', failureCode: null, failureMessage: null, run: {
        id: 'run-1', status: 'COMPLETED', resultOutcome: 'PASSED', trialsRun: 1, requestedTrials: 1, cleanupVerified: true, failure: null,
        trials: [],
      } }],
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
        credentialSessionId="credential-session-0001"
        onOpenRun={vi.fn()}
        onOpenRegression={vi.fn()}
        onOpenAiProposal={vi.fn()}
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
      { 'X-ARL-Target-Credential-Session': 'credential-session-0001' },
    )
    expect(await screen.findByText(/cleanup VERIFIED/)).toBeInTheDocument()
  })

  it('opens the existing regression and AI proposal workspaces', async () => {
    const onOpenRegression = vi.fn()
    const onOpenAiProposal = vi.fn()
    render(
      <PilotTemplateRunnerPanel api={apiStub()} targetSystemId="sideproject-local" refreshKey={0}
        credentialSessionId={null} onOpenRun={vi.fn()}
        onOpenRegression={onOpenRegression} onOpenAiProposal={onOpenAiProposal} />,
    )

    await screen.findByText('가용성')
    fireEvent.click(screen.getByRole('button', { name: '회귀 실행·결과 열기' }))
    fireEvent.click(screen.getByRole('button', { name: 'AI 제안 검토 열기' }))
    expect(onOpenRegression).toHaveBeenCalledOnce()
    expect(onOpenAiProposal).toHaveBeenCalledOnce()
  })
})
