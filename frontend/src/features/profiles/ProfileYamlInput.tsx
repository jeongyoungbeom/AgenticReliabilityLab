import { useRef } from 'react'

interface ProfileYamlInputProps {
  yaml: string
  onChange: (value: string) => void
  onError: (message: string) => void
}

const MAX_YAML_BYTES = 65_536

export function ProfileYamlInput({ yaml, onChange, onError }: ProfileYamlInputProps) {
  const inputRef = useRef<HTMLInputElement>(null)

  async function handleFile(file: File | undefined) {
    if (!file) return
    if (file.size > MAX_YAML_BYTES) {
      onError('Target Profile YAML은 64 KiB를 넘길 수 없습니다.')
      return
    }
    const value = await file.text()
    if (new TextEncoder().encode(value).byteLength > MAX_YAML_BYTES) {
      onError('Target Profile YAML은 UTF-8 기준 64 KiB를 넘길 수 없습니다.')
      return
    }
    onChange(value)
  }

  return (
    <section className="card profile-input">
      <div className="section-heading">
        <div>
          <p className="eyebrow">1. Profile 초안</p>
          <h2>Target Profile YAML</h2>
        </div>
        <button className="secondary-button" type="button" onClick={() => inputRef.current?.click()}>
          YAML 파일 불러오기
        </button>
        <input
          ref={inputRef}
          className="visually-hidden"
          type="file"
          accept=".yaml,.yml,text/yaml,application/yaml"
          onChange={(event) => void handleFile(event.target.files?.[0])}
        />
      </div>
      <p className="muted">붙여넣기 또는 파일 업로드만 허용합니다. 이 단계에서는 Target에 네트워크 요청을 보내지 않습니다.</p>
      <textarea
        aria-label="Target Profile YAML"
        spellCheck={false}
        value={yaml}
        onChange={(event) => onChange(event.target.value)}
        placeholder="arl:\n  targets:\n    registrations: ..."
        rows={18}
      />
      <p className="field-note">{new TextEncoder().encode(yaml).byteLength.toLocaleString()} / 65,536 bytes</p>
      <details className="observation-source-guide">
        <summary>명세 관측 소스 입력 도움</summary>
        <p>
          관측 소스는 명세가 임의로 고르는 값이 아니라, 사람이 승인한 Profile이 소유합니다. 수집에 실패하면 해당
          불변식은 통과가 아니라 판정 불가가 됩니다.
        </p>
        <ul>
          <li><strong>HARNESS_STATE</strong>: 상대 경로 <code>/harness/state</code>, 제공 field 목록을 입력합니다. 응답은 <code>HARNESS_STATE_V1</code> 계약이어야 합니다.</li>
          <li><strong>PROMETHEUS</strong>: 절대 URL, field 목록, field별 PromQL <code>queries</code>를 입력합니다. 쿼리는 명세가 아닌 Profile에만 둡니다.</li>
          <li><strong>TRACE</strong>: 절대 Tempo URL, field 목록, field별 TraceQL <code>queries</code>를 입력합니다. 모든 TraceQL에는 <code>{'${trial}'}</code>이 있어야 하며 Target은 <code>X-ARL-Trial</code> 값을 그 쿼리가 같은 시행을 가리키도록 쓰는 스팬 속성에 기록해야 합니다.</li>
        </ul>
      </details>
    </section>
  )
}
