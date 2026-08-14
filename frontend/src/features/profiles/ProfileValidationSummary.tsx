import type { TargetProfileValidation } from '../../api/targetProfile'

interface ProfileValidationSummaryProps {
  validation: TargetProfileValidation | null
}

export function ProfileValidationSummary({ validation }: ProfileValidationSummaryProps) {
  if (!validation) {
    return <p className="empty-state">검증을 실행하면 등록될 Target과 안전 정책 요약이 표시됩니다.</p>
  }

  return (
    <dl className="summary-list">
      <div><dt>Target</dt><dd>{validation.targetName} ({validation.targetSystemId})</dd></div>
      <div><dt>Environment</dt><dd>{validation.environment}</dd></div>
      <div><dt>GET 후보</dt><dd>{validation.readOnlyOperationCount}개</dd></div>
      <div><dt>Generic HTTP 실행</dt><dd>{validation.genericHttpEnabled ? '허용' : '비활성'}</dd></div>
      <div><dt>Experiment Profile</dt><dd>{validation.experimentProfilePresent ? '포함' : '없음'}</dd></div>
    </dl>
  )
}
