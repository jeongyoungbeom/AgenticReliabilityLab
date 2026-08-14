import type { RootCauseReport } from '../../api/analysis'

export function RootCausePanel({ report }: { report: RootCauseReport }) {
  return (
    <section className="card root-cause-panel">
      <p className="eyebrow">원인 가설과 개선 제안</p>
      <h2>{report.status}</h2>
      {report.failureMessage && <p className="notice error">{report.failureMessage}</p>}
      {report.hypotheses.map((hypothesis) => (
        <article key={hypothesis.ordinal} className="advice-card">
          <p><span className="status-badge">{hypothesis.confidence}</span> 가설 {hypothesis.ordinal}</p>
          <h3>{hypothesis.title}</h3>
          <p>{hypothesis.rationale}</p>
          <p className="muted">반증 조건: {hypothesis.falsifiability}</p>
        </article>
      ))}
      {report.improvementProposals.map((proposal) => (
        <article key={proposal.ordinal} className="advice-card proposal-card">
          <p>가설 {proposal.hypothesisOrdinal} 기반 · 위험 {proposal.risk}</p>
          <h3>{proposal.title}</h3>
          <p>{proposal.proposedChange}</p>
          <p className="muted">기대 효과: {proposal.expectedEffect}</p>
        </article>
      ))}
      <p className="muted">이 화면은 제안만 표시합니다. 코드·설정·Target 또는 배포를 변경하거나 승인하지 않습니다.</p>
    </section>
  )
}
