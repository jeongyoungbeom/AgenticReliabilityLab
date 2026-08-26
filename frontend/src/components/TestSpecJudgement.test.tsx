import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  NotEvaluatedReasonBadge,
  TestSpecJudgementBadge,
  judgementLabel,
  notEvaluatedReasonLabel,
} from './TestSpecJudgement'

describe('TestSpecJudgement', () => {
  it.each([
    ['PASSED', '통과', 'ok'],
    ['VIOLATED', '위반', 'danger'],
    ['NOT_EVALUATED', '판정 불가', 'warn'],
  ])('invariant %s를 %s로 구분한다', (value, label, tone) => {
    render(<TestSpecJudgementBadge kind="invariant" value={value} />)

    const badge = screen.getByText(label)
    expect(badge).toHaveClass('judgement-badge', tone)
    expect(badge).toHaveAttribute('data-judgement', value)
  })

  it.each([
    ['PASSED', '시행 통과', 'ok'],
    ['VIOLATED', '시행 위반', 'danger'],
    ['INCONCLUSIVE', '시행 판정 불가', 'warn'],
  ])('trial %s를 %s로 구분한다', (value, label, tone) => {
    render(<TestSpecJudgementBadge kind="trial" value={value} />)

    const badge = screen.getByText(label)
    expect(badge).toHaveClass('judgement-badge', tone)
    expect(badge).toHaveAttribute('data-judgement', value)
  })

  it.each([
    ['PASSED', '실행 통과', 'ok'],
    ['VIOLATED', '실행 위반', 'danger'],
    ['INCONCLUSIVE', '실행 판정 불가', 'warn'],
  ])('run %s를 시행 결과와 구분한다', (value, label, tone) => {
    render(<TestSpecJudgementBadge kind="run" value={value} />)

    expect(screen.getByText(label)).toHaveClass('judgement-badge', tone)
  })

  it.each([
    ['OBSERVATION_MISSING', '관측 없음'],
    ['REQUIREMENT_UNMET', '선행 조건 미충족'],
    ['OBSERVATION_INSUFFICIENT', '관측 불충분'],
    ['EXPRESSION_FAILED', '판정식 평가 실패'],
    ['TRIAL_NOT_RUN', '시행 미실행'],
  ])('%s 원인을 별도로 표시한다', (reason, label) => {
    render(<NotEvaluatedReasonBadge reason={reason} />)

    const badge = screen.getByText(label)
    expect(badge).toHaveClass('judgement-badge', 'warn')
    expect(badge).toHaveAttribute('data-judgement', reason)
    expect(badge).toHaveAttribute('title')
  })

  it('알 수 없는 값도 통과 색으로 표시하지 않는다', () => {
    render(<TestSpecJudgementBadge kind="invariant" value="FUTURE_STATUS" />)

    const badge = screen.getByText('FUTURE_STATUS')
    expect(badge).toHaveClass('judgement-badge')
    expect(badge).not.toHaveClass('ok', 'danger', 'warn')
    expect(badge).toHaveAttribute('title', '현재 화면이 해석하지 못하는 판정 값입니다. 통과로 간주하지 마세요.')
  })

  it('재사용할 라벨 헬퍼도 같은 어휘를 반환한다', () => {
    expect(judgementLabel('invariant', 'VIOLATED')).toBe('위반')
    expect(judgementLabel('trial', 'INCONCLUSIVE')).toBe('시행 판정 불가')
    expect(judgementLabel('run', 'INCONCLUSIVE')).toBe('실행 판정 불가')
    expect(notEvaluatedReasonLabel('EXPRESSION_FAILED')).toBe('판정식 평가 실패')
  })
})
