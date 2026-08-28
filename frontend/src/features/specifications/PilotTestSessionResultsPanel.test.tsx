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

  it('does not replace an unavailable selected session with the first listed session', async () => {
    const api = { get: vi.fn().mockResolvedValue([session]) } as unknown as ApiClient
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId="commerce"
        selectedSessionId="missing-session"
        onSelectSession={vi.fn()}
        onOpenRun={vi.fn()}
      />,
    )

    expect(await screen.findByText('선택한 파일럿 세션을 현재 목록에서 찾을 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByText('선택한 후보')).not.toBeInTheDocument()
  })

  it('displays loading instead of an empty-result claim while the session list is pending', () => {
    const api = { get: vi.fn().mockReturnValue(new Promise(() => {})) } as unknown as ApiClient
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId="commerce"
        selectedSessionId={null}
        onSelectSession={vi.fn()}
        onOpenRun={vi.fn()}
      />,
    )

    expect(screen.getByText('저장된 파일럿 세션을 불러오는 중입니다.')).toBeInTheDocument()
    expect(screen.queryByText('이 Target에 저장된 파일럿 세션이 없습니다.')).not.toBeInTheDocument()
  })

  it('keeps item status and an error code visible when no failure message is available', async () => {
    const recoverySession = {
      ...session,
      status: 'RECOVERY_REQUIRED' as const,
      resultOutcome: 'PASSED' as const,
      cleanupVerified: false,
      failure: 'Target cleanup must be verified',
      outcomes: [{
        ...session.outcomes[0],
        status: 'RECOVERY_REQUIRED' as const,
        cleanupVerified: false,
        failureCode: 'TEST_SPEC_RUN_RECOVERY_REQUIRED',
        failureMessage: null,
      }],
    }
    const api = { get: vi.fn().mockResolvedValue([recoverySession]) } as unknown as ApiClient
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId="commerce"
        selectedSessionId="session-1"
        onSelectSession={vi.fn()}
        onOpenRun={vi.fn()}
      />,
    )

    expect(await screen.findByText('상태 RECOVERY_REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('TEST_SPEC_RUN_RECOVERY_REQUIRED')).toBeInTheDocument()
    expect(screen.getByText('Target cleanup must be verified')).toBeInTheDocument()
  })

  it('shows a stored Korean diagnosis and keeps the technical code as supporting detail', async () => {
    const diagnosedSession = {
      ...session,
      status: 'RECOVERY_REQUIRED' as const,
      resultOutcome: 'INCONCLUSIVE' as const,
      cleanupVerified: false,
      failure: '이전 실행의 정리 상태를 확인해야 합니다.',
      diagnosis: {
        stage: 'RECOVERY' as const,
        summary: '이전 실행의 정리 상태를 확인해야 합니다.',
        likelyCause: '상태 변경 실행이 중단됐거나 reset 검증이 완료되지 않았습니다.',
        nextAction: '정리 상태를 확인한 뒤 복구가 검증된 경우에만 다음 실행을 시작하세요.',
        technicalDetail: 'code=TEST_SPEC_RUN_RECOVERY_REQUIRED',
      },
      outcomes: [{
        ...session.outcomes[0],
        status: 'RECOVERY_REQUIRED' as const,
        resultOutcome: 'INCONCLUSIVE' as const,
        cleanupVerified: false,
        failureCode: 'TEST_SPEC_RUN_RECOVERY_REQUIRED',
        failureMessage: '이전 실행의 정리 상태를 확인해야 합니다.',
        diagnosis: {
          stage: 'RECOVERY' as const,
          summary: '이전 실행의 정리 상태를 확인해야 합니다.',
          likelyCause: '상태 변경 실행이 중단됐거나 reset 검증이 완료되지 않았습니다.',
          nextAction: '정리 상태를 확인한 뒤 복구가 검증된 경우에만 다음 실행을 시작하세요.',
          technicalDetail: 'code=TEST_SPEC_RUN_RECOVERY_REQUIRED',
        },
      }],
    }
    const api = { get: vi.fn().mockResolvedValue([diagnosedSession]) } as unknown as ApiClient
    render(
      <PilotTestSessionResultsPanel
        api={api}
        targetSystemId="commerce"
        selectedSessionId="session-1"
        onSelectSession={vi.fn()}
        onOpenRun={vi.fn()}
      />,
    )

    expect(await screen.findAllByText('복구 단계 진단')).toHaveLength(2)
    expect(screen.getAllByText(/다음 행동:/)).toHaveLength(2)
    expect(screen.getAllByText('기술 정보')).toHaveLength(2)
  })
})
