# Independent Review

Task: pilot-ux-simplification
Base Commit: `0424fe7` (`fix: align SideProject pilot setup with harness overlay`)
Current HEAD: `0424fe7`
Working Tree Included: **yes** — UX 단순화 1–6단계 전체가 미커밋 상태이며 이 리뷰의 대상이다.
Relevant Diff: 6단계(파일럿 테스트 세션 저장 모델) 신규·변경분 중심.
`targetdiscovery/{domain,application,application/port,api,infrastructure}`,
`src/main/resources/db/migration/V29__pilot_test_sessions.sql`,
`frontend/src/api/pilotTemplates.ts`,
`frontend/src/features/specifications/{PilotTestSessionResultsPanel,TestSpecRunWorkspace}.tsx`,
`frontend/src/features/profiles/PilotTemplateRunnerPanel.tsx`, `frontend/src/App.tsx` 및 각 테스트.

Reviewed At: 2026-08-27
Method: 범위가 겹치지 않는 두 리뷰어(백엔드 / 프런트)가 코드부터 독립 검토했고, 주 세션이 각 지적을
실제 코드에서 다시 확인한 뒤 중복을 합쳐 기록했다. 1–5단계는 이전 세션에서 리뷰·반영이 끝났으므로 제외한다.

검증 상태 참고: 이 리뷰 시점의 자동 검증은 통과 상태였다(백엔드 343 tests / detekt 0, 프런트 38 tests, 빌드 성공).
**아래 finding은 전부 "빌드와 테스트가 통과하는데도 잘못된 것"이다.**

모든 finding의 `Status`는 `OPEN`이다. 판정은 `RESOLUTION.md`에서 한다.

---

## REV-001

Severity: Critical
Status: OPEN

### Location

`frontend/src/features/specifications/PilotTestSessionResultsPanel.tsx` (항목 뱃지 렌더),
`frontend/src/features/profiles/PilotTemplateRunnerPanel.tsx` (실행 직후 항목 목록)

### Problem

항목 뱃지가 `resultOutcome`만 보고 색과 라벨을 정한다. `outcome.resultOutcome ?? outcome.status` 구조라
`resultOutcome`이 있으면 `status`는 화면에 렌더되지 않는다.

### Trigger

불변식은 모두 통과했지만 정리(cleanup) 검증에 실패한 Run.

### Impact

`JdbcTestSpecRunRepository.kt:95`가 `status = if (outcome.cleanupVerified) COMPLETED else RECOVERY_REQUIRED`로
정하므로, 이 Run은 `status=RECOVERY_REQUIRED, resultOutcome=PASSED, cleanupVerified=false`가 된다.
사용자 화면에는 **초록 PASSED 뱃지**만 뜨고 복구가 필요하다는 사실이 사라진다.
Target에 정리되지 않은 상태가 남았는데 화면은 통과라고 말한다. `DECISIONS.md` D010 위반이다.

### Evidence

`JdbcTestSpecRunRepository.kt:95`에서 상태 규칙을 확인했고, 두 컴포넌트의 렌더 코드에서
`status`가 표시될 경로가 없음을 확인했다.

### Recommendation

판정(`resultOutcome`)과 항목 상태(`status`)를 별도 뱃지로 렌더하고, `status !== 'COMPLETED'`이면
판정 값과 무관하게 경고색을 강제한다.

---

## REV-002

Severity: Critical
Status: OPEN

### Location

`targetdiscovery/application/PilotTemplateExecutionService.kt` (`completeSession`),
`targetdiscovery/infrastructure/JdbcPilotTestSessionRepository.kt` (`complete`),
`targetdiscovery/infrastructure/sql/PilotTestSessionSql.kt` (`RECOVER_RUNNING`)

### Problem

`pilot_test_session_item`은 모든 후보 실행이 끝난 뒤 `complete()` 한 번에만 INSERT된다.
재기동 복구는 세션 헤더만 갱신하므로 중단된 세션은 **항목 0건**으로 남는다.

### Trigger

후보 여러 개를 실행하는 도중 ARL이 재기동되거나 프로세스가 죽는 경우.

