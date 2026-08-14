import { useEffect, useState } from 'react'
import { ApiClient } from '../../api/ApiClient'
import type { TargetProfile } from '../../api/targetProfile'
import { parseWorkbenchIntent, type WorkbenchIntent } from './intentParser'

interface SafeChatWorkbenchProps {
  api: ApiClient
  onIntent: (intent: WorkbenchIntent) => void
  onSelectTarget: (targetSystemId: string) => void
}

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  intent?: WorkbenchIntent
}

export function SafeChatWorkbench({ api, onIntent, onSelectTarget }: SafeChatWorkbenchProps) {
  const [profiles, setProfiles] = useState<TargetProfile[]>([])
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([{
    role: 'assistant',
    text: '안전한 화면 이동만 도와드릴 수 있습니다. 예: “Profile 등록”, “테스트 후보”, “single GPT 분석”, “원인 결과”',
  }])

  useEffect(() => {
    void api.get<TargetProfile[]>('/api/target-profiles').then(setProfiles).catch(() => setProfiles([]))
  }, [api])

  function send() {
    if (!input.trim()) return
    const parsed = parseWorkbenchIntent(input, profiles)
    setMessages((current) => [
      ...current,
      { role: 'user', text: input },
      { role: 'assistant', text: parsed.response, intent: parsed.intent },
    ])
    setInput('')
    if (parsed.targetSystemId) onSelectTarget(parsed.targetSystemId)
    if (parsed.intent !== 'UNSUPPORTED') onIntent(parsed.intent)
  }

  return (
    <section className="card chat-workbench">
      <p className="eyebrow">제한형 Chat</p>
      <h2>화면 상태만 안내합니다</h2>
      <p className="muted">이 Chat은 Tool 실행 Agent가 아닙니다. Target HTTP, Shell, Docker, DB, 코드 변경, 승인 요청을 보내지 않습니다.</p>
      <ol className="chat-history">
        {messages.map((message, index) => (
          <li key={`${message.role}-${index}`} className={message.role}>
            <strong>{message.role === 'user' ? '나' : '워크벤치'}</strong>
            <p>{message.text}</p>
            {message.intent && <code>{message.intent}</code>}
          </li>
        ))}
      </ol>
      <div className="chat-input-row">
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') send() }}
          placeholder="원하는 화면을 말해보세요"
        />
        <button type="button" onClick={send}>보내기</button>
      </div>
    </section>
  )
}
