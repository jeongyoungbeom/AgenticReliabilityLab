import { useEffect, useState } from 'react'
import { ApiClient, ApiError } from '../../api/ApiClient'
import type { PilotDiscovery } from '../../api/pilotDiscovery'

interface PilotDiscoveryPanelProps {
  api: ApiClient
  targetSystemId: string | null
  refreshKey: number
}

export function PilotDiscoveryPanel({ api, targetSystemId, refreshKey }: PilotDiscoveryPanelProps) {
  const [discovery, setDiscovery] = useState<PilotDiscovery | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setDiscovery(null)
    setMessage(null)
    if (!targetSystemId) return
    void load()
  }, [api, targetSystemId, refreshKey])

  async function load() {
    if (!targetSystemId) return
    try {
      setBusy(true)
      setMessage(null)
      setDiscovery(await api.get<PilotDiscovery>(`/api/targets/${targetSystemId}/pilot-discovery`))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  if (!targetSystemId) return null

  return (
    <section className="card pilot-discovery">
      <div className="section-heading">
        <div>
          <p className="eyebrow">4. Swagger 발견과 기본 후보</p>
          <h2>allowlist 안에서만 발견</h2>
        </div>
        <button className="secondary-button" type="button" onClick={() => void load()} disabled={busy}>새로고침</button>
      </div>
      {message && <p className="notice error">{message}</p>}
      {discovery && (
        <>
          <p className="muted">
            <code>{(discovery.openApiPaths?.length ? discovery.openApiPaths : [discovery.openApiPath]).join(', ')}</code>
            {' '}· snapshot {(discovery.snapshotChecksums?.[0] ?? discovery.snapshotChecksum).slice(0, 12)}
            {(discovery.snapshotChecksums?.length ?? 0) > 1 ? ` 외 ${(discovery.snapshotChecksums?.length ?? 1) - 1}개` : ''}
            {' '}· allowlist 밖 {discovery.ignoredOperationCount}개 제외
          </p>
          <ul className="discovered-operation-list">
            {discovery.discoveredOperations.map((operation) => (
              <li key={`${operation.method}-${operation.executionPath}-${operation.authProfile ?? 'public'}`}>
                <strong>{operation.method} {operation.executionPath}</strong>
                <small>
                  Swagger {operation.swaggerPath} · {operation.operationId ?? 'operationId 없음'} · {operation.authProfile ?? 'public'}
                </small>
              </li>
            ))}
          </ul>
          <div className="pilot-candidate-grid">
            {discovery.candidates.map((candidate) => (
              <article className={`pilot-candidate ${candidate.readiness === 'READY' ? 'ready' : 'not-ready'}`} key={candidate.id}>
                <div className="candidate-heading">
                  <h3>{candidate.title}</h3>
                  <span className={candidate.readiness === 'READY' ? 'badge ok' : 'badge warn'}>{candidate.readiness}</span>
                </div>
                <p>{candidate.description}</p>
                {candidate.operations.length > 0 && (
                  <small>{candidate.operations.map((operation) => `${operation.method} ${operation.executionPath}`).join(' → ')}</small>
                )}
                {candidate.missingOperations.length > 0 && (
                  <p className="candidate-blocker">미발견: {candidate.missingOperations.join(', ')}</p>
                )}
              </article>
            ))}
          </div>
        </>
      )}
    </section>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`
  return error instanceof Error ? error.message : 'Swagger 발견 결과를 불러오지 못했습니다.'
}