### Impact

세션은 `RECOVERY_REQUIRED`가 되지만 `outcomes`가 빈 배열이라, 어떤 후보가 이미 Target을 변경했는지
API로 확인할 수 없다. 자식 Run은 `"<세션키>:<후보id>"` 형태의 idempotency key로만 추적 가능한데
세션 응답은 그 키를 노출하지 않는다. 복구가 가장 필요한 상황에서 증거가 없다.

### Evidence

`complete()`가 세션 UPDATE 성공 후에만 항목을 INSERT하는 것을 확인했고,
`RECOVER_RUNNING`이 `pilot_test_session`만 UPDATE하는 것을 확인했다.

### Recommendation

후보 하나가 끝날 때마다 항목을 즉시 기록하고, `complete()`는 세션 헤더 갱신만 담당하도록 분리한다.

---

## REV-003

Severity: Critical
Status: OPEN

### Location

`targetdiscovery/application/PilotTemplateExecutionService.kt` (`completeSession`의 `cleanupVerified` 계산),
표시 지점은 `PilotTestSessionResultsPanel.tsx`와 `PilotTemplateRunnerPanel.tsx`

### Problem

`items.filter { it.testSpecRunId != null }.all { it.cleanupVerified == true }`는 대상이 없으면 `true`다.
같은 계산에서 세션 `status`는 `COMPLETED`, `failure`는 `null`이 된다.

### Trigger

선택한 모든 후보가 Run 생성 전에 실패하는 경우. 예: 같은 Target에 진행 중이거나 복구가 필요한 실행이 있어
후보마다 `TEST_SPECIFICATION_RECOVERY_REQUIRED`가 던져지는 상황(`TestSpecificationService.kt:363`).

### Impact

아무것도 실행되지 않았는데 저장 결과가 `COMPLETED / INCONCLUSIVE / cleanupVerified=true / failure=null`이 된다.
화면 헤더는 "정리 검증: 확인됨", 항목 줄은 "cleanup REQUIRED"로 서로 모순된다.
검증하지 않은 것을 검증했다고 보고하는 것이라 D010 위반이며, 저장된 데이터 자체가 틀린다.

### Evidence

`all {}`의 공허참을 코드에서 확인했고, `TestSpecificationService.kt:363`의 실행 슬롯 거부 경로와
`executeOne`의 `ClientRequestException` catch 경로로 이 조합이 실제로 만들어짐을 확인했다.

### Recommendation

검증 대상이 없으면 `cleanupVerified`를 `null`로 둔다. 성공 항목이 0건인 세션을 `COMPLETED`로 부르지 않고
세션 `failure`에 첫 실패 원인을 채운다. 프런트도 `null`을 "확인됨"으로 표시하지 않는다.

---

## REV-004

Severity: Major
Status: OPEN

### Location

`frontend/src/features/profiles/PilotTemplateRunnerPanel.tsx` (Target 변경 effect, `load()`, `execute()`)

### Problem

effect가 정리 함수를 반환하지 않고, `load()`/`execute()` 어디에도 취소 플래그나 Target 스냅샷이 없다.
같은 저장소의 `TestSpecRunWorkspace`와 `PilotTestSessionResultsPanel`은 이 가드를 갖고 있다.

### Trigger

후보 조회나 실행 POST가 진행 중일 때 사용자가 다른 Target을 선택하는 경우.

### Impact

(1) A의 후보 목록이 B의 실행 후보로 표시되고, 실행하면 B의 엔드포인트에 A의 candidateIds가 실린다.
(2) A의 실행 결과가 B 화면에 붙고, `세션 결과 보기`가 B 목록에 없는 A의 세션 id를 넘긴다(REV-006과 연쇄).
1–5단계 리뷰에서 다른 컴포넌트에 대해 이미 고쳤던 결함이 이 파일에만 남아 있다.

### Evidence

파일 전체를 읽어 취소 가드가 없음을 확인했다. 형제 컴포넌트의 `current` 플래그 사용도 확인했다.

### Recommendation

effect에 정리 함수를 두고, `execute()`는 시작 시점의 `targetSystemId`를 스냅샷해 resolve 시 현재 값과
다르면 결과를 버린다.

