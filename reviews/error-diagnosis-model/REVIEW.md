# Independent Review

Task: error-diagnosis-model
Base Commit: `8ecbba5` (`feat: streamline pilot target setup`)
Current HEAD: `8ecbba5`
Working Tree Included: **yes** — 7단계(오류 진단 모델) 전체가 미커밋 상태이며 이 리뷰의 대상이다.
1–6단계 미커밋 변경도 같은 작업 트리에 있으나, 이미 `reviews/pilot-ux-simplification/`에서 리뷰·반영된 범위이므로
이번에는 7단계가 새로 도입하거나 바꾼 동작만 본다.
Relevant Diff:
`diagnosis/FailureDiagnosis.kt`(신규), `api/common/{ApiErrorResponse,ApiExceptionHandler}.kt`,
`access/ApiAuthorizationFilter.kt`, `targetcredential/application/*`,
`targetdiscovery/{application,domain,infrastructure,api}/**`,
`testspec/{domain,infrastructure,api/dto}/**`,
`db/migration/V30__pilot_failure_diagnoses.sql`, `V31__test_spec_run_failure_diagnoses.sql`,
`frontend/src/components/FailureDiagnosisDetails.tsx`(신규), `frontend/src/api/ApiClient.ts`,
`frontend/src/features/{profiles,specifications}/**` 및 각 테스트.

Reviewed At: 2026-08-28
Method: `AGENTS.md` → `TASK.md` → `git status`/`git diff` → 변경 코드와 호출 관계 → 테스트 순으로 먼저 독립 검토해
finding 후보를 만든 뒤, `reviews/pilot-ux-simplification/REVIEW.md`와 `DECISIONS.md`를 대조해 중복을 제거했다.
`HANDOFF.md`는 이 세션 초반에 이미 읽힌 상태였으나, 완료 주장은 판단 근거로 쓰지 않고 코드로만 확인했다.

검증 상태 참고: 이 리뷰에서는 빌드·테스트를 실행하지 않았다(원격 세션의 셸 시간 제한). 아래 finding은 모두
**정적 코드 근거와 정규식 재현으로 확인한 것**이며, "빌드와 테스트가 통과하는데도 잘못된 것"만 담았다.
정규식 동작은 `SensitiveDiagnosticRedactor`의 패턴을 그대로 옮겨 실제로 매칭 결과를 재현해 확인했다.

---

## REV-001

Severity: Major
Status: OPEN

### Location

- `src/main/kotlin/.../diagnosis/FailureDiagnosis.kt:57-62` (`"TARGET_CREDENTIAL_EXPIRED", "ACCESS_DENIED" -> ...`)
- `src/main/kotlin/.../access/ApiAuthorizationFilter.kt:82-90` (`writeForbidden`)
- `src/main/kotlin/.../access/OperatorAccessService.kt:39-58`
- `src/test/kotlin/.../access/ApiAuthorizationIntegrationTests.kt:31-32`

### Problem

`ACCESS_DENIED`가 Target 자격증명 만료(`TARGET_CREDENTIAL_EXPIRED`)와 같은 템플릿에 묶여 있다.
그런데 ARL에서 `ACCESS_DENIED`를 만드는 곳은 `OperatorAccessService`이고, 그 실패는 **ARL 자체 API의 운영자 역할
토큰**(viewer / profileEditor / executor)이 없거나 틀렸다는 뜻이다. Target의 seller/buyer/harness 테스트 자격증명과는
다른 자격증명이다(`DECISIONS.md` D006이 둘을 명시적으로 분리한다).

결과적으로 403 응답의 진단은 다음과 같이 나간다.

- summary: `Target 인증 또는 권한을 확인하지 못했습니다.`
- likelyCause: `자격증명이 만료되었거나 이 역할에 필요한 권한이 없습니다.`
- nextAction: `해당 역할의 테스트 자격증명을 갱신한 뒤 preflight를 다시 실행하세요.`

실제 조치는 "ARL 접근 토큰을 넣어라"인데, 안내는 "Target 테스트 자격증명을 갱신하고 preflight를 다시 실행하라"다.

같은 응답의 `message`도 도움이 되지 않는다. 원래 메시지는 `Viewer authorization is required` /
`Profile editor authorization is required` / `Executor authorization is required`인데, 세 문자열 모두
`SensitiveDiagnosticRedactor`의 marker(`authorization`)에 걸려 **전체가 `[REDACTED]`로 치환된다**(REV-004 참고).

