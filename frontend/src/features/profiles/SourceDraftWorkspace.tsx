import { useRef, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { TargetProfileDraft } from '../../api/targetProfileDraft'

interface SourceDraftWorkspaceProps {
  api: ApiClient
  onUseYaml: (yaml: string) => void
}

type SourceKind = 'openapi' | 'readme'

const limits: Record<SourceKind, number> = { openapi: 1_048_576, readme: 262_144 }

export function SourceDraftWorkspace({ api, onUseYaml }: SourceDraftWorkspaceProps) {
  const [source, setSource] = useState<SourceKind>('openapi')
  const [document, setDocument] = useState('')
  const [draft, setDraft] = useState<TargetProfileDraft | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  async function generateDraft() {
    try {
      setBusy(true)
      setMessage(null)
      const result = await api.post<TargetProfileDraft>(
        `/api/target-profile-drafts/${source}`,
        { document },
        'profileEditor',
      )
      setDraft(result)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function selectFile(file: File | undefined) {
    if (!file) return
    if (file.size > limits[source]) {
      setMessage(`${sourceLabel(source)} 입력은 ${formatBytes(limits[source])}를 넘길 수 없습니다.`)
      return
    }
    const value = await file.text()
    if (new TextEncoder().encode(value).byteLength > limits[source]) {
      setMessage(`${sourceLabel(source)} 입력은 UTF-8 기준 ${formatBytes(limits[source])}를 넘길 수 없습니다.`)
      return
    }
    setDocument(value)
  }

  function changeSource(next: SourceKind) {
    setSource(next)
    setDocument('')
    setDraft(null)
    setMessage(null)
  }

  return (
    <section className="card source-draft-workspace">
      <div className="section-heading">
        <div>
          <p className="eyebrow">선택 사항 · OpenAPI / README Draft</p>
          <h2>실행하지 않는 Profile 제안</h2>
        </div>
        <button className="secondary-button" type="button" onClick={() => inputRef.current?.click()}>파일 불러오기</button>
        <input
          ref={inputRef}
          className="visually-hidden"
          type="file"
          accept={source === 'openapi' ? '.json,.yaml,.yml,application/json,application/yaml' : '.md,text/markdown,text/plain'}
          onChange={(event) => void selectFile(event.target.files?.[0])}
        />
      </div>
      <p className="muted">외부 URL·remote $ref·callback·webhook은 읽지 않습니다. README는 지시가 아닌 비신뢰 텍스트로만 처리합니다.</p>
      <div className="segmented-control" role="group" aria-label="Draft source">
        <button type="button" className={source === 'openapi' ? 'active' : undefined} onClick={() => changeSource('openapi')}>OpenAPI</button>
        <button type="button" className={source === 'readme' ? 'active' : undefined} onClick={() => changeSource('readme')}>README</button>
      </div>
      <textarea
        aria-label={`${sourceLabel(source)} document`}
        spellCheck={false}
        rows={10}
        value={document}
        onChange={(event) => setDocument(event.target.value)}
        placeholder={source === 'openapi' ? 'OpenAPI JSON 또는 YAML을 붙여넣으세요.' : 'README의 GET endpoint 설명을 붙여넣으세요.'}
      />
      <div className="button-row">
        <button type="button" onClick={() => void generateDraft()} disabled={busy || !document.trim()}>안전한 Draft 제안</button>
        <span className="field-note">{formatBytes(new TextEncoder().encode(document).byteLength)} / {formatBytes(limits[source])}</span>
      </div>
      {message && <p className="notice error">{message}</p>}
      {draft && (
        <div className="draft-result">
          <strong>{draft.suggestedTargetName} · {draft.readOnlyOperations.length}개 GET 후보</strong>
          <ul>{draft.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
          <button type="button" onClick={() => onUseYaml(draft.yaml)}>YAML 초안으로 가져오기</button>
        </div>
      )}
    </section>
  )
}

function sourceLabel(source: SourceKind): string {
  return source === 'openapi' ? 'OpenAPI' : 'README'
}

function formatBytes(value: number): string {
  return value >= 1024 * 1024 ? `${(value / (1024 * 1024)).toFixed(1)} MiB` : `${Math.ceil(value / 1024)} KiB`
}
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : 'Draft를 생성하지 못했습니다.'
}
