# Review Resolution

Task: pilot-ux-simplification
Reviewed Revision: `0424fe7` plus the 1–6-stage working tree captured by `REVIEW.md`
Applied At: `8ecbba5` plus the uncommitted review-application working tree, 2026-08-28 02:31 KST

Summary: ACCEPTED 11 / REJECTED 3 / ALREADY_RESOLVED 0 / DEFERRED 2 / STALE 0

## REV-001

Status: ACCEPTED

Reason:
`resultOutcome=PASSED`와 `status=RECOVERY_REQUIRED`가 함께 올 수 있는데 두 화면이 판정만 초록으로 보였다. 이는 D010과 Requirement 7에 어긋난다.

Changes:
- `PilotTemplateRunnerPanel.tsx`, `PilotTestSessionResultsPanel.tsx`에서 판정·상태를 별도 표시하고, 복구 필요 또는 정리 미검증 세션을 오류 스타일로 표시했다.

Verification:
- 두 컴포넌트의 `RECOVERY_REQUIRED + PASSED` 회귀 테스트가 통과했다.

## REV-002

Status: REJECTED

Reason:
재기동 시 세션 헤더가 `RECOVERY_REQUIRED`로 전환돼 완료로 오인되지 않으며, 이는 Requirement 6의 계약을 만족한다. 상세 증거는 D008에 따라 child Test Spec Run이 소유한다. 중단 시 항목을 즉시 영속하는 것은 API·트랜잭션 모델을 확장하는 별도 설계이며 현재 계약의 누락은 아니다.

Changes:
- 없음.

Verification:
- 기존 재시작 복구 영속 테스트와 단독 PostgreSQL 통합 테스트를 확인했다.

## REV-003

Status: ACCEPTED

Reason:
Run이 하나도 생성되지 않은 선택에서 공집합 `all {}` 때문에 `cleanupVerified=true`와 빈 세션 실패 사유가 저장됐다. 검증하지 않은 정리를 확인됨으로 표시하므로 D010 위반이다. 세션의 `COMPLETED`는 모든 선택을 처리한 lifecycle 상태로 유지하되, 판정은 `INCONCLUSIVE`, 정리는 `null`, 실패 사유는 첫 실패로 저장한다.

Changes:
- `PilotTemplateExecutionService.kt`가 실행된 Run이 있을 때만 정리 검증을 집계하고, 실패 항목의 사유 또는 코드를 세션에 저장하게 했다.
- `PilotTestSessionStore`와 JDBC 구현이 nullable `cleanupVerified`를 보존하게 했다.

Verification:
- Run 생성 전 실패 시 `INCONCLUSIVE / cleanupVerified=null`을 검증하는 회귀 테스트가 통과했다.

## REV-004

Status: ACCEPTED

Reason:
Target 전환 중 늦게 도착한 후보 또는 실행 응답이 새 Target 화면에 반영될 수 있었다. 다른 Target의 후보·결과를 표시하는 것은 Requirement 7에 어긋난다.

Changes:
- `PilotTemplateRunnerPanel.tsx`에 Target 스냅샷과 effect cleanup guard를 추가해 이전 Target의 load/execute 결과와 busy 상태 갱신을 버린다.

Verification:
- 이전 Target의 늦은 discovery 응답이 화면을 덮어쓰지 않는 회귀 테스트가 통과했다.

## REV-005

Status: DEFERRED

Reason:
다중 인스턴스 또는 부팅 직후의 recovery 경쟁 가능성은 실제 설계 위험이지만 D008의 명시된 단일 인스턴스 제한을 넘는다. 항목 즉시 기록·CAS·recovery cutoff을 함께 재설계해야 하므로 현재 TASK의 확인된 표시 오류 수정 범위를 벗어난다.

Changes:
- 없음.

Verification:
- 현재 구현은 이미 recovery 상태를 완료로 표시하지 않는 것을 확인했다.

## REV-006

Status: ACCEPTED

Reason:
선택한 session id가 목록에 없을 때 첫 항목으로 대체하면 다른 세션을 선택한 것처럼 보인다.

Changes:
- `PilotTestSessionResultsPanel.tsx`가 찾지 못한 선택을 `null`로 유지하고 명시적인 오류를 표시하게 했다.

Verification:
- 목록에 없는 선택 id가 첫 번째 세션을 렌더하지 않는 회귀 테스트가 통과했다.

## REV-007

Status: ACCEPTED

Reason:
예상하지 못한 예외의 원문 message가 DB와 API 응답으로 전달됐다. 내부 SQL·클래스명 또는 예상 밖의 비밀 정보가 남을 수 있어 Requirement 4와 보안 지침에 맞지 않는다.

Changes:
- `PilotTemplateExecutionService.kt`가 unknown exception에는 고정된 안전 메시지만 저장한다.