### Trigger

SECURED 모드에서 역할 토큰 없이/잘못된 토큰으로 `/api/**`를 호출하는 모든 경우. `ApiAuthorizationFilter`가 잡는 경로와
`ApiExceptionHandler.forbidden`이 잡는 경로 양쪽 모두 같은 진단을 붙인다.

### Impact

TASK 요구사항 2("사용자가 영어 예외 코드 한 줄을 해석해야 다음 행동을 알 수 있는 UI가 되면 안 된다")를 만족시키려던
변경이, 오히려 **틀린 다음 행동을 확신 있게 제시한다.** 사용자는 Target 토큰을 다시 넣고 preflight를 반복하다가
원인(ARL 접근 토큰)에 도달하지 못한다. 수용 기준 2의 "기술 세부 사항은 결과 판정과 모순되지 않는다"에도 어긋난다.

### Evidence

- `OperatorAccessService.requireProfileEditor/requireExecutor/requireViewer`가 넘기는 메시지 3종을 확인했다
  (`OperatorAccessService.kt:39-58`). 모두 ARL 역할 토큰에 대한 것이다.
- `ApiAuthorizationFilter.doFilterInternal`은 `AccessDeniedException`을 잡아 `writeForbidden(response, exception.message)`로
  넘기고, `writeForbidden`은 `FailureDiagnosisFactory.fromCode("ACCESS_DENIED", 403)`을 붙인다.
- marker 정규식을 그대로 재현해 세 메시지가 모두 전체 치환 대상임을 확인했다(확인, 추측 아님).
- 이번에 추가된 `ApiAuthorizationIntegrationTests`는
  `assertContains(body, "\"diagnosis\":{\"stage\":\"CREDENTIALS\"")`로 **이 매핑을 회귀 테스트로 고정한다.**
  즉 테스트가 통과한다는 사실이 이 안내가 맞다는 근거가 되지 못한다.

### Recommendation

ARL 운영자 접근 실패와 Target 자격증명 실패를 서로 다른 코드/템플릿으로 분리하는 방향을 검토한다
(예: `ACCESS_DENIED`는 "ARL 접근 토큰" 단계로 두고, Target 쪽은 `TARGET_CREDENTIAL_*`만 유지).
어떤 역할 토큰이 필요한지는 비밀값이 아니므로, 역할 이름을 진단에 남길 수 있는지도 함께 본다.

---

## REV-002

Severity: Major
Status: OPEN

### Location

- `frontend/src/api/ApiClient.ts:86-90` (`formatApiError`)
- `src/main/kotlin/.../api/common/ApiExceptionHandler.kt:77-86` (`response`)
- `src/main/kotlin/.../diagnosis/FailureDiagnosis.kt:42-48,130-135` (`templateFor` / `systemTemplate`)
- 호출부: `QuickTargetRegistration.tsx`, `TargetProfileWorkspace.tsx`, `PilotDiscoveryPanel.tsx`,
  `EffectiveSettingsPanel.tsx`, `TargetCredentialPanel.tsx`, `PilotTemplateRunnerPanel.tsx`,
  `PilotTestSessionResultsPanel.tsx`, `TestSpecRunWorkspace.tsx`

### Problem

`ApiExceptionHandler.response()`는 이제 **모든** 오류에 `diagnosis`를 붙인다. 그리고 프런트의 `formatApiError`는
`diagnosis`가 있으면 `error.code`와 `error.message`를 **전부 버리고** 진단 3줄만 렌더한다.

문제는 `FailureDiagnosisFactory`가 매핑하는 코드가 12개뿐이고, 나머지는 전부 `systemTemplate()`로 떨어진다는 점이다.
매핑되지 않는 실제 사용자 대면 코드에는 최소한 다음이 있다.

- `QUICK_OPENAPI_NOT_FOUND` (간편 등록)
- `TARGET_PROFILE_INVALID` (고급 YAML 검증)
- `INVALID_TEST_SPECIFICATION`, `INVALID_REQUEST`, `RESOURCE_NOT_FOUND`, `PAYLOAD_TOO_LARGE`, `INTERNAL_ERROR`

이 코드들에서 사용자가 보는 문구는 오직 이것 하나가 된다.

