export type InvariantOutcome = 'PASSED' | 'VIOLATED' | 'NOT_EVALUATED'
export type TrialOutcome = 'PASSED' | 'VIOLATED' | 'INCONCLUSIVE'
export type NotEvaluatedReason =
  | 'OBSERVATION_MISSING'
  | 'REQUIREMENT_UNMET'
  | 'OBSERVATION_INSUFFICIENT'
  | 'EXPRESSION_FAILED'
  | 'TRIAL_NOT_RUN'

type JudgementKind = 'invariant' | 'trial' | 'run'
type BadgeTone = 'ok' | 'danger' | 'warn' | 'neutral'

interface JudgementBadgeProps {
  kind: JudgementKind
  value: string
}

interface NotEvaluatedReasonBadgeProps {
  reason: string
}

interface BadgePresentation {
  label: string
  detail: string
  tone: BadgeTone
}

/**
 * The declarative-spec engine distinguishes a broken invariant from a judgement it could not make.
 *
 * This module deliberately does not accept the older Experiment `FAILED` vocabulary: a legacy execution failure and
 * a Test Spec invariant violation are different facts and must not acquire the same label by accident.
 */
export function TestSpecJudgementBadge({ kind, value }: JudgementBadgeProps) {
  const presentation = judgementPresentation(kind, value)
  return <Badge presentation={presentation} value={value} />
}

/** Shows the actionable cause that sits behind one NOT_EVALUATED verdict. */
export function NotEvaluatedReasonBadge({ reason }: NotEvaluatedReasonBadgeProps) {
  const presentation = notEvaluatedReasonPresentation(reason)
  return <Badge presentation={presentation} value={reason} />
}

export function judgementLabel(kind: JudgementKind, value: string): string {
  return judgementPresentation(kind, value).label
}

export function notEvaluatedReasonLabel(reason: string): string {
  return notEvaluatedReasonPresentation(reason).label
}

function Badge({ presentation, value }: { presentation: BadgePresentation; value: string }) {
  const toneClass = presentation.tone === 'neutral' ? '' : ` ${presentation.tone}`
  return (
    <span className={`badge judgement-badge${toneClass}`} title={presentation.detail} data-judgement={value}>
      {presentation.label}
    </span>
  )
}

function judgementPresentation(kind: JudgementKind, value: string): BadgePresentation {
  if (kind === 'invariant') return invariantPresentation(value)
  if (kind === 'run') return runPresentation(value)
  return trialPresentation(value)
}

function invariantPresentation(value: string): BadgePresentation {
  switch (value as InvariantOutcome) {
    case 'PASSED':
      return { label: '통과', detail: '이 불변식은 관측값으로 통과가 확인되었습니다.', tone: 'ok' }
    case 'VIOLATED':
      return { label: '위반', detail: '이 불변식이 관측값에서 위반되었습니다.', tone: 'danger' }
    case 'NOT_EVALUATED':
      return { label: '판정 불가', detail: '이 불변식은 통과나 위반으로 판정되지 않았습니다.', tone: 'warn' }
    default:
      return unknownPresentation(value)
  }
}

function trialPresentation(value: string): BadgePresentation {
  switch (value as TrialOutcome) {
    case 'PASSED':
      return { label: '시행 통과', detail: '이 시행의 모든 불변식이 통과했습니다.', tone: 'ok' }
    case 'VIOLATED':
      return { label: '시행 위반', detail: '이 시행에서 하나 이상의 불변식이 위반되었습니다.', tone: 'danger' }
    case 'INCONCLUSIVE':
      return { label: '시행 판정 불가', detail: '이 시행에는 판정할 수 없는 불변식이 있습니다.', tone: 'warn' }
    default:
      return unknownPresentation(value)
  }
}

function runPresentation(value: string): BadgePresentation {
  switch (value as TrialOutcome) {
    case 'PASSED':
      return { label: '실행 통과', detail: '이 실행의 시행 결과가 통과로 종합되었습니다.', tone: 'ok' }
    case 'VIOLATED':
      return { label: '실행 위반', detail: '이 실행의 시행 결과가 위반으로 종합되었습니다.', tone: 'danger' }
    case 'INCONCLUSIVE':
      return { label: '실행 판정 불가', detail: '이 실행의 시행 결과를 통과나 위반으로 종합할 수 없습니다.', tone: 'warn' }
    default:
      return unknownPresentation(value)
  }
}

function notEvaluatedReasonPresentation(reason: string): BadgePresentation {
  switch (reason as NotEvaluatedReason) {
    case 'OBSERVATION_MISSING':
      return { label: '관측 없음', detail: '필요한 관측값을 읽지 못했습니다. 관측 소스나 수집기를 확인하세요.', tone: 'warn' }
    case 'REQUIREMENT_UNMET':
      return { label: '선행 조건 미충족', detail: 'requires가 가리키는 불변식이 통과하지 않았습니다.', tone: 'warn' }
    case 'OBSERVATION_INSUFFICIENT':
      return { label: '관측 불충분', detail: '관측값은 읽었지만 판정할 만큼 충분하지 않았습니다.', tone: 'warn' }
    case 'EXPRESSION_FAILED':
      return { label: '판정식 평가 실패', detail: '명세의 조건식이 관측값으로 평가되지 않았습니다.', tone: 'warn' }
    case 'TRIAL_NOT_RUN':
      return { label: '시행 미실행', detail: '시행이 완료되지 않아 판정할 관측값이 없습니다.', tone: 'warn' }
    default:
      return unknownPresentation(reason)
  }
}

function unknownPresentation(value: string): BadgePresentation {
  return {
    label: value,
    detail: '현재 화면이 해석하지 못하는 판정 값입니다. 통과로 간주하지 마세요.',
    tone: 'neutral',
  }
}
