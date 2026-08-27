import { useMemo, useState } from 'react'
import { AccessTokenPanel } from './auth/AccessTokenPanel'
import { ApiClient, type AccessTokens } from './api/ApiClient'
import { TargetProfileWorkspace } from './features/profiles/TargetProfileWorkspace'
import { TestSpecRunWorkspace } from './features/specifications/TestSpecRunWorkspace'
import { useSessionStorageState } from './hooks/useSessionStorageState'
import type { TargetCredentialPreflightResult, TargetRuntimeCredentialStatus } from './api/targetCredentials'

type WorkspaceView = 'tests' | 'results'

const emptyTokens: AccessTokens = { viewer: '', profileEditor: '', executor: '' }

export default function App() {
  const [tokens, setTokens] = useState<AccessTokens>(emptyTokens)
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null)
  const [targetCredentialStatus, setTargetCredentialStatus] = useState<TargetRuntimeCredentialStatus | null>(null)
  const [targetCredentialPreflight, setTargetCredentialPreflight] = useState<TargetCredentialPreflightResult[]>([])
  // YAML contains no Target credentials, so retaining the draft during browser navigation is safe and intentional.
  const [targetProfileYaml, setTargetProfileYaml] = useSessionStorageState<string | null>('arl.target-profile-yaml-draft', null)
  const [view, setView] = useState<WorkspaceView>('tests')
  const [selectedTestSpecRunId, setSelectedTestSpecRunId] = useSessionStorageState<string | null>('arl.test-spec-run-id', null)
  const [selectedPilotTestSessionId, setSelectedPilotTestSessionId] = useSessionStorageState<string | null>('arl.pilot-test-session-id', null)
  const [endedMessage, setEndedMessage] = useState<{ text: string; failed: boolean } | null>(null)
  const api = useMemo(() => new ApiClient(tokens), [tokens])

  function selectTarget(targetId: string) {
    const targetChanged = targetId !== selectedTargetId
    setSelectedTargetId(targetId)
    // A new Profile version may keep the same Target id while changing its Harness state endpoint. A preflight is
    // therefore valid only for the exact selection event that produced it, not indefinitely for the Target id.
    setTargetCredentialPreflight([])
    if (targetChanged) {
      setTargetCredentialStatus(null)
      setSelectedTestSpecRunId(null)
      setSelectedPilotTestSessionId(null)
      setEndedMessage(null)
    }
  }

  /**
   * Ends the working session: the server-side credential session first, then everything the browser holds.
   *
   * The server call is unconditional. A reloaded page has no selected Target and no loaded status, but its cookie
   * still points at live credentials, so gating the call on either would leave them behind while telling the user
   * they were cleared. Failure is reported as failure instead of being hidden behind a cleared screen.
   */
  async function endSession() {
    let ended: { text: string; failed: boolean } = {
      text: '세션을 종료했습니다. Target 자격증명, YAML 초안, 선택한 Target을 모두 지웠습니다.',
      failed: false,
    }
    try {
      await api.delete('/api/target-credential-session', 'executor')
    } catch {
      ended = {
        text: '브라우저 상태는 지웠지만 ARL 런타임 자격증명 삭제 요청이 실패했습니다. ARL을 재시작하면 사라집니다.',
        failed: true,
      }
    }
    setSelectedTargetId(null)
    setTargetCredentialStatus(null)
    setTargetCredentialPreflight([])
    setTargetProfileYaml(null)
    setSelectedTestSpecRunId(null)
    setSelectedPilotTestSessionId(null)
    setView('tests')
    setEndedMessage(ended)
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="product-name">Agentic Reliability Lab</p>
          <h1>안전한 신뢰성 테스트 워크벤치</h1>
        </div>
        <p className="selected-target">{selectedTargetId ? `선택한 Target: ${selectedTargetId}` : 'Target을 등록하거나 선택하세요'}</p>
      </header>

      <AccessTokenPanel tokens={tokens} onChange={setTokens} />

      <nav className="workspace-nav" aria-label="워크벤치 단계">
        <NavButton active={view === 'tests'} onClick={() => setView('tests')}>테스트</NavButton>
        <NavButton active={view === 'results'} onClick={() => setView('results')}>결과</NavButton>
        <button type="button" className="end-session-button" onClick={() => void endSession()}>세션 종료</button>
      </nav>

      {endedMessage && (
        <p className={endedMessage.failed ? 'notice error' : 'notice success'}>{endedMessage.text}</p>
      )}

      {view === 'tests' && (
        <TargetProfileWorkspace
          api={api}
          selectedTargetId={selectedTargetId}
          onSelectTarget={selectTarget}
          onOpenRun={(runId) => {
            setSelectedTestSpecRunId(runId)
            setView('results')
          }}
          onOpenSession={(sessionId) => {
            setSelectedPilotTestSessionId(sessionId)
            setView('results')
          }}
          credentialStatus={targetCredentialStatus}
          onCredentialStatusChange={setTargetCredentialStatus}
          credentialPreflight={targetCredentialPreflight}
          onCredentialPreflightChange={setTargetCredentialPreflight}
          yaml={targetProfileYaml ?? ''}
          onYamlChange={setTargetProfileYaml}
        />
      )}
      {view === 'results' && (
        <TestSpecRunWorkspace
          api={api}
          selectedTargetId={selectedTargetId}
          selectedPilotTestSessionId={selectedPilotTestSessionId}
          onSelectPilotTestSession={setSelectedPilotTestSessionId}
          selectedRunId={selectedTestSpecRunId}
          onSelectRun={setSelectedTestSpecRunId}
        />
      )}
    </main>
  )
}

function NavButton({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return <button className={active ? 'active' : undefined} type="button" onClick={onClick}>{children}</button>
}