---

## REV-005

Severity: Major
Status: OPEN

### Location

`PilotTestSessionSql.kt`의 `RECOVER_RUNNING` (`where status = :running`),
`JdbcPilotTestSessionRepository.complete()` (`if (updated != 1) return false`, 항목 INSERT 이전),
`PilotTemplateExecutionService.completeSession()`의 `check(...)`

### Problem

복구 UPDATE에 Target·시각·인스턴스 조건이 없다. 다른 주체가 세션을 `RUNNING`에서 내리면,
실행을 끝낸 스레드의 세션 UPDATE가 0행이 되고 **항목을 기록하지도 못한 채** `IllegalStateException`으로 끝난다.

### Trigger

같은 DB에 ARL 두 대가 붙는 경우. 또는 단일 인스턴스라도 기동 직후 — `ApplicationRunner`는 웹 서버가
요청을 받기 시작한 뒤 실행되므로, 그 사이에 생성된 세션을 복구 러너가 즉시 내리는 좁은 창이 있다.

### Impact

실제로 완료된 실행 결과가 통째로 유실되고 사용자에게는 500이 나간다.
단일 인스턴스 전제는 `DECISIONS.md` D008에 기록돼 있지만, 전제가 깨졌을 때의 결과가
"표시 왜곡"이 아니라 "결과 전량 유실"이라는 점은 남아 있지 않다.

### Evidence

세 지점의 코드를 읽어 확인했다. 기동 직후 창은 구조상 가능성이며 실측하지 않았다.

### Recommendation

복구 UPDATE를 부팅 시각 이전에 생성된 세션으로 좁히고, 세션 상태 CAS 실패와 항목 기록을 분리해
결과가 먼저 남게 한다.

---

## REV-006

Severity: Major
Status: OPEN

### Location

`frontend/src/features/specifications/PilotTestSessionResultsPanel.tsx`
(`sessions.find(...) ?? sessions[0] ?? null`)

### Problem

선택한 세션 id가 목록에 없으면 조용히 첫 번째 세션으로 대체되고, 그 버튼에 선택 표시까지 붙는다.
사용자에게도 상위 상태에도 "찾지 못했다"는 사실이 전달되지 않는다.

### Trigger

다른 Target의 세션 id가 남아 있는 경우(REV-004 연쇄), 또는 목록 상한(최근 30개)을 넘겨
이전에 고른 세션이 밀려난 경우.

### Impact

사용자가 A 세션을 열었다고 믿는 화면에 B 세션의 판정이 표시된다.

### Evidence

해당 표현식과, 선택 표시가 대체된 세션에 붙는 렌더 경로를 확인했다.

### Recommendation

찾지 못하면 `null`을 유지하고 그 사실을 표시한다. 또는 이미 존재하지만 사용되지 않는
`findPilotTestSession`으로 id 기준 단건 조회를 한다(REV-015 참조).

---

## REV-007

Severity: Major
Status: OPEN

### Location

`targetdiscovery/application/PilotTemplateExecutionService.kt` (`executeOne`의 `catch (exception: Exception)`)

### Problem

임의 예외의 `message`를 그대로 항목 `failureMessage`에 담아 DB에 저장하고 뷰어 응답으로 반환한다.

### Trigger

`specifications.execute` 경로에서 예상하지 못한 예외(예: `DataAccessException`)가 발생하는 경우.

### Impact

SQL 문, 테이블명, 드라이버 내부 문자열이 영구 저장되고 API로 노출된다.
개인 컨벤션("예외 메시지에 SQL·스택트레이스·내부 클래스명을 노출하지 않는다") 위반이다.
자격증명 누출은 확인한 범위에서 없었다(Target 토큰은 세션·항목·응답 어디에도 저장되지 않는다).

### Evidence

catch 블록과 `PilotTestSessionItem.failureMessage` → 응답 DTO 경로를 확인했다.

### Recommendation

알려진 예외만 코드로 매핑하고 나머지는 고정 메시지 + correlation id만 남긴다.

---

## REV-008

