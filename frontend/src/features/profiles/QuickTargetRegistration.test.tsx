import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import { QuickTargetRegistration } from './QuickTargetRegistration'

describe('QuickTargetRegistration', () => {
  it('이름·URL·환경만 표준 Profile 등록 API로 보낸다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'post').mockResolvedValue({
      id: 'profile-1', targetSystemId: 'quick-sideproject', targetName: 'SideProject',
      baseUrl: 'http://host.docker.internal:18080', environment: 'LOCAL', source: 'USER_IMPORT', status: 'ACTIVE',
      checksum: 'checksum', openApiPath: '/api-docs/product', openApiPaths: ['/api-docs/product'],
      genericHttpEnabled: true, readOnlyOperationCount: 1, experimentProfilePresent: false,
      createdAt: '2026-08-27T00:00:00Z', activatedAt: '2026-08-27T00:00:00Z',
    } as never)
    const onRegistered = vi.fn()
    const user = userEvent.setup()
    render(<QuickTargetRegistration api={api} busy={false} onRegistered={onRegistered} onError={vi.fn()} />)

    await user.type(screen.getByLabelText('Target 이름'), 'SideProject')
    await user.type(screen.getByLabelText('Target URL'), 'http://host.docker.internal:18080')
    await user.click(screen.getByRole('button', { name: '간편 등록' }))

    expect(api.post).toHaveBeenCalledWith(
      '/api/target-profiles/quick-register',
      { name: 'SideProject', baseUrl: 'http://host.docker.internal:18080', environment: 'LOCAL' },
      'profileEditor',
    )
    expect(onRegistered).toHaveBeenCalled()
  })
})
