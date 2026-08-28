/**
 * One vocabulary for `cleanupVerified` across every screen.
 *
 * The three states are distinct facts (D010): verified, not verified, and nothing to verify because no run was
 * made. The run panel and the session results panel used to word the same value differently, which read as two
 * different states for one session.
 */
export function cleanupLabel(cleanupVerified: boolean | null | undefined): string {
  if (cleanupVerified === true) return '확인됨'
  if (cleanupVerified === false) return '미확인'
  return '검증 대상 없음'
}