Severity: Major
Status: OPEN

### Location

`frontend/src/features/profiles/PilotTemplateRunnerPanel.tsx` (실행 직후 세션 요약)

### Problem

세션 레벨 `status === 'RECOVERY_REQUIRED'` 분기가 없고 `result.failure`를 렌더하는 코드가 없다.
`resultOutcome === 'PASSED'`가 아니면 전부 같은 노란 한 줄이다.

### Trigger

세션이 `RECOVERY_REQUIRED`로 끝나는 경우.

### Impact

같은 데이터를 결과 패널과 Run 워크스페이스는 빨간 복구 배너로 알리는데,
사용자가 가장 먼저 보는 화면이 가장 약하게 경고하고 실패 원인은 아예 보여 주지 않는다.

### Evidence

해당 컴포넌트에 `failure` 참조가 없음을 확인했고, 다른 두 화면의 복구 배너를 확인했다.

### Recommendation

`status === 'RECOVERY_REQUIRED'`이거나 `cleanupVerified !== true`면 오류 스타일로 올리고
`failure`를 함께 표시한다.

---

## REV-009

Severity: Major
Status: OPEN

### Location

`frontend/src/features/specifications/PilotTestSessionResultsPanel.test.tsx`,
`frontend/src/features/profiles/PilotTemplateRunnerPanel.test.tsx`,
`src/test/.../targetdiscovery/application/PilotTestSessionPersistenceTests.kt`

### Problem

6단계 테스트가 성공 경로 하나만 검증한다. 프런트 픽스처는 `COMPLETED / PASSED / cleanupVerified=true` 하나뿐이고,
백엔드에는 `completeSession`의 집계 규칙(`status` / `resultOutcome` / `cleanupVerified`) 테스트가 없다.

### Trigger

해당 없음(테스트 공백 자체가 문제다).

### Impact

REV-001·REV-003이 테스트를 전부 통과한 채 남은 직접적인 원인이다.
또한 영속 테스트가 `specificationId`/`testSpecRunId`를 항상 `null`로 넣어 `V29`의 FK 두 개가 한 번도 실행되지 않고,
새 GET 2개에는 API 레벨 테스트가 없다.
`recoverIncompleteSessions`의 반환값을 정확히 1로 단언하는 부분은 다른 테스트가 `RUNNING` 세션을 남기면 깨진다.

### Evidence

세 테스트 파일의 픽스처와 단언을 읽어 확인했다.

### Recommendation

최소한 (a) `RECOVERY_REQUIRED` + `PASSED` 조합이 성공으로 보이지 않을 것,
(b) Run이 없는 세션이 정리 확인됨으로 저장되지 않을 것, (c) 목록에 없는 선택 id가 대체되지 않을 것,
(d) 늦게 도착한 이전 Target 응답이 반영되지 않을 것을 추가한다.

---

## REV-010

Severity: Minor
Status: OPEN

### Location

`PilotTemplateExecutionService.findSession()`

### Problem

존재하지 않는 세션 조회가 `ClientRequestException`을 던져 409 CONFLICT로 매핑된다.

### Impact

클라이언트가 "없음"과 "충돌"을 구분할 수 없다. 저장소에는 404로 매핑되는 `ResourceNotFoundException`이 이미 있고
`TestSpecificationService`가 그것을 쓴다.

### Evidence

`ApiExceptionHandler`에서 두 예외의 매핑을 확인했다.

### Recommendation

`ResourceNotFoundException`으로 바꾼다.

---

## REV-011

Severity: Minor
Status: OPEN

### Location

`PilotTemplateExecutionService.findSessions()` → `view()` → `sessions.findItems()`

### Problem

세션 목록 조회가 세션마다 항목 조회를 한 번씩 더 한다(최대 31쿼리).

### Impact

N+1. 목록 응답이 항목까지 모두 포함하므로 세션이 쌓일수록 비용이 커진다.

### Recommendation

`session_id in (...)`로 항목을 한 번에 읽어 메모리에서 묶는다.

---

## REV-012

Severity: Minor
Status: OPEN

### Location

