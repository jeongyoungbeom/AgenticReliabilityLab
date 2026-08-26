import { useEffect, useRef, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import {
  createTestSpecification,
  type SpecSource,
  type TestSpecificationDocument,
} from '../../api/testSpecifications'

interface TestSpecRegistrationWorkspaceProps {
  api: ApiClient
  targetSystemId: string | null
  onRegistered: (specificationId: string) => void
}

const MAX_REQUEST_BYTES = 262_144

export function TestSpecRegistrationWorkspace({ api, targetSystemId, onRegistered }: TestSpecRegistrationWorkspaceProps) {
  const [source, setSource] = useState<SpecSource>('USER_REQUESTED')
  const [documentText, setDocumentText] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  if (!targetSystemId) return <p className="notice">먼저 Target Profile 화면에서 Target을 선택하세요.</p>

  async function register() {
    if (!targetSystemId) return
    const document = parseDocument(documentText)
    if (document instanceof Error) {
      setFailed(true)
      setMessage(document.message)
      return
    }
    const request = { targetSystemId, source, document }
    if (new TextEncoder().encode(JSON.stringify(request)).byteLength > MAX_REQUEST_BYTES) {
      setFailed(true)
      setMessage('명세 등록 요청 전체는 256 KiB를 넘길 수 없습니다.')
      return
    }

    try {
      setBusy(true)
      setMessage(null)
      const specification = await createTestSpecification(api, request)
      if (!mounted.current) return
      setFailed(false)
      setMessage(`명세 ${specification.specKey} v${specification.version}을 등록했습니다. 판정 기준을 검토한 뒤 승인하세요.`)
      onRegistered(specification.id)
    } catch (error) {
      if (!mounted.current) return
      setFailed(true)
      setMessage(errorMessage(error))
    } finally {
      if (mounted.current) setBusy(false)
    }
  }

  return (
    <section className="card specification-registration">
      <p className="eyebrow">명세 등록</p>
      <h2>무엇을 판정할지 먼저 기록합니다</h2>
      <p className="muted">
        이 단계는 실행 요청이 아닙니다. JSON 명세는 활성 Profile의 경로·권한·관측값·안전 상한으로 검증되며,
        등록 뒤에도 사람의 명시적 승인 전에는 실행할 수 없습니다.
      </p>
      <label>
        명세 출처
        <select value={source} onChange={(event) => setSource(event.target.value as SpecSource)}>
          <option value="USER_REQUESTED">사용자 작성</option>
          <option value="RULE_GENERATED">규칙 기반 생성</option>
          <option value="MODEL_PROPOSED">모델 제안</option>
        </select>
      </label>
      <label>
        Test Specification JSON
        <textarea
          aria-label="Test Specification JSON"
          rows={18}
          spellCheck={false}
          value={documentText}
          onChange={(event) => setDocumentText(event.target.value)}
          placeholder={'{\n  "specKey": "inventory-never-negative",\n  "title": "재고는 음수가 아니다",\n  "category": "CONCURRENCY",\n  "risk": "MODERATE",\n  "observations": [],\n  "invariants": [],\n  "policy": { "trials": 3 }\n}'}
        />
      </label>
      <div className="button-row">
        <button type="button" onClick={() => void register()} disabled={busy || documentText.trim() === ''}>
          명세 등록 및 검증
        </button>
        <span className="field-note">입력 JSON: {new TextEncoder().encode(documentText).byteLength.toLocaleString()} bytes · 전체 요청 한도: 262,144 bytes</span>
      </div>
      {message && <p className={failed ? 'notice error' : 'notice success'}>{message}</p>}
    </section>
  )
}

function parseDocument(value: string): TestSpecificationDocument | Error {
  try {
    const parsed: unknown = JSON.parse(value)
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
      return new Error('명세 문서는 JSON 객체여야 합니다.')
    }
    return parsed as TestSpecificationDocument
  } catch {
    return new Error('유효한 JSON 명세를 입력하세요.')
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : '명세를 등록하지 못했습니다.'
}
