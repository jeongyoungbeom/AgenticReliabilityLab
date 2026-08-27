import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '../../api/ApiClient'
import { PilotTestSessionResultsPanel } from './PilotTestSessionResultsPanel'

const session = {
  id: 'session-1', targetSystemId: 'commerce', profileVersionId: 'profile-1', status: 'COMPLETED' as const,
  resultOutcome: 'PASSED' as const, cleanupVerified: true, createdAt: '2026-08-27T00:00:00Z', completedAt: '2026-08-27T00:00:01Z', failure: null,
  outcomes: [{
    candidateId: 'availability', specificationId: 'spec-1', testSpecRunId: 'run-1', status: 'COMPLETED' as const,
    resultOutcome: 'PASSED' as const, cleanupVerified: true, failureCode: null, failureMessage: null, completedAt: '2026-08-27T00:00:01Z',
  }],
}

describe('PilotTestSessionResultsPanel', () => {
  it('shows a persisted selection and opens its linked Test Spec Run', async () => {
    const api = { get: vi.fn().mockResolvedValue([session]) } as unknown as ApiClient
    const onOpenRun = vi.fn()
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId="commerce"
        selectedSessionId="session-1"
        onSelectSession={vi.fn()}
        onOpenRun={onOpenRun}
      />,
    )

    expect(await screen.findByText('선택한 후보')).toBeInTheDocument()
    expect(screen.getByText('세션 ID')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '시행 상세 보기' }))
    expect(onOpenRun).toHaveBeenCalledWith('run-1')
    expect(api.get).toHaveBeenCalledWith('/api/targets/commerce/pilot-test-sessions')
  })

  it('does not request sessions until a Target has been selected', async () => {
    const api = { get: vi.fn() } as unknown as ApiClient
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId={null}
        selectedSessionId={null}
        onSelectSession={vi.fn()}
        onOpenRun={vi.fn()}
      />,
    )

    expect(screen.getByText('먼저 Target을 선택하세요')).toBeInTheDocument()
    await waitFor(() => expect(api.get).not.toHaveBeenCalled())
  })
})
