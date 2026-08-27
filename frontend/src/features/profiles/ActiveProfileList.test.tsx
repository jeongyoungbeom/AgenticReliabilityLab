import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ActiveProfileList } from './ActiveProfileList'

describe('ActiveProfileList', () => {
  it('bootstrap Profile은 숨기고 사용자가 등록한 Target만 선택하게 한다', async () => {
    const onSelectTarget = vi.fn()
    render(
      <ActiveProfileList
        profiles={[
          profile({ id: 'bootstrap', targetName: 'Bootstrap Target', source: 'BOOTSTRAP' }),
          profile({ id: 'registered', targetName: 'My Target', source: 'USER_IMPORT' }),
        ]}
        selectedTargetId={null}
        onSelectTarget={onSelectTarget}
      />,
    )

    expect(screen.queryByRole('button', { name: /Bootstrap Target/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /My Target/ }))
    expect(onSelectTarget).toHaveBeenCalledWith('registered')
  })
})

function profile(overrides: Partial<{
  id: string
  targetName: string
  source: string
}> = {}) {
  return {
    id: 'target',
    targetSystemId: overrides.id ?? 'target',
    targetName: overrides.targetName ?? 'Target',
    baseUrl: 'http://localhost:18080',
    environment: 'LOCAL',
    source: overrides.source ?? 'USER_IMPORT',
    status: 'ACTIVE' as const,
    checksum: 'checksum',
    openApiPath: null,
    openApiPaths: [],
    genericHttpEnabled: true,
    readOnlyOperationCount: 1,
    experimentProfilePresent: false,
    createdAt: '2026-08-27T00:00:00Z',
    activatedAt: '2026-08-27T00:00:00Z',
  }
}
