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
    </section>
  )
}
