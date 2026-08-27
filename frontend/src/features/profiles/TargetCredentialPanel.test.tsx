import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { TargetCredentialPreflightResult, TargetRuntimeCredentialStatus } from '../../api/targetCredentials'
import { TargetCredentialPanel } from './TargetCredentialPanel'

describe('TargetCredentialPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('Target 토큰을 런타임에 적용한 뒤 입력칸을 지우고 저장소를 사용하지 않는다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: [], sessionActive: false,
    } as never)
    vi.spyOn(api, 'put').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: ['seller'], sessionActive: true,
    } as never)
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    render(<CredentialPanelHarness api={api} />)

    expect(screen.getByRole('button', { name: '메모리에서 지우기' })).toBeDisabled()

    const seller = screen.getByLabelText('Target seller')
    await user.type(seller, 'short-lived-target-token')
    await user.click(screen.getByRole('button', { name: '런타임에 적용' }))

    await waitFor(() => expect(seller).toHaveValue(''))
    expect(screen.getByText(/입력칸은 지웠습니다/)).toBeInTheDocument()
    // The session id is never handed to page scripts; it travels only in the HttpOnly cookie.
    expect(storageSpy).not.toHaveBeenCalled()
    expect(vi.mocked(api.put).mock.calls[0][3]).toBeUndefined()
    expect(screen.getByRole('button', { name: '메모리에서 지우기' })).toBeEnabled()
  })

  it('새로고침 후에도 쿠키가 가리키는 살아 있는 세션을 서버에서 복구한다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: ['seller', 'harness'], sessionActive: true,
    } as never)
    render(<CredentialPanelHarness api={api} />)

    expect(await screen.findByText(/런타임 보관 역할: seller, harness/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '메모리에서 지우기' })).toBeEnabled()
    expect(api.get).toHaveBeenCalledWith('/api/targets/sideproject-local/runtime-credentials', 'executor')
  })

  it('역할별 preflight 결과를 인증 문제와 연결 문제로 구분한다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: [], sessionActive: false,
    } as never)
    vi.spyOn(api, 'post').mockResolvedValue([
      { role: 'seller', status: 'READY', method: 'GET', path: '/api/products/my', httpStatus: 200 },
      { role: 'buyer', status: 'TARGET_CREDENTIAL_EXPIRED', method: 'GET', path: '/api/orders', httpStatus: 401 },
      { role: 'harness', status: 'TARGET_UNREACHABLE', method: 'GET', path: '/api/harness/state', httpStatus: null },
    ] as never)
    const user = userEvent.setup()
    render(<CredentialPanelHarness api={api} />)

    await user.click(screen.getByRole('button', { name: '역할별 preflight' }))

    expect(await screen.findByText('인증 확인됨')).toBeInTheDocument()
    expect(screen.getByText('만료 또는 권한 없음')).toBeInTheDocument()
    expect(screen.getByText('Target 연결 실패')).toBeInTheDocument()
  })

  it('자격증명을 교체하면 기존 preflight를 무효화한다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: ['seller', 'harness'], sessionActive: true,
    } as never)
    vi.spyOn(api, 'put').mockResolvedValue({
      targetSystemId: 'sideproject-local', storedRoles: ['seller', 'buyer', 'harness'], sessionActive: true,
    } as never)
    const clearPreflight = vi.fn()
    const user = userEvent.setup()
    render(
      <TargetCredentialPanel
        api={api}
        targetSystemId="sideproject-local"
        credentialStatus={{ targetSystemId: 'sideproject-local', storedRoles: ['seller', 'harness'], sessionActive: true }}
        preflight={[{ role: 'harness', status: 'READY', method: 'GET', path: '/api/harness/state', httpStatus: 200 }]}
        onCredentialStatusChange={vi.fn()}
        onPreflightChange={clearPreflight}
      />,
    )

    await user.type(screen.getByLabelText('Target seller'), 'replacement-token')
    await user.click(screen.getByRole('button', { name: '런타임에 적용' }))

    await waitFor(() => expect(api.put).toHaveBeenCalled())
    expect(clearPreflight).toHaveBeenCalledWith([])
  })
})

function CredentialPanelHarness({ api }: { api: ApiClient }) {
  const [status, setStatus] = useState<TargetRuntimeCredentialStatus | null>(null)
  const [preflight, setPreflight] = useState<TargetCredentialPreflightResult[]>([])
  return (
    <TargetCredentialPanel
      api={api}
      targetSystemId="sideproject-local"
      credentialStatus={status}
      preflight={preflight}
      onCredentialStatusChange={setStatus}
      onPreflightChange={setPreflight}
    />
  )
}
