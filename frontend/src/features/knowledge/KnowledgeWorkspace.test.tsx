import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import { MAX_README_DOCUMENT_CHARACTERS, type TargetKnowledgeSnapshot } from '../../api/targetKnowledge'
import { KnowledgeWorkspace } from './KnowledgeWorkspace'

function snapshot(overrides: Partial<TargetKnowledgeSnapshot> = {}): TargetKnowledgeSnapshot {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    targetSystemId: 'pilot-target',
    profileVersionId: '22222222-2222-2222-2222-222222222222',
    profileVersionActive: true,
    checksum: 'abcdef0123456789',
    extractionVersion: 'knowledge-extraction-v1',
    confirmed: false,
    confirmedBy: null,
    confirmedAt: null,
    createdBy: 'EXECUTOR',
    createdAt: '2026-01-01T00:00:00Z',
    sources: [],
    operations: [],
    workflows: [],
    domainHypotheses: [],
    invariants: [],
    riskSignals: [],
    warnings: [],
    ...overrides,
  }
}

function apiStub(handlers: { get?: unknown; post?: unknown } = {}): ApiClient {
  const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
  vi.spyOn(api, 'get').mockImplementation(async () => (handlers.get ?? []) as never)
  vi.spyOn(api, 'post').mockImplementation(async () => (handlers.post ?? snapshot()) as never)
  return api
}

describe('KnowledgeWorkspace', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('선택한 Target이 없으면 자료 제출을 막는다', () => {
    render(<KnowledgeWorkspace api={apiStub()} targetSystemId={null} />)

    expect(screen.getByText(/먼저 Target Profile 화면에서 Target을 선택/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이해 모델 만들기' })).not.toBeInTheDocument()
  })

  it('문서를 하나도 넣지 않으면 생성 버튼이 비활성이다', () => {
    render(<KnowledgeWorkspace api={apiStub()} targetSystemId="pilot-target" />)

    expect(screen.getByRole('button', { name: '이해 모델 만들기' })).toBeDisabled()
  })

  it('제출한 문서만 요청에 담고 빈 항목은 보내지 않는다', async () => {
    const api = apiStub()
    render(<KnowledgeWorkspace api={api} targetSystemId="pilot-target" />)

    await userEvent.type(screen.getByLabelText(/README 또는 설명 문서/), '재고 서비스')
    await userEvent.click(screen.getByRole('button', { name: '이해 모델 만들기' }))

    await waitFor(() => expect(api.post).toHaveBeenCalled())
    const [path, body, role] = vi.mocked(api.post).mock.calls[0]
    expect(path).toBe('/api/target-knowledge-snapshots')
    expect(role).toBe('profileEditor')
    expect(body).toEqual({ targetSystemId: 'pilot-target', readmeDocument: '재고 서비스' })
  })

  it('Profile 버전이 대체된 Snapshot은 사용할 수 없다고 알린다', async () => {
    const api = apiStub({ post: snapshot({ profileVersionActive: false }) })
    render(<KnowledgeWorkspace api={api} targetSystemId="pilot-target" />)

    await userEvent.type(screen.getByLabelText(/README 또는 설명 문서/), 'x')
    await userEvent.click(screen.getByRole('button', { name: '이해 모델 만들기' }))

    expect(await screen.findByText(/더 이상 활성이 아닙니다/)).toBeInTheDocument()
  })

  it('확인 요청에 정해진 확인 문구를 보낸다', async () => {
    const api = apiStub()
    render(<KnowledgeWorkspace api={api} targetSystemId="pilot-target" />)

    await userEvent.type(screen.getByLabelText(/README 또는 설명 문서/), 'x')
    await userEvent.click(screen.getByRole('button', { name: '이해 모델 만들기' }))
    await userEvent.click(await screen.findByRole('button', { name: '내용을 확인했습니다' }))

    await waitFor(() => expect(vi.mocked(api.post).mock.calls.length).toBeGreaterThan(1))
    const [path, body] = vi.mocked(api.post).mock.calls[1]
    expect(path).toContain('/confirmation')
    expect(body).toEqual({ confirmation: 'CONFIRM_TARGET_KNOWLEDGE' })
  })

  it('Profile 버전이 대체된 Snapshot에는 확인 버튼을 내주지 않는다', async () => {
    const api = apiStub({ post: snapshot({ profileVersionActive: false }) })
    render(<KnowledgeWorkspace api={api} targetSystemId="pilot-target" />)

    await userEvent.type(screen.getByLabelText(/README 또는 설명 문서/), 'x')
    await userEvent.click(screen.getByRole('button', { name: '이해 모델 만들기' }))

    await screen.findByText(/더 이상 활성이 아닙니다/)
    expect(screen.queryByRole('button', { name: '내용을 확인했습니다' })).not.toBeInTheDocument()
  })

  it('비밀값을 지우라고 경고한다', () => {
    render(<KnowledgeWorkspace api={apiStub()} targetSystemId="pilot-target" />)

    expect(screen.getByText(/인증 토큰·비밀번호·API 키·DB 접속 문자열을 지우세요/)).toBeInTheDocument()
  })

  it('서버 상한을 넘는 문서는 보내기 전에 막는다', async () => {
    const api = apiStub()
    render(<KnowledgeWorkspace api={api} targetSystemId="pilot-target" />)

    const readme = screen.getByLabelText(/README 또는 설명 문서/)
    fireEvent.change(readme, { target: { value: 'x'.repeat(MAX_README_DOCUMENT_CHARACTERS + 1) } })

    expect(screen.getByRole('button', { name: '이해 모델 만들기' })).toBeDisabled()
    expect(screen.getByText(/허용 크기를 넘었습니다/)).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })
})