> 요청을 안전하게 완료하지 못했습니다. 예상 원인: ARL 내부 상태 또는 현재 실행 조건을 확인해야 합니다.
> 다음 행동: 기술 정보를 확인하고, 필요한 설정을 바로잡은 뒤 다시 시도하세요.

`formatApiError`는 `technicalDetail`조차 포함하지 않으므로, "기술 정보를 확인하고"라고 안내하면서 정작 화면에는
확인할 기술 정보도 오류 코드도 없다.

### Trigger

간편 등록에서 Swagger를 찾지 못하는 경우가 가장 흔하다. `QuickTargetProfileRegistrationWorkflow.kt:277-283`은
`등록한 URL에서 지원되는 Swagger/OpenAPI 문서를 찾지 못했습니다. 허용 경로: /v3/api-docs, /swagger.json, ...`라는
**이미 한국어이고 이미 조치 가능한** 메시지를 던지는데, 이 메시지가 화면에 도달하지 않는다.

### Impact

TASK 요구사항 2를 만족시키려는 변경이 요구사항 5("간편 등록 … 기존 성공 흐름을 회귀시키지 않는다")의 실패 안내를
회귀시킨다. 변경 전에는 `${code}: ${message}` 형태로 허용 경로 목록까지 보였고, 지금은 일반 문구만 남는다.
사용자는 무엇을 고쳐야 할지 알 수 없고, 지원 문의에 남길 코드조차 화면에 없다.

이 메시지는 marker에 걸리지 않아 **서버는 안전하게 보냈고, 프런트가 버린 것**임을 확인했다(REV-004와 원인이 다르다).

### Evidence

- `formatApiError`는 `diagnosis`가 없을 때만 `${error.code}: ${error.message}`를 반환하고, 그 뒤에는 곧바로 진단 3줄만 반환한다.
  `diagnosis`가 항상 붙게 된 이상 첫 줄은 사실상 죽은 분기다.
- `ApiExceptionHandler.response()`는 status/code와 무관하게 `FailureDiagnosisFactory.fromCode(code, status.value())`를
  붙인다. `templateFor`의 fallback은 `systemTemplate()`이다.
- 변경 전 8개 화면의 `errorMessage()`는 모두 `${error.code}: ${error.message}`였다(diff로 확인).

### Recommendation

진단과 서버 메시지를 배타적으로 다루지 않는 방향을 검토한다. 예를 들어 매핑된 코드에서는 진단을 주 문구로 쓰되
안전한 `message`를 보조로 함께 보이고, 매핑되지 않은 코드에서는 기존처럼 `message`를 주 문구로 유지한다.
최소한 `technicalDetail`(코드 포함)은 어떤 경우에도 화면에 남겨야 "기술 정보를 확인하라"는 안내가 성립한다.

---

## REV-003

Severity: Major
Status: OPEN

### Location

- `src/main/kotlin/.../testspec/application/TestSpecRunner.kt:56-59`
- `src/main/kotlin/.../testspec/application/InvariantEvaluator.kt:68-84` (`unrunnable` → `detail = reason`)
- `src/main/kotlin/.../testspec/infrastructure/JdbcTestSpecRunRepository.kt:212-235` (`insertTrial`, `verdictsJson`)
- `frontend/src/features/specifications/TestSpecRunWorkspace.tsx:214` (`verdict.detail` 렌더)

### Problem

이번 변경은 `failure` 계열 컬럼과 `fault_events_json`에는 `safeFailure(...)`(= `SensitiveDiagnosticRedactor`)를
읽기·쓰기 양쪽에 걸었지만, **같은 문자열이 들어가는 `verdicts_json`에는 걸지 않았다.**

`TestSpecRunner`는 pre-run reset이 검증되지 않으면 동일한 `reason` 문자열을 두 곳에 넣는다.

```kotlin
val reason = baseline.failure ?: "Pre-run reset was not verified"
executions.add(TrialExecution(1, ..., failure = reason))   // -> failure 컬럼: redaction 적용
trials.add(evaluator.unrunnable(specification, 1, reason)) // -> verdicts_json: redaction 미적용
```

즉 구현자가 "마스킹이 필요하다"고 판단한 바로 그 문자열이, 한 컬럼에서는 `[REDACTED]`가 되고 옆 컬럼에는 원문으로
남는다. 그리고 `verdict.detail`은 UI에서 그대로 렌더된다.

