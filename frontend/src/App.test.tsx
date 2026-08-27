import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const REGISTERED_TARGET = [{
  id: 'pv-1', targetSystemId: 'other-target', targetName: 'Other Target',
  baseUrl: 'http://localhost:18080', environment: 'TEST', source: 'USER_IMPORT', status: 'ACTIVE',
  checksum: 'x', openApiPath: null, openApiPaths: ['/v3/api-docs'],
  genericHttpEnabled: false, readOnlyOperationCount: 1, experimentProfilePresent: false,
  createdAt: '2026-01-01T00:00:00Z', activatedAt: '2026-01-01T00:00:00Z',
}]

const EFFECTIVE_SETTINGS = {
  targetSystemId: 'other-target', targetName: 'Other Target', environment: 'TEST',
  baseUrl: 'http://localhost:18080', allowedOrigin: 'http://localhost:18080', allowedCidrs: ['127.0.0.1/32'],
  healthPath: '/actuator/health', openApiPaths: ['/v3/api-docs'],
  harnessStatePath: '/api/harness/state', harnessStateFields: [], harnessResetPath: '/api/harness/reset',
  harnessFaultPath: '/api/harness/fault', harnessFaultReleasePath: '/api/harness/fault/release',
  authProfiles: ['seller', 'buyer', 'harness'], supportedFaults: ['PAYMENT_FAILURE'], allowedCalls: [],
  requestTimeout: 'PT5S', maxConcurrency: 20, maxRequestCount: 100, maxTrials: 20, generatedYaml: 'arl: {}',
}

const PILOT_DISCOVERY = {
  targetSystemId: 'other-target', profileVersionId: 'pv-1', openApiPath: '/v3/api-docs',
  openApiPaths: ['/v3/api-docs'], snapshotId: 'snapshot-1', snapshotChecksum: '0123456789abcdef',
  snapshotChecksums: ['0123456789abcdef'], discoveredOperations: [], ignoredOperationCount: 0, candidates: [],
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

function deleteCalls(): string[] {
  return vi.mocked(globalThis.fetch).mock.calls
    .filter(([, init]) => init?.method === 'DELETE')
    .map(([input]) => String(input))
}

describe('App shell', () => {
  beforeEach(() => {
    sessionStorage.clear()
    // A Response body can be read only once, so each call needs its own instance.
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => json([]))
  })

  it('상단에는 테스트·결과·세션 종료만 남는다', async () => {
    render(<App />)
    await screen.findByText(/등록한 Target이 없습니다/)
    const nav = screen.getByRole('navigation', { name: '워크벤치 단계' })
    expect([...nav.querySelectorAll('button')].map((button) => button.textContent))
      .toEqual(['테스트', '결과', '세션 종료'])
  })

  it('분석·Chat·Test Plan 같은 옛 화면으로 가는 입구가 없다', async () => {
    render(<App />)
    await screen.findByText(/등록한 Target이 없습니다/)
    for (const gone of ['분석', 'Chat', 'Target 이해 모델', '테스트 후보', 'Test Plan', '선언형 명세']) {
      expect(screen.queryByRole('button', { name: gone })).not.toBeInTheDocument()
    }
  })

  it('결과 탭은 Target 선택 없이도 실행 결과를 조회할 수 있다', async () => {
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '결과' }))

    expect(screen.getByLabelText('Test Spec Run ID')).toBeInTheDocument()
  })

  it('세션 종료가 Target 선택 여부와 무관하게 서버 세션까지 지운다', async () => {
    sessionStorage.setItem('arl.target-profile-yaml-draft', JSON.stringify('arl:\n  targets: []'))
    render(<App />)

    await userEvent.click(screen.getByRole('button', { name: '세션 종료' }))

    // A reloaded page has no selected Target but its cookie still points at live credentials, so the call is
    // unconditional. Gating it would leave them behind while telling the user they were cleared.
    await waitFor(() => expect(deleteCalls()).toContain('/api/target-credential-session'))
    expect(await screen.findByText(/세션을 종료했습니다/)).toBeInTheDocument()
    expect(sessionStorage.getItem('arl.target-profile-yaml-draft')).toBeNull()
  })

  it('세션 종료의 서버 요청이 실패하면 성공으로 보고하지 않는다', async () => {
    vi.mocked(globalThis.fetch).mockImplementation(async (_input, init) =>
      init?.method === 'DELETE' ? json({ code: 'X', message: 'y' }, 500) : json([]))
    render(<App />)

    await userEvent.click(screen.getByRole('button', { name: '세션 종료' }))

    const notice = await screen.findByText(/삭제 요청이 실패했습니다/)
    expect(notice).toHaveClass('error')
  })

  it('Target을 선택하면 상단 표시가 따라간다', async () => {
    vi.mocked(globalThis.fetch).mockImplementation(async (input) =>
      String(input).includes('/effective-settings') ? json(EFFECTIVE_SETTINGS)
        : String(input).includes('/pilot-discovery') ? json(PILOT_DISCOVERY)
          : json(REGISTERED_TARGET))
    render(<App />)

    await userEvent.click(await screen.findByRole('button', { name: /Other Target/ }))

    expect(await screen.findByText(/선택한 Target: other-target/)).toBeInTheDocument()
  })
})