`PilotTemplateExecutionService.completeSession()` (`items`의 `completedAt`)

### Problem

모든 항목의 `completedAt`에 세션 완료 시각 하나가 들어간다.

### Impact

오래 걸린 세션의 항목들이 같은 시각으로 기록돼 증거 타임라인이 왜곡된다.
자식 Run에 자체 시각이 남아 있어 완화되지만, 세션 응답만 보면 순서 외 정보가 없다.

### Recommendation

각 후보 실행이 끝난 시각을 항목에 기록한다(REV-002의 즉시 기록과 같이 처리하면 자연히 해결된다).

---

## REV-013

Severity: Minor
Status: OPEN

### Location

`JdbcPilotTestSessionRepository.complete()`의 `@Transactional`

### Problem

트랜잭션 경계가 Service가 아니라 Repository에 있다. 개인 컨벤션("Service가 트랜잭션 경계를 가진다")과 다르다.

### Impact

경계가 어디인지 코드에서 일관되게 읽히지 않는다.
세션 INSERT가 트랜잭션 밖인 것 자체는 재기동 복구 설계상 의도된 것이므로 이와 구분해야 한다.

### Recommendation

세션 완료 트랜잭션을 Service로 올리고, 의도적으로 트랜잭션 밖에 두는 INSERT에는 이유를 주석으로 남긴다.

---

## REV-014

Severity: Minor
Status: OPEN

### Location

`PilotTestSessionResultsPanel.tsx`, `PilotTemplateRunnerPanel.tsx`의 실패 표시 가드

### Problem

`{outcome.failureMessage && ...}` 구조라 `failureMessage`가 없으면 `failureCode`까지 화면에서 사라진다.

### Trigger

백엔드가 `failureMessage = failureMessage ?: run?.failure`로 계산하므로 message가 null이고
code만 `TEST_SPEC_RUN_RECOVERY_REQUIRED`인 항목이 나올 수 있다.

### Recommendation

가드를 `failureMessage || failureCode`로 바꾼다.

---

## REV-015

Severity: Minor
Status: OPEN

### Location

`frontend/src/api/pilotTemplates.ts`, `App.tsx`, `TestSpecRunWorkspace.tsx`,
`PilotDiscoveryPanel.tsx` / `PilotTemplateRunnerPanel.tsx`, `PilotTestSessionResultsPanel.tsx`

### Problem

프런트 잔여 정리 항목 묶음.

- `findPilotTestSession`이 어디에서도 호출되지 않는다(죽은 코드).
- `PilotDiscoveryPanel`과 `PilotTemplateRunnerPanel`이 같은 `pilot-discovery`를 같은 refreshKey로 각각 호출한다.
- `arl.test-spec-run-id` 키를 `App`과 `TestSpecRunWorkspace`가 각각 소유해 쓰기 주체가 둘이다.
- `onOpenSession`이 이전에 열어 둔 Run 선택을 비우지 않아 무관한 Run 결과가 세션 아래 남는다.
- `RUNNING` 세션이 자동 갱신되지 않는다(Run에는 폴링이 있다).
- 세션 선택 버튼에 `aria-pressed`/`role`이 없고, 접근명이 8자리 hex뿐이라 `createdAt`으로 구분할 수 없다.

### Impact

개별로는 작지만 세션 결과 화면의 신뢰도와 유지보수성을 함께 낮춘다.

### Recommendation

한 번에 정리한다. `findPilotTestSession`은 REV-006 수정에 그대로 쓸 수 있다.

---

## REV-016

Severity: Minor
Status: OPEN

### Location

`PilotTestSessionResultsPanel.tsx` (`busy` 초기값과 빈 상태 조건)

### Problem

첫 렌더 시점에는 `busy=false, sessions=[], message=null`이라 "저장된 파일럿 세션이 없습니다"가 먼저 그려지고,
그 뒤 로딩 중임을 알리는 표시가 없다.

### Impact

응답이 느리면 사용자가 세션이 저장되지 않았다고 결론 낼 수 있다.

### Recommendation

로딩 여부를 명시적으로 구분하고, 로딩 중에는 "불러오는 중"을 표시한다.
