import type { FailureDiagnosis } from '../api/ApiClient'

interface FailureDiagnosisDetailsProps {
  diagnosis: FailureDiagnosis | null | undefined
}

const STAGE_LABELS: Record<FailureDiagnosis['stage'], string> = {
  CREDENTIALS: '자격증명', PREFLIGHT: '사전 확인', EXECUTION: '실행', CLEANUP: '정리',
  RECOVERY: '복구', CONFIGURATION: '설정', SYSTEM: '시스템',
}

/** Keeps the action a user can take visible while leaving stable diagnostic codes as optional supporting detail. */
export function FailureDiagnosisDetails({ diagnosis }: FailureDiagnosisDetailsProps) {
  if (!diagnosis) return null
  return (
    <section className="failure-diagnosis" aria-label="오류 진단">
      <strong>{STAGE_LABELS[diagnosis.stage]} 단계 진단</strong>
      <p><strong>설명:</strong> {diagnosis.summary}</p>
      <p><strong>예상 원인:</strong> {diagnosis.likelyCause}</p>
      <p><strong>다음 행동:</strong> {diagnosis.nextAction}</p>
      <details>
        <summary>기술 정보</summary>
        <code>{diagnosis.technicalDetail}</code>
      </details>
    </section>
  )
}
