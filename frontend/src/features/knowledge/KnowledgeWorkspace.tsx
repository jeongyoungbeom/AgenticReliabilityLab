import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  KNOWLEDGE_CONFIRMATION,
  MAX_OPENAPI_DOCUMENT_CHARACTERS,
  MAX_README_DOCUMENT_CHARACTERS,
  type CreateTargetKnowledgeSnapshotRequest,
  type TargetKnowledgeSnapshot,
} from '../../api/targetKnowledge'
import { KnowledgeSnapshotDetail } from './KnowledgeSnapshotDetail'

interface KnowledgeWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
}

/**
 * Builds and reviews the Target understanding model from documents the user pastes in.
 *
 * ARL never fetches a URL or reads a repository here: whatever is in these three boxes is the entire input, which is
 * why the screen states that plainly rather than offering an address field.
 */
export function KnowledgeWorkspace({ api, targetSystemId }: KnowledgeWorkspaceProps) {
  const [openApiDocument, setOpenApiDocument] = useState('')
  const [readmeDocument, setReadmeDocument] = useState('')
  const [invariantText, setInvariantText] = useState('')
  const [snapshots, setSnapshots] = useState<TargetKnowledgeSnapshot[]>([])
  const [selected, setSelected] = useState<TargetKnowledgeSnapshot | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  // A slower reply for a Target the user already moved away from must not overwrite the list they are looking at.
  useEffect(() => {
    let current = true
    setSelected(null)
    setSnapshots([])
    if (targetSystemId) {
      api
        .get<TargetKnowledgeSnapshot[]>(`/api/targets/${targetSystemId}/knowledge-snapshots`)
        .then((loaded) => { if (current) setSnapshots(loaded) })
        .catch((error: unknown) => { if (current) report(error) })
    }
    return () => { current = false }
  }, [api, targetSystemId])

  async function refreshSnapshots(targetId: string) {
    try {
      setSnapshots(await api.get<TargetKnowledgeSnapshot[]>(`/api/targets/${targetId}/knowledge-snapshots`))
    } catch (error) {
      report(error)
    }
  }

  async function createSnapshot() {
    if (!targetSystemId) return
    await run(async () => {
      const request: CreateTargetKnowledgeSnapshotRequest = { targetSystemId }
      if (openApiDocument.trim()) request.openApiDocument = openApiDocument
      if (readmeDocument.trim()) request.readmeDocument = readmeDocument
      const invariants = toLines(invariantText)
      if (invariants.length > 0) request.brief = { invariants }

      const snapshot = await api.post<TargetKnowledgeSnapshot>(
        '/api/target-knowledge-snapshots',
        request,
        'profileEditor',
      )
      setSelected(snapshot)
      succeed('이해 모델을 만들었습니다. 아래 내용을 검토한 뒤 확인하세요.')
      await refreshSnapshots(targetSystemId)
    })
  }

  async function confirmSnapshot(snapshotId: string) {
    await run(async () => {
      const confirmed = await api.post<TargetKnowledgeSnapshot>(
        `/api/target-knowledge-snapshots/${snapshotId}/confirmation`,
        { confirmation: KNOWLEDGE_CONFIRMATION },
        'profileEditor',
      )
      setSelected(confirmed)
      succeed('검토 확인을 기록했습니다.')
      if (targetSystemId) await refreshSnapshots(targetSystemId)
    })
  }

  async function openSnapshot(snapshotId: string) {
    await run(async () => {
      setSelected(await api.get<TargetKnowledgeSnapshot>(`/api/target-knowledge-snapshots/${snapshotId}`))
    })
  }

  function succeed(text: string) {
    setMessage(text)
    setFailed(false)
  }

  function report(error: unknown) {
    setMessage(errorMessage(error))
    setFailed(true)
  }

  async function run(action: () => Promise<void>) {
    try {
      setBusy(true)
      setMessage(null)
      setFailed(false)
      await action()
    } catch (error) {
      report(error)
    } finally {
      setBusy(false)
    }
  }

  if (!targetSystemId) {
    return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>
  }

  const hasDocument = Boolean(openApiDocument.trim() || readmeDocument.trim() || invariantText.trim())
  const oversized = [
    openApiDocument.length > MAX_OPENAPI_DOCUMENT_CHARACTERS ? 'OpenAPI 문서' : null,
    readmeDocument.length > MAX_README_DOCUMENT_CHARACTERS ? 'README 문서' : null,
  ].filter((name): name is string => name !== null)

  return (
    <div className="workspace-grid knowledge-workspace">
      <section className="card">
        <p className="eyebrow">1. 자료 제출</p>
        <h2>가진 문서만 붙여넣기</h2>
        <p className="muted">
          ARL은 외부 URL이나 저장소를 읽지 않습니다. 여기에 붙여넣은 내용이 입력의 전부이며, 이 단계에서 Target에
          요청을 보내지 않습니다.
        </p>
        <p className="notice warning">
          붙여넣기 전에 <strong>인증 토큰·비밀번호·API 키·DB 접속 문자열을 지우세요.</strong> 문서에서 읽어낸 인용문은
          그대로 저장되어 이후 후보 생성과 분석의 근거로 남습니다. ARL은 비밀값을 자동으로 가려내지 않습니다.
        </p>
        <label>
          OpenAPI 문서 (JSON 또는 YAML)
          <textarea
            rows={8}
            value={openApiDocument}
            onChange={(event) => setOpenApiDocument(event.target.value)}
            placeholder="/v3/api-docs 응답을 그대로 붙여넣어도 됩니다"
          />
        </label>
        <CharacterCount length={openApiDocument.length} limit={MAX_OPENAPI_DOCUMENT_CHARACTERS} />
        <label>
          README 또는 설명 문서
          <textarea rows={6} value={readmeDocument} onChange={(event) => setReadmeDocument(event.target.value)} />
        </label>
        <CharacterCount length={readmeDocument.length} limit={MAX_README_DOCUMENT_CHARACTERS} />
        <label>
          직접 아는 불변식 (한 줄에 하나)
          <textarea
            rows={4}
            value={invariantText}
            onChange={(event) => setInvariantText(event.target.value)}
            placeholder="재고는 0 미만이 될 수 없다"
          />
        </label>
        {oversized.length > 0 && (
          <p className="notice error">{oversized.join(', ')}이(가) 허용 크기를 넘었습니다. 줄여서 다시 시도하세요.</p>
        )}
        <div className="button-row">
          <button
            type="button"
            onClick={() => void createSnapshot()}
            disabled={busy || !hasDocument || oversized.length > 0}
          >
            이해 모델 만들기
          </button>
        </div>
        {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
      </section>

      <section className="card">
        <div className="section-heading">
          <div>
            <p className="eyebrow">2. 기존 이해 모델</p>
            <h2>{targetSystemId}</h2>
          </div>
          <button
            className="secondary-button"
            type="button"
            onClick={() => void refreshSnapshots(targetSystemId)}
            disabled={busy}
          >
            새로고침
          </button>
        </div>
        {snapshots.length === 0 ? (
          <p className="muted">아직 만든 이해 모델이 없습니다.</p>
        ) : (
          <ul className="selection-list">
            {snapshots.map((snapshot) => (
              <li key={snapshot.id}>
                <button type="button" className="link-button" onClick={() => void openSnapshot(snapshot.id)}>
                  {snapshot.checksum.slice(0, 12)}
                </button>
                <span className={snapshot.profileVersionActive ? 'badge ok' : 'badge warn'}>
                  {snapshot.profileVersionActive ? '사용 가능' : '대체됨'}
                </span>
                {snapshot.confirmed && <span className="badge">확인됨</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {selected && (
        <KnowledgeSnapshotDetail
          snapshot={selected}
          busy={busy}
          onConfirm={() => void confirmSnapshot(selected.id)}
        />
      )}
    </div>
  )
}

function CharacterCount({ length, limit }: { length: number; limit: number }) {
  if (length === 0) return null
  return (
    <p className={length > limit ? 'char-count over' : 'char-count'}>
      {length.toLocaleString()} / {limit.toLocaleString()}자
    </p>
  )
}

function toLines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
}
