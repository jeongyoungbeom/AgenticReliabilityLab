import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import { TargetCredentialPanel } from './TargetCredentialPanel'

describe('TargetCredentialPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('Target 토큰을 런타임에 적용한 뒤 입력칸을 지우고 저장소를 사용하지 않는다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'put').mockResolvedValue({
      targetSystemId: 'sideproject-local', credentialSessionId: 'credential-session-0001', storedRoles: ['seller'], expiresAt: '2026-08-26T01:00:00Z',
    } as never)
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const onCredentialSessionChange = vi.fn()
    const user = userEvent.setup()
    render(<TargetCredentialPanel api={api} targetSystemId="sideproject-local" onCredentialSessionChange={onCredentialSessionChange} />)

    const seller = screen.getByLabelText('Target seller')
    await user.type(seller, 'short-lived-target-token')
    await user.click(screen.getByRole('button', { name: '런타임에 적용' }))

    await waitFor(() => expect(seller).toHaveValue(''))
    expect(screen.getByText(/입력칸은 지웠습니다/)).toBeInTheDocument()
    expect(storageSpy).not.toHaveBeenCalled()
    expect(onCredentialSessionChange).toHaveBeenCalledWith('credential-session-0001')
  })

  it('역할별 preflight 결과를 인증 문제와 연결 문제로 구분한다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'post').mockResolvedValue([
      { role: 'seller', status: 'READY', method: 'GET', path: '/api/products/my', httpStatus: 200 },
      { role: 'buyer', status: 'TARGET_CREDENTIAL_EXPIRED', method: 'GET', path: '/api/orders', httpStatus: 401 },
      { role: 'harness', status: 'TARGET_UNREACHABLE', method: 'GET', path: '/api/harness/state', httpStatus: null },
    ] as never)
    const user = userEvent.setup()
    render(<TargetCredentialPanel api={api} targetSystemId="sideproject-local" onCredentialSessionChange={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: '역할별 preflight' }))

    expect(await screen.findByText('인증 확인됨')).toBeInTheDocument()
    expect(screen.getByText('만료 또는 권한 없음')).toBeInTheDocument()
    expect(screen.getByText('Target 연결 실패')).toBeInTheDocument()
  })
})