### Trigger

pre-run reset 검증 실패. 이 TASK가 다루려는 대표 실패 중 하나이며, 8단계 실제 Target 통합에서 가장 먼저 만나는 실패다.
`baseline.failure`는 `EnvironmentResetService.kt:41-58`에서 만들어지며
`The environment did not return to its baseline: 'check' saw <Target 관측값>` 또는
`The reset hook did not succeed: <transport 메시지>` 형태로 Target에서 온 문자열을 포함한다.

### Impact

요구사항 3 / 수용 기준 3은 민감 원문이 **DB·API 응답·Evidence**에 남지 않을 것을 요구하고, `DECISIONS.md` D006도
Target 자격증명이 Evidence에 남지 않아야 한다고 못 박는다. 현재 redaction 경계는 `failure` 계열에만 있어
같은 데이터가 `test_spec_trial_result.verdicts_json`을 통해 DB·API·브라우저로 그대로 나간다.

기존 `stores verdicts timings and resets without raw responses or bindings` 테스트는 이 경로를 잡지 못한다.
그 테스트의 민감값은 **응답/바인딩**에 심어져 있어 애초에 영속되지 않는 값이고, `detail` 경로로는 흐르지 않는다.

### Evidence

- `insertTrial`은 `failure`는 `safeFailure(execution.failure)`로, `faultEventsJson`은 이벤트별 `failure`를 마스킹해서
  넣지만, `verdictsJson`은 `objectMapper.writeValueAsString(trial.verdicts)` 그대로다.
- `toTrial()`도 `failure`와 `faultEvents`만 다시 마스킹하고 `verdicts`는 원문으로 역직렬화한다.
- `InvariantVerdict.detail`은 `TestSpecRunResponse`의 `verdicts`에 그대로 실려 나가고
  `TestSpecRunWorkspace.tsx:214`에서 `<p className="verdict-detail">{verdict.detail}</p>`로 렌더된다.
- 정규식 재현 결과, `The environment did not return to its baseline: 'inventory_restored' saw 3`은
  marker에 걸리지 않아 `failure` 컬럼에서도 원문으로 남는다 — 즉 이 사례에서는 두 컬럼의 차이가 드러나지 않지만,
  Target 메시지에 알려진 키가 포함되는 순간 **같은 문장이 한쪽만 마스킹되는 비대칭**이 발생한다.

### Recommendation

redaction을 컬럼별 호출이 아니라 영속·직렬화 경계 한 곳에서 적용하는 방향을 검토한다.
최소한 `InvariantVerdict.detail`(및 `observedValues`, `appliedException`)이 `verdicts_json`에 들어가기 전과
API DTO로 나갈 때 같은 필터를 통과해야 한다. 회귀 테스트는 `failure` 컬럼이 아니라 **`detail` 경로**로 민감값을
흘려 넣어 확인해야 한다.

---

## REV-004

Severity: Major
Status: OPEN

### Location

- `src/main/kotlin/.../diagnosis/FailureDiagnosis.kt:153-181` (`SensitiveDiagnosticRedactor`)
- `src/test/kotlin/.../diagnosis/FailureDiagnosisTests.kt:36-46`
- `src/test/kotlin/.../testspec/infrastructure/JdbcTestSpecPersistenceTests.kt:237-258`

### Problem

redactor는 키워드 marker 하나로 "전체 삭제 / 그대로 통과"를 결정한다. 두 방향 모두 문제가 있다.

**오탐(정상 문구가 통째로 사라진다).** marker 목록에 `authorization`과 `token`이 단독 단어로 들어 있어,
민감값이 전혀 없는 문장도 전체가 `[REDACTED]`가 된다. 재현으로 확인한 예:

| 입력 | 결과 |
| --- | --- |
| `Viewer authorization is required` | 전체 `[REDACTED]` |
| `Profile editor authorization is required` | 전체 `[REDACTED]` |
| `spec.steps[2].accessToken must not be blank` | 전체 `[REDACTED]` |

**미탐(응답 본문은 알려진 키가 없으면 그대로 남는다).** 요구사항 3은 "응답 본문"을 제거 대상으로 명시하는데,
본문 탐지는 `(?:response\s+)?body\s*[:=]` 리터럴에만 의존한다. 재현으로 확인한 예:

