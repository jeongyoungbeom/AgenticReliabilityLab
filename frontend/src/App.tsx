import { useMemo, useState } from 'react'
import { AccessTokenPanel } from './auth/AccessTokenPanel'
import { ApiClient, type AccessTokens } from './api/ApiClient'
import { TargetTestWorkspace } from './features/batches/TargetTestWorkspace'
import { AnalysisWorkspace } from './features/analysis/AnalysisWorkspace'
import { SafeChatWorkbench } from './features/chat/SafeChatWorkbench'
import type { WorkbenchIntent } from './features/chat/intentParser'
import { TargetProfileWorkspace } from './features/profiles/TargetProfileWorkspace'
import { useSessionStorageState } from './hooks/useSessionStorageState'

type WorkspaceView = 'profiles' | 'batches' | 'analysis' | 'chat'

const emptyTokens: AccessTokens = { viewer: '', profileEditor: '', executor: '' }

export default function App() {
  const [tokens, setTokens] = useState<AccessTokens>(emptyTokens)
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null)
  const [selectedBatchId, setSelectedBatchId] = useSessionStorageState<string | null>('arl.selected-target-test-batch', null)
  const [view, setView] = useState<WorkspaceView>('profiles')
  const api = useMemo(() => new ApiClient(tokens), [tokens])

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
        <NavButton active={view === 'profiles'} onClick={() => setView('profiles')}>1. Target Profile</NavButton>
        <NavButton active={view === 'batches'} onClick={() => setView('batches')}>2. 안전 테스트</NavButton>
        <NavButton active={view === 'analysis'} onClick={() => setView('analysis')}>3. 분석</NavButton>
        <NavButton active={view === 'chat'} onClick={() => setView('chat')}>4. Chat</NavButton>
      </nav>

      {view === 'profiles' && (
        <TargetProfileWorkspace
          api={api}
          selectedTargetId={selectedTargetId}
          onSelectTarget={(targetId) => {
            setSelectedTargetId(targetId)
            setSelectedBatchId(null)
          }}
        />
      )}
      {view === 'batches' && (
        <TargetTestWorkspace api={api} targetSystemId={selectedTargetId} onSelectBatch={setSelectedBatchId} />
      )}
      {view === 'analysis' && <AnalysisWorkspace api={api} targetTestBatchId={selectedBatchId} />}
      {view === 'chat' && (
        <SafeChatWorkbench
          api={api}
          onIntent={(intent) => setView(viewForIntent(intent))}
          onSelectTarget={(targetId) => {
            setSelectedTargetId(targetId)
            setSelectedBatchId(null)
          }}
        />
      )}
    </main>
  )
}

function NavButton({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return <button className={active ? 'active' : undefined} type="button" onClick={onClick}>{children}</button>
}

function viewForIntent(intent: WorkbenchIntent): WorkspaceView {
  if (intent === 'TARGET_PROFILE_DRAFT') return 'profiles'
  if (intent === 'SELECT_TARGET' || intent === 'SELECT_CANDIDATES' || intent === 'OPEN_BATCH_APPROVAL') return 'batches'
  return 'analysis'
}
