import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TestSpecRunResponse } from '../../api/testSpecifications'
import {
  MisjudgmentReportWorkspace,
  RegressionRunWorkspace,
  TestSpecGenerationWorkspace,
} from './TestSpecFollowUpWorkspaces'

const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })

const violatedRun: TestSpecRunResponse = {
  id: 'run-1', specificationId: 'spec-1', targetSystemId: 'commerce', profileVersionId: 'profile-1',
  status: 'COMPLETED', requestedTrials: 1, resultOutcome: 'VIOLATED', trialsRun: 1, trialsViolated: 1,
  trialsInconclusive: 0, cleanupVerified: true, createdBy: 'operator', createdAt: '2026-08-25T00:00:00Z',
  startedAt: null, completedAt: null, failure: null, resets: [],
  trials: [{
    trialNumber: 1, outcome: 'VIOLATED', stateChanged: true, completed: true, failure: null, timings: [],
    verdicts: [{
      invariantId: 'stock-never-negative', description: '재고는 음수가 아니다', outcome: 'VIOLATED',
      condition: 'stock >= 0', observedValues: {}, notEvaluatedReason: null, detail: null, appliedException: null,
    }],
  }],
}

describe('TestSpecFollowUpWorkspaces', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
    vi.spyOn(globalThis, 'fetch')
  })

  it('회귀 실행은 명세별 성공과 거부를 함께 표시한다', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(jsonResponse({
      targetSystemId: 'commerce',
      runs: [
        { specificationId: 'spec-1', specKey: 'stock', version: 2, run: { ...violatedRun, resultOutcome: 'PASSED' }, failureCode: null, failureMessage: null },
        { specificationId: 'spec-2', specKey: 'payment', version: 1, run: null, failureCode: 'ACTIVE_RUN_EXISTS', failureMessage: '다른 실행이 진행 중입니다.' },
      ],
    }))
    const user = userEvent.setup()

    render(<RegressionRunWorkspace api={api} targetSystemId="commerce" />)
    await user.click(screen.getByRole('button', { name: '회귀 실행 요청' }))

    expect(await screen.findByText('실행 통과')).toBeInTheDocument()
    expect(screen.getByText('실행 불가')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE_RUN_EXISTS: 다른 실행이 진행 중입니다.')).toBeInTheDocument()
  })

  it('LLM 제안은 UTF-8 byte 수와 거부된 후보의 원문을 함께 보여준다', async () => {
    vi.mocked(globalThis.fetch)
      .mockResolvedValueOnce(jsonResponse([snapshot]))
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse({
        id: 'generation-1', targetSystemId: 'commerce', knowledgeSnapshotId: 'snapshot-1', modelKey: 'default', modelId: 'model',
        promptVersion: 'v1', inputChecksum: 'checksum', status: 'COMPLETED', promptTokenCount: null, completionTokenCount: null,
        durationMillis: 10, failureCode: null, failureMessage: null, requestedAt: '2026-08-25T00:00:00Z', startedAt: null,
        completedAt: '2026-08-25T00:00:01Z',
        candidates: [{ ordinal: 1, outcome: 'REJECTED', specKey: 'missing-trace', title: '누락된 trace', document: { specKey: 'missing-trace' }, rejectionReason: 'TRACE 관측이 없습니다.', specificationId: null }],
      }))
    const user = userEvent.setup()

    render(<TestSpecGenerationWorkspace api={api} targetSystemId="commerce" onOpenApproval={vi.fn()} />)
    await screen.findByRole('option', { name: /^snapshot/ })
    await user.type(screen.getByLabelText('LLM OpenAPI document'), '가')
    expect(screen.getByText('3 / 1,048,576 bytes')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '제안 생성' }))

    expect(await screen.findByText('거부됨')).toBeInTheDocument()
    expect(screen.getByText('TRACE 관측이 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/"specKey": "missing-trace"/)).toBeInTheDocument()
  })

  it('오판 신고는 위반 verdict만 선택하고 초안 명세 검토로 연결한다', async () => {
    vi.mocked(globalThis.fetch)
      .mockResolvedValueOnce(jsonResponse(violatedRun))
      .mockResolvedValueOnce(jsonResponse({
        id: 'report-1', targetSystemId: 'commerce', specificationId: 'spec-1', runId: 'run-1', trialNumber: 1,
        invariantId: 'stock-never-negative', reason: '예약 행입니다.', modelKey: 'default', modelId: 'model', promptVersion: 'v1',
        status: 'DRAFTED', draftedCondition: 'stock == -1', draftedDescription: '예약 행 예외', resultingSpecificationId: 'spec-2',
        rejectionReason: null, promptTokenCount: null, completionTokenCount: null, durationMillis: 10, failureCode: null,
        failureMessage: null, requestedAt: '2026-08-25T00:00:00Z', startedAt: null, completedAt: '2026-08-25T00:00:01Z',
      }))
    const openApproval = vi.fn()
    const user = userEvent.setup()

    render(<MisjudgmentReportWorkspace api={api} targetSystemId="commerce" selectedRunId="run-1" onOpenApproval={openApproval} />)
    await user.click(screen.getByRole('button', { name: '위반 불러오기' }))
    await screen.findByRole('option', { name: /stock-never-negative/ })
    await user.type(screen.getByLabelText('정상인 이유'), '예약 행입니다.')
    await user.click(screen.getByRole('button', { name: '오판 신고 및 예외 초안 요청' }))

    expect(await screen.findByText('예약 행 예외')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '새 명세 버전 승인 화면으로 이동' }))
    expect(openApproval).toHaveBeenCalledWith('spec-2')
  })
})

const snapshot = {
  id: 'snapshot-1', targetSystemId: 'commerce', profileVersionId: 'profile-1', profileVersionActive: true,
  checksum: 'snapshot-checksum', extractionVersion: 'v1', confirmed: true, confirmedBy: 'operator',
  confirmedAt: '2026-08-25T00:00:00Z', createdBy: 'operator', createdAt: '2026-08-25T00:00:00Z',
  sources: [], operations: [], workflows: [], domainHypotheses: [], invariants: [], riskSignals: [], warnings: [],
}

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