| 입력 | 결과 |
| --- | --- |
| `HTTP 500 from POST /orders: {"message":"unexpected","customerEmail":"a@b.c"}` | **원문 그대로 저장** |

### Trigger

- 오탐: SECURED 모드의 모든 403(REV-001), 인증 관련 필드명을 쓰는 명세 검증 오류.
- 미탐: Target 또는 중간 계층이 `body:` 같은 라벨 없이 JSON을 예외 메시지에 담는 경우.
  현재 ARL의 `TargetHttpTransportException` 메시지는 모두 정형 문구라 이 경로는 **확인된 재현이 아니라 미탐 가능성**이다.
  다만 요구사항 3이 요구하는 보장은 "알려진 키워드가 있을 때"가 아니라 "응답 본문"이다.

### Impact

- 오탐 쪽은 요구사항 2를 직접 깬다. 사용자가 보는 것이 영어 코드 한 줄에서 `[REDACTED]` 한 줄로 바뀌었을 뿐이다.
- 미탐 쪽은 수용 기준 3의 보장을 키워드 목록의 완전성에 의존하게 만든다. 현재 회귀 테스트
  (`FailureDiagnosisTests` 3번째 테스트, `JdbcTestSpecPersistenceTests`의 새 테스트)는 **모두 알려진 키워드를 포함한
  입력만** 넣으므로, 키워드 밖 본문이 통과한다는 사실을 드러내지 못한다.

### Evidence

- 위 표는 `potentiallySensitiveMarker` 정규식을 그대로 옮겨 실제 매칭을 재현해 얻은 결과다(추측 아님).
- `redact`는 marker가 하나라도 걸리면 `sensitiveFragments` 부분 치환을 아예 건너뛰고 `"[REDACTED]"`를 반환한다
  (`FailureDiagnosis.kt:177`). 주석은 이것이 의도임을 밝히고 있으나, 의도가 요구사항 2와 충돌한다.
- 새 테스트의 입력은 `Authorization: Bearer ... access_token=... response body={...}` 형태로
  marker 전체 삭제 경로만 통과시킨다.

### Recommendation

marker(문맥 전체 삭제 판단)와 실제 비밀값 패턴을 분리하는 방향을 검토한다.
`authorization`/`token`이 **값과 함께**(`\s*[:=]\s*값`, `Bearer <값>`) 나타날 때만 전체 삭제로 올리고, 단독 단어는
부분 치환 또는 통과로 두면 오탐이 줄어든다. 본문은 키워드가 아니라 구조(예: `{`/`[`로 시작하는 JSON 조각, 길이)로
판단하는 편이 요구사항 3의 문구에 더 가깝다.
어느 쪽을 택하든, 회귀 테스트에 **키워드가 없는 본문**과 **비밀값이 없는 정상 문구** 두 종류를 모두 넣어야 한다.

---

## REV-005

Severity: Minor
Status: OPEN

### Location

- `frontend/src/features/profiles/PilotTemplateRunnerPanel.tsx:188-190`
- `frontend/src/features/specifications/PilotTestSessionResultsPanel.tsx:170-172`

### Problem

`cleanupVerified`가 `null`(= 실행된 Run이 없어 정리 검증 대상이 없음)일 때 두 화면의 표시가 다르다.

- 실행 직후 패널: `NOT_VERIFIED`
- 세션 결과 패널: `-`

같은 세션의 같은 값인데, 한쪽은 경고로 읽히고 다른 쪽은 "데이터 없음"으로 읽힌다.

### Trigger

후보가 Run을 만들지 못하고 끝난 세션(예: `PILOT_TEMPLATE_REJECTED`, 템플릿 생성 단계 실패)을 실행 직후 화면에서 보고
곧바로 세션 결과 화면에서 다시 여는 경우.

### Impact

`DECISIONS.md` D010은 판정·상태·정리 검증을 뭉치지 말고 `null`을 "확인됨"으로 표시하지 말 것을 요구한다.
두 화면 모두 그 선은 지키지만, 같은 사실에 다른 어휘를 쓰면 사용자가 두 화면을 서로 다른 상태로 오해한다.
`-`는 "검증 대상이 없다"는 사실을 전달하지 못한다.

### Evidence

두 `cleanupLabel` 구현이 `null` 분기에서 각각 `'NOT_VERIFIED'`와 `'-'`를 반환한다. 이번 변경에서 두 함수가 모두
새로 추가됐다(diff로 확인).

