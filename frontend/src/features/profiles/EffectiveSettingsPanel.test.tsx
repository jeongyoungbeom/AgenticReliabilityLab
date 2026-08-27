import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import type { ApiClient } from '../../api/ApiClient'
import { EffectiveSettingsPanel } from './EffectiveSettingsPanel'

const generatedYaml = 'arl:\n  targets:\n    registrations: []\n'

it('starts advanced YAML from the complete generated Profile instead of an empty document', async () => {
  const useGeneratedYaml = vi.fn()
  const api = {
    get: vi.fn().mockResolvedValue({
      targetSystemId: 'sideproject-local', targetName: 'SideProject', environment: 'LOCAL',
      baseUrl: 'http://localhost:18080', allowedOrigin: 'http://localhost:18080', allowedCidrs: ['127.0.0.1/32'],
      healthPath: '/actuator/health', openApiPaths: ['/api-docs/product'],
      harnessStatePath: '/api/harness/state', harnessStateFields: ['orderCount'],
      harnessResetPath: '/api/harness/reset', harnessFaultPath: '/api/harness/fault',
      harnessFaultReleasePath: '/api/harness/fault/release', authProfiles: ['seller', 'buyer', 'harness'],
      supportedFaults: ['PAYMENT_FAILURE'], allowedCalls: ['GET /api/products'], requestTimeout: 'PT5S',
      maxConcurrency: 20, maxRequestCount: 100, maxTrials: 20, generatedYaml,
    }),
  } as unknown as ApiClient

  render(
    <EffectiveSettingsPanel
      api={api}
      profile={{
        id: 'profile-1', targetSystemId: 'sideproject-local', targetName: 'SideProject',
        baseUrl: 'http://localhost:18080', environment: 'LOCAL', source: 'USER_IMPORT', status: 'ACTIVE',
        checksum: 'checksum', openApiPath: null, openApiPaths: ['/api-docs/product'], genericHttpEnabled: true,
        readOnlyOperationCount: 1, experimentProfilePresent: false, createdAt: '2026-01-01T00:00:00Z', activatedAt: null,
      }}
      onUseGeneratedYaml={useGeneratedYaml}
    />,
  )

  await screen.findByText('/api-docs/product')
  const button = screen.getByRole('button', { name: '이 설정으로 고급 YAML 시작' })
  await userEvent.click(button)

  expect(useGeneratedYaml).toHaveBeenCalledWith(generatedYaml)
})

it('ignores a stale effective-settings response after the selected Profile changes', async () => {
  const first = deferred<unknown>()
  const second = deferred<unknown>()
  const useGeneratedYaml = vi.fn()
  const api = {
    get: vi.fn((path: string) => path.includes('profile-a') ? first.promise : second.promise),
  } as unknown as ApiClient
  const { rerender } = render(
    <EffectiveSettingsPanel api={api} profile={profile('profile-a', '/api-docs/a')} onUseGeneratedYaml={useGeneratedYaml} />,
  )

  rerender(
    <EffectiveSettingsPanel api={api} profile={profile('profile-b', '/api-docs/b')} onUseGeneratedYaml={useGeneratedYaml} />,
  )
  await act(async () => {
    second.resolve(settings('/api-docs/b', 'arl:\n  targets:\n    registrations: [b]\n'))
    await second.promise
  })
  expect(await screen.findByText('/api-docs/b')).toBeInTheDocument()

  await act(async () => {
    first.resolve(settings('/api-docs/a', 'arl:\n  targets:\n    registrations: [a]\n'))
    await first.promise
  })
  expect(screen.queryByText('/api-docs/a')).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '이 설정으로 고급 YAML 시작' }))
  expect(useGeneratedYaml).toHaveBeenCalledWith('arl:\n  targets:\n    registrations: [b]\n')
})

function profile(id: string, openApiPath: string) {
  return {
    id, targetSystemId: id, targetName: id, baseUrl: 'http://localhost:18080', environment: 'LOCAL' as const,
    source: 'USER_IMPORT' as const, status: 'ACTIVE' as const, checksum: 'checksum', openApiPath: null,
    openApiPaths: [openApiPath], genericHttpEnabled: true, readOnlyOperationCount: 1,
    experimentProfilePresent: false, createdAt: '2026-01-01T00:00:00Z', activatedAt: null,
  }
}

function settings(openApiPath: string, yaml: string) {
  return {
    targetSystemId: 'target', targetName: 'Target', environment: 'LOCAL', baseUrl: 'http://localhost:18080',
    allowedOrigin: 'http://localhost:18080', allowedCidrs: ['127.0.0.1/32'], healthPath: '/actuator/health',
    openApiPaths: [openApiPath], harnessStatePath: '/api/harness/state', harnessStateFields: ['orderCount'],
    harnessResetPath: '/api/harness/reset', harnessFaultPath: '/api/harness/fault',
    harnessFaultReleasePath: '/api/harness/fault/release', authProfiles: ['seller', 'buyer', 'harness'],
    supportedFaults: ['PAYMENT_FAILURE'], allowedCalls: ['GET /api/products'], requestTimeout: 'PT5S',
    maxConcurrency: 20, maxRequestCount: 100, maxTrials: 20, generatedYaml: yaml,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}
