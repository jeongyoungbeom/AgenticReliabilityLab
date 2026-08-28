import { describe, expect, it } from 'vitest'
import { cleanupLabel } from './cleanupStatus'

describe('cleanupLabel', () => {
  it('gives the three cleanup states one wording for every screen', () => {
    expect(cleanupLabel(true)).toBe('확인됨')
    expect(cleanupLabel(false)).toBe('미확인')
    // Nothing was run, so there is nothing to verify. It must not read as verified, and it must not read as a
    // missing value either: the run panel and the results panel used to disagree on exactly this state.
    expect(cleanupLabel(null)).toBe('검증 대상 없음')
    expect(cleanupLabel(null)).toBe(cleanupLabel(undefined))
  })
})