### Recommendation

`cleanupVerified`의 세 상태(검증됨 / 미검증 / 검증 대상 없음)에 대한 라벨을 한 곳에서 정의해 두 화면이 공유한다.
요구사항 2를 따르면 한국어 라벨로 통일하는 편이 일관적이다.

---

## REV-006

Severity: Minor
Status: OPEN

### Location

`src/main/kotlin/.../api/common/ApiExceptionHandler.kt:77-78`

### Problem

```kotlin
private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<ApiErrorResponse> {
    require(message.isNotBlank()) { "An API error response must have a diagnostic source" }
```

`require`는 `@RestControllerAdvice`의 예외 핸들러 **안에서** `IllegalArgumentException`을 던진다. 핸들러 안에서 던진
예외는 같은 advice가 다시 처리하지 않으므로, 이 경우 응답은 ARL의 안정적 오류 계약(`code`/`message`/`correlationId`/
`diagnosis`)이 아니라 서블릿 컨테이너의 기본 오류 응답이 된다.

### Trigger

`exception.message`가 `null`이 아니라 **빈 문자열/공백**인 경우. 현재 호출부는 모두 `?:` 기본값으로 `null`만 막고
공백은 막지 않는다(`notFound`, `invalidTestSpecification`, `unreadableRequest`, `invalidRequest`).
현재 코드베이스에서 공백 메시지를 만드는 호출부를 **찾지는 못했다** — 재현된 결함이 아니라 방어 코드가
실패했을 때의 결과가 더 나쁜 구조라는 지적이다.

### Impact

가장 방어하고 싶은 경로(오류 응답)에서, 가드가 걸리는 순간 구조화된 진단이 전혀 없는 응답이 나간다.
프런트의 `toApiError`는 JSON 파싱에 실패하면 `HTTP_ERROR` fallback으로 떨어지므로 사용자는 다시 일반 문구만 본다.

### Evidence

`response()`는 모든 핸들러의 공통 경로이고, `require` 이후에야 `diagnosis`가 만들어진다.
`unexpected(Exception)` 핸들러도 결국 같은 `response()`를 호출하므로 이 예외를 되받아 줄 상위 핸들러가 없다.

### Recommendation

핸들러 안에서 던지지 말고, 공백 메시지를 `diagnosis.summary`로 대체하는 방향을 검토한다
(이미 `safeMessage` 계산에 같은 fallback이 있다). 계약 위반 감지가 필요하면 예외 대신 로그로 남긴다.

---

## REV-007

Severity: Minor
Status: OPEN

### Location

`src/main/kotlin/.../api/common/ApiExceptionHandler.kt:66-75` (`unexpected`)

### Problem

변경 전 `log.error("...", correlationId, exception)`이 스택트레이스를 남겼으나, 지금은 예외 객체를 넘기지 않고
`exception.javaClass.simpleName`만 남긴다. 스택트레이스가 **어디에도** 남지 않는다.

### Trigger

ARL 내부의 예상하지 못한 예외(NPE, 직렬화 오류, JDBC 오류 등) 전부.

### Impact

`INTERNAL_ERROR`가 발생하면 서버 로그에는 correlationId와 예외 클래스 이름만 남는다. 어느 코드에서 났는지 알 수 없어
ARL 자체 결함의 원인 추적이 사실상 불가능해진다. 요구사항 3이 막으려는 것은 **저장·응답·화면에 남는 민감값**이며,
"운영자만 보는 서버 로그에서 스택트레이스를 완전히 없앤다"까지는 요구하지 않는다.

### Evidence

diff에서 세 번째 인자(`exception`)가 제거되고 주석으로 "Target 라이브러리가 헤더/본문을 담을 수 있다"고 밝히고 있다.
그 위험은 **메시지**에 있는데, 제거된 것은 메시지를 포함한 스택 전체다.

### Recommendation

원인 체인의 클래스 이름과 스택 프레임은 남기고 메시지만 마스킹하는 중간 지점을 검토한다
(예: 마스킹한 메시지로 감싼 예외를 로깅하거나, `exception.stackTrace`만 별도 기록).
정말로 스택을 남기지 않기로 한다면, 그 판단은 `DECISIONS.md`에 남겨야 이후 세션에서 되돌려지지 않는다.

