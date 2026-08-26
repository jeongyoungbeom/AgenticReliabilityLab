import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TestSpecificationResponse } from '../../api/testSpecifications'
import { TestSpecApprovalWorkspace } from './TestSpecApprovalWorkspace'

const specification: TestSpecificationResponse = {
  id: 'spec-1',
  targetSystemId: 'commerce',
  specKey: 'inventory-never-negative',
  version: 1,
  title: '재고 판정',
  profileVersionId: 'profile-1',
  profileVersionActive: true,
  source: 'USER_REQUESTED',
  category: 'CONCURRENCY',
  risk: 'MODERATE',
  status: 'PENDING_APPROVAL',
  document: {
    observations: [{ id: 'stock', description: '현재 재고' }],
    invariants: [{
      id: 'stock-never-negative',
      description: '재고는 음수가 아니다',
      condition: 'stock >= 0',
      exceptions: [{ condition: 'stock == -1', description: '예약 행 sentinel', approvedBy: 'reviewer' }],
    }],
    setup: [{ name: '재고 fixture 생성', call: { method: 'POST', path: '/fixtures' } }],
    workload: [{ kind: 'INJECT_FAULT', name: '결제 장애', faultType: 'PAYMENT_FAILURE', scope: 'next-1', ttl: 30_000 }],
    policy: { trials: 3, interval: 1_000, cleanupTiming: 'AFTER_ALL' },
  },
  checksum: 'checksum-1',
  requiredConfirmation: 'APPROVE_MODERATE_TEST_SPECIFICATION',
  unfoundedThresholds: ['stock-never-negative'],
  createdBy: 'reviewer',
  createdAt: '2026-08-25T00:00:00Z',
  approvedBy: null,
  approvedAt: null,
  terminalReason: null,
}

describe('TestSpecApprovalWorkspace', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(globalThis, 'fetch')
  })

  it('근거 없는 임계값을 해당 불변식에 붙이고 정확한 문구를 요구한다', async () => {
    vi.mocked(globalThis.fetch)
      .mockResolvedValueOnce(jsonResponse([specification]))
      .mockResolvedValueOnce(jsonResponse(specification))
      .mockResolvedValueOnce(jsonResponse({ ...specification, status: 'APPROVED', approvedBy: 'operator' }))
    const onSelectSpecification = vi.fn()
    const user = userEvent.setup()

    render(
      <TestSpecApprovalWorkspace
        api={new ApiClient({ viewer: '', profileEditor: '', executor: '' })}
        targetSystemId="commerce"
        selectedSpecificationId="spec-1"
        onSelectSpecification={onSelectSpecification}
      />,
    )

    expect(await screen.findByText('재고는 음수가 아니다')).toBeInTheDocument()
    const invariant = screen.getByText('재고는 음수가 아니다').closest('li')
    expect(invariant).not.toBeNull()
    expect(within(invariant!).getByText('근거: 없음')).toBeInTheDocument()
    expect(within(invariant!).getByText('예약 행 sentinel')).toBeInTheDocument()
    expect(within(invariant!).getByText('승인자: reviewer · 승인 시각: 명세 API 미제공')).toBeInTheDocument()
    expect(screen.getByText('PAYMENT_FAILURE')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 준비 호출:/)).toBeInTheDocument()
    expect(screen.getByText('POST /fixtures')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 호출 및 Profile 리셋 예상 시간은 API 미제공/)).toBeInTheDocument()
    const approve = screen.getByRole('button', { name: '이 기준으로 승인' })
    expect(approve).toBeDisabled()

    await user.type(screen.getByLabelText('승인 확인 문구'), 'APPROVE_MODERATE_TEST_SPECIFICATION')
    expect(approve).toBeEnabled()
    await user.click(approve)

    await waitFor(() => expect(screen.getByText(/판정 기준을 승인했습니다/)).toBeInTheDocument())
    expect(globalThis.fetch).toHaveBeenCalledWith(
      '/api/test-specifications/spec-1/approve',
      expect.objectContaining({ method: 'POST' }),
    )
  })
})

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
