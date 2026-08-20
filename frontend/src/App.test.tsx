import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

describe('App shell', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
  })

  it('상단 단계는 네 개로 유지한다', () => {
    render(<App />)
    const nav = screen.getByRole('navigation', { name: '워크벤치 단계' })
    expect(nav.querySelectorAll('button')).toHaveLength(4)
  })

  it('Target Profile 안에서 이해 모델로 이동한다', async () => {
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'Target 이해 모델' }))

    expect(screen.getByText(/먼저 Target Profile 화면에서 Target을 선택/)).toBeInTheDocument()
  })

  it('안전 테스트는 후보·계획·즉시 실행·실험 결과로 나뉜다', async () => {
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '2. 안전 테스트' }))

    const nav = screen.getByRole('navigation', { name: '안전 테스트 하위 단계' })
    expect(nav.querySelectorAll('button')).toHaveLength(4)
  })

  it('실험 결과 화면은 Target 선택 없이도 열린다', async () => {
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '2. 안전 테스트' }))
    await userEvent.click(screen.getByRole('button', { name: '실험 결과' }))

    expect(screen.getByLabelText('Experiment Run ID')).toBeInTheDocument()
  })

  it('Target을 바꾸면 저장된 Test Plan을 버린다', async () => {
    sessionStorage.setItem('arl.test-plan-id', JSON.stringify('plan-from-other-target'))
    vi.mocked(globalThis.fetch).mockResolvedValue(
      new Response(
        JSON.stringify([{
          id: 'pv-1', targetSystemId: 'other-target', source: 'YAML', status: 'ACTIVE', checksum: 'x',
          genericHttpEnabled: false, readOnlyOperationCount: 1, experimentProfilePresent: false,
          createdAt: '2026-01-01T00:00:00Z', activatedAt: '2026-01-01T00:00:00Z',
        }]),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    render(<App />)

    await userEvent.click(await screen.findByRole('button', { name: /other-target/ }))

    // The hook removes the key rather than storing a null, so an absent entry is what "dropped" looks like.
    expect(sessionStorage.getItem('arl.test-plan-id')).toBeNull()
  })
})