Verification:
- 내부 문자열을 포함한 예외가 고정 메시지로 치환되는 회귀 테스트가 통과했다.

## REV-008

Status: ACCEPTED

Reason:
실행 직후 화면이 세션 `RECOVERY_REQUIRED`, `cleanupVerified`, `failure`를 충분히 표시하지 않아 가장 먼저 보는 화면에서 실패를 약화했다.

Changes:
- `PilotTemplateRunnerPanel.tsx`에 세션 상태·정리 상태·실패 사유 및 오류 스타일을 추가했다.

Verification:
- 복구 필요 세션의 상태·정리 요구·실패 사유가 보이는 프런트 회귀 테스트가 통과했다.

## REV-009

Status: ACCEPTED

Reason:
기존 테스트는 성공 fixture 중심이어서 REV-001/003/004/006/014/016이 통과한 채 남을 수 있었다.

Changes:
- 백엔드 집계·안전 오류 메시지·404 테스트와 프런트의 복구 상태, 늦은 Target 응답, 없는 세션 선택, 로딩, 오류 코드, stale Run 선택 테스트를 추가했다.

Verification:
- 수정 범위의 백엔드 4개 테스트와 프런트 전체 44개 테스트가 통과했다.

## REV-010

Status: ACCEPTED

Reason:
존재하지 않는 session 조회가 409으로 매핑돼 리소스 부재를 충돌로 알렸다.

Changes:
- `findSession`이 `ResourceNotFoundException`을 던지게 변경했다.

Verification:
- 서비스 예외와 `ApiExceptionHandler`의 HTTP 404 매핑을 검증하는 회귀 테스트가 통과했다.

## REV-011

Status: DEFERRED

Reason:
최대 30개의 세션을 읽을 때의 N+1은 실제 성능 개선점이지만 현재 파일럿 계약 또는 표시 정확성을 깨지 않는다. batch 조회 포트 변경은 별도 성능 작업에서 측정과 함께 처리한다.

Changes:
- 없음.

Verification:
- 목록 상한이 30임을 확인했다.

## REV-012

Status: REJECTED

Reason:
세션 항목의 `completedAt`은 세션 요약 시각이며, 상세 실행 타임라인은 D008에 따라 child Test Spec Run이 소유한다. 이 요약 필드에 후보별 시각을 복제하지 않는 현재 모델은 의도된 경계다.

Changes:
- 없음.

Verification:
- `PilotTestSessionItem`과 child Run 증거 소유 경계를 확인했다.

## REV-013

Status: REJECTED

Reason:
repository의 완료 UPDATE와 item INSERT를 한 트랜잭션으로 묶는 것은 원자성에 필요하다. 이를 Service로 올리면 외부 Target 호출까지 장기 트랜잭션에 넣게 되므로 현재 경계가 더 안전하다. 이 저장소에는 해당 "Service만 transaction" 계약도 없다.

Changes:
- 없음.

Verification:
- JDBC `complete()`의 원자적 UPDATE/INSERT 경계를 확인했다.

## REV-014

Status: ACCEPTED

Reason:
`failureMessage`가 null이고 `failureCode`만 있을 때 화면에서 오류 코드까지 사라졌다. 실패를 숨길 수 있으므로 Requirement 7 위반이다.

Changes:
- 두 결과 화면이 message 또는 code 중 하나만 있어도 고정 fallback과 함께 표시하게 했다.

Verification:
- code-only recovery 항목이 표시되는 프런트 회귀 테스트가 통과했다.

## REV-015

Status: ACCEPTED

Reason:
dead front API helper, Run id의 이중 sessionStorage 소유, 세션을 열 때 남는 이전 Run, RUNNING 세션 미갱신, 선택 버튼의 접근성 정보 부재를 확인했다. 중복 discovery GET은 stateless read라 이번 표시 정확성 수정과 별개로 유지했다.

Changes:
- 사용되지 않는 `findPilotTestSession` helper를 제거했다.
- `App.tsx`를 단일 Run id 저장소로 두고, 세션 열기 시 이전 Run 선택을 지운다.
- RUNNING 세션을 2초 후 다시 조회하고, session 선택 버튼에 `aria-pressed` 및 생성 시각을 포함한 접근명을 추가했다.

Verification:
- 세션이 Run 선택을 대체하면 이전 Run 결과·입력이 사라지는 회귀 테스트가 통과했다.

## REV-016

Status: ACCEPTED

Reason:
최초/Target 전환 렌더에서 목록 요청이 진행 중이어도 빈 상태 문구가 먼저 표시될 수 있었다. 저장되지 않았다는 잘못된 인상을 줄 수 있다.

Changes:
- `PilotTestSessionResultsPanel.tsx`에 Target별 load 완료 상태를 두고 진행 중 문구를 렌더한다.

Verification:
- pending 요청에서 빈 상태 대신 로딩 문구가 표시되는 회귀 테스트가 통과했다.