---

## REV-008

Severity: Minor
Status: OPEN

### Location

`frontend/src/api/targetCredentials.ts:40`

### Problem

`import type { FailureDiagnosis } from './ApiClient'`가 파일 **맨 마지막 줄**에 있다. 같은 변경에서 손댄
`pilotTemplates.ts`와 `testSpecifications.ts`는 기존대로 첫 줄의 import에 합쳐 넣었다.

### Trigger

해당 파일을 읽거나 수정할 때. 동작에는 영향이 없다(ESM import는 호이스팅된다).

### Impact

`AGENTS.md`의 "기존 구조, 명명법, 코딩 스타일을 우선한다"에 어긋난다. lint가 잡지 못해 그대로 남았다.

### Evidence

`tail -4 frontend/src/api/targetCredentials.ts`로 확인했다. 파일 상단에는 이미 다른 import가 없고,
`FailureDiagnosis` 타입은 22번째 줄 `TargetCredentialPreflightResult`에서 사용된다.

### Recommendation

import를 파일 상단으로 옮긴다.

---

## 확인했으나 결함으로 보지 않은 부분

오탐을 막기 위해 남긴다.

- **`cleanupVerified`를 `Boolean` → `Boolean?`로 바꾼 것**: `pilot_test_session.cleanup_verified`는 V29에서
  nullable이므로 null 저장에 문제가 없고, "실행이 없었는데 정리 검증됨"을 없애는 방향이라 D010과 요구사항 4에 맞는다.
  기존 `reviews/pilot-ux-simplification/REVIEW.md` REV-003의 반영 결과로 확인했다.
- **`findSession`이 `ResourceNotFoundException`(404)로 바뀐 것**: 기존 REVIEW.md REV-010의 반영 결과이며,
  프런트의 `findPilotTestSession`도 함께 제거돼 호출자가 없다. 중복 finding을 만들지 않는다.
- **`TestSpecRunWorkspace`에서 `useSessionStorageState` 제거**: 같은 키(`arl.test-spec-run-id`)를 `App.tsx:21`이
  여전히 sessionStorage로 유지하므로 새로고침 복구가 사라지지 않는다.
- **Evidence(`observations_json`)에 Target 값이 남는 것**: `ObservedEvidence`는 명세가 선언한 관측 표현식의 결과이지
  응답 본문 원문이 아니다. 기존 `stores verdicts timings and resets without raw responses or bindings` 테스트가
  응답/바인딩 비영속을 지킨다. (단, `verdicts_json`의 `detail` 경로는 REV-003 참고.)
- **`diagnostic_*` 컬럼 길이(V31의 `varchar(500)`)와 저장 시 truncate 부재**: 현재 `technicalDetail`은
  `code=... · HTTP nnn` 형태로 짧고, `summary`/`likelyCause`/`nextAction`은 고정 상수라 길이 초과 경로를 찾지 못했다.
- **`JdbcPilotTestSessionRepository.toDiagnosis()`의 플랫폼 타입 NPE 가능성**: `diagnostic_stage`가 non-null인 행은
  항상 다섯 컬럼을 함께 쓰는 경로에서만 만들어지므로 재현 경로를 찾지 못했다.
- **파일럿 세션 항목 상태 매핑(`toSessionItem`)과 세션 집계(`completeSession`)**: `PASSED`는 모든 항목이
  `COMPLETED && PASSED`일 때만 나오고, 실패 항목이 있으면 `INCONCLUSIVE`/`VIOLATED`로 떨어진다.
  실패를 성공으로 보이게 하는 경로는 찾지 못했다.

## 확인하지 못한 부분

- **빌드·테스트를 실행하지 못했다.** 이 세션의 원격 셸은 호출당 45초 제한이 있어 `.\gradlew.bat check`와
  `npm test`를 돌릴 수 없었다. 위 finding은 모두 정적 근거이며, 컴파일/테스트 실패를 주장하는 항목은 없다.
- **실제 Target(SideProject Docker)에 대한 동작 확인을 하지 않았다.** REV-001·REV-002의 화면 문구는
  코드 경로로 확인한 것이고 브라우저에서 렌더된 결과를 본 것은 아니다.
- **`analysis/` 패키지의 `failureFrom(exception)` 경로**는 이번 변경 범위 밖이라 보지 않았다.
