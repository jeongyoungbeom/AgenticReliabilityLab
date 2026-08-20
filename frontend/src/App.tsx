import { useMemo, useState } from 'react'
import { AccessTokenPanel } from './auth/AccessTokenPanel'
import { ApiClient, type AccessTokens } from './api/ApiClient'
import { TargetTestWorkspace } from './features/batches/TargetTestWorkspace'
import { AnalysisWorkspace } from './features/analysis/AnalysisWorkspace'
import { SafeChatWorkbench } from './features/chat/SafeChatWorkbench'
import type { WorkbenchIntent } from './features/chat/intentParser'
import { TargetProfileWorkspace } from './features/profiles/TargetProfileWorkspace'
import { KnowledgeWorkspace } from './features/knowledge/KnowledgeWorkspace'
import { CandidateWorkspace } from './features/candidates/CandidateWorkspace'
import { TestPlanWorkspace } from './features/plans/TestPlanWorkspace'
import { ExperimentResultWorkspace } from './features/experiments/ExperimentResultWorkspace'
import type { TestCandidateGeneration } from './api/testCandidates'
import { SectionNav } from './components/SectionNav'
import { useSessionStorageState } from './hooks/useSessionStorageState'

type WorkspaceView = 'profiles' | 'batches' | 'analysis' | 'chat'
type ProfileSection = 'profile' | 'knowledge'
type TestSection = 'candidates' | 'plans' | 'batches' | 'experiments'

const TEST_SECTIONS = [
  { id: 'candidates', title: '테스트 후보' },
  { id: 'plans', title: 'Test Plan' },
  { id: 'batches', title: '즉시 실행' },
  { id: 'experiments', title: '실험 결과' },
] as const satisfies ReadonlyArray<{ id: TestSection; title: string }>

const PROFILE_SECTIONS = [
  { id: 'profile', title: 'Profile 등록' },
  { id: 'knowledge', title: 'Target 이해 모델' },
] as const satisfies ReadonlyArray<{ id: ProfileSection; title: string }>

const emptyTokens: AccessTokens = { viewer: '', profileEditor: '', executor: '' }

export default function App() {
  const [tokens, setTokens] = useState<AccessTokens>(emptyTokens)
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null)
  const [selectedBatchId, setSelectedBatchId] = useSessionStorageState<string | null>('arl.selected-target-test-batch', null)
  const [view, setView] = useState<WorkspaceView>('profiles')
  const [profileSection, setProfileSection] = useState<ProfileSection>('profile')
  const [testSection, setTestSection] = useState<TestSection>('candidates')
  const [selectedCandidateIds, setSelectedCandidateIds] = useState<string[]>([])
  const [generation, setGeneration] = useState<TestCandidateGeneration | null>(null)
  // The plan id outlives a reload, so switching Target has to drop it too; otherwise the Plan screen would show a
  // plan belonging to a Target the user has left.
  const [, setStoredPlanId] = useSessionStorageState<string | null>('arl.test-plan-id', null)
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
        <>
          <SectionNav
            label="Target Profile 하위 단계"
            sections={PROFILE_SECTIONS}
            active={profileSection}
            onSelect={setProfileSection}
          />
          {profileSection === 'profile' && (
            <TargetProfileWorkspace
              api={api}
              selectedTargetId={selectedTargetId}
              onSelectTarget={(targetId) => {
                setSelectedTargetId(targetId)
                setSelectedBatchId(null)
                setSelectedCandidateIds([])
            setGeneration(null)
            setStoredPlanId(null)
              }}
            />
          )}
          {profileSection === 'knowledge' && (
            <KnowledgeWorkspace api={api} targetSystemId={selectedTargetId} />
          )}
        </>
      )}
      {view === 'batches' && (
        <>
          <SectionNav
            label="안전 테스트 하위 단계"
            sections={TEST_SECTIONS}
            active={testSection}
            onSelect={setTestSection}
          />
          {testSection === 'candidates' && (
            <CandidateWorkspace
              api={api}
              targetSystemId={selectedTargetId}
              selectedCandidateIds={selectedCandidateIds}
              onSelectionChange={setSelectedCandidateIds}
              onGenerationLoaded={setGeneration}
            />
          )}
          {testSection === 'plans' && (
            <TestPlanWorkspace
              api={api}
              generation={generation}
              selectedCandidateIds={selectedCandidateIds}
              onDispatched={(batchId) => {
                setSelectedBatchId(batchId)
                setTestSection('batches')
              }}
            />
          )}
          {testSection === 'batches' && (
            <TargetTestWorkspace api={api} targetSystemId={selectedTargetId} onSelectBatch={setSelectedBatchId} />
          )}
          {testSection === 'experiments' && <ExperimentResultWorkspace api={api} />}
        </>
      )}
      {view === 'analysis' && <AnalysisWorkspace api={api} targetTestBatchId={selectedBatchId} />}
      {view === 'chat' && (
        <SafeChatWorkbench
          api={api}
          onIntent={(intent) => {
            const destination = destinationForIntent(intent)
            setView(destination.view)
            if (destination.testSection) setTestSection(destination.testSection)
          }}
          onSelectTarget={(targetId) => {
            setSelectedTargetId(targetId)
            setSelectedBatchId(null)
            setSelectedCandidateIds([])
                setGeneration(null)
          }}
        />
      )}
    </main>
  )
}

function NavButton({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return <button className={active ? 'active' : undefined} type="button" onClick={onClick}>{children}</button>
}

/**
 * Chat intents predate the Phase 11-15 screens, so they must keep landing on the flow they were written for.
 *
 * "Select candidates" means the read-only Batch candidates, not the generated test catalogue, and sending the user to
 * the wrong sub-screen would silently change what their sentence did.
 */
function destinationForIntent(intent: WorkbenchIntent): { view: WorkspaceView; testSection?: TestSection } {
  if (intent === 'TARGET_PROFILE_DRAFT') return { view: 'profiles' }
  if (intent === 'SELECT_TARGET' || intent === 'SELECT_CANDIDATES' || intent === 'OPEN_BATCH_APPROVAL') {
    return { view: 'batches', testSection: 'batches' }
  }
  return { view: 'analysis' }
}
