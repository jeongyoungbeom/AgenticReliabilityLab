# Review Resolution

Task: `error-diagnosis-model`
Reviewed Revision: `reviews/error-diagnosis-model/REVIEW.md` (Base/Current HEAD `8ecbba5`, working tree 포함)
Applied At: 같은 작업 트리(HEAD `8ecbba5` + 1–7단계 미커밋 변경). 커밋·push 없음.

Summary: ACCEPTED 8 / REJECTED 0 / ALREADY_RESOLVED 0 / DEFERRED 0 / STALE 0

판정 방법: 각 finding을 `TASK.md` 요구사항·수용 기준과 현재 코드에서 직접 확인했다. REVIEW.md의 정규식 재현
주장은 그대로 받지 않고 `SensitiveDiagnosticRedactor`의 패턴과 호출 경로를 다시 읽어 확인했다.
8개 모두 ACCEPTED가 된 것은 리뷰를 정답으로 취급했기 때문이 아니라, 8개 모두 현재 코드에서 같은 사실이
확인됐기 때문이다. REVIEW.md의 "확인했으나 결함으로 보지 않은 부분"은 다시 검토했고 동의한다.

---

## REV-001

Status: ACCEPTED

Reason:
현재 코드에서 동일한 문제를 확인했다. `FailureDiagnosis.kt`의 `credentialTemplate`이 `ACCESS_DENIED`를
`TARGET_CREDENTIAL_EXPIRED`와 같은 분기에 묶어, 403 응답의 `nextAction`이 "해당 역할의 테스트 자격증명을
갱신한 뒤 preflight를 다시 실행하세요"였다. 그런데 `ACCESS_DENIED`를 만드는 곳은 `OperatorAccessService`
(`requireViewer`/`requireProfileEditor`/`requireExecutor`)뿐이고, 이는 ARL 자체 API의 운영자 역할 토큰 실패다.
`DECISIONS.md` D006이 ARL 접근 토큰과 Target seller/buyer/harness 토큰을 명시적으로 분리하므로,
안내가 사용자를 확실하게 틀린 조치로 보낸다. 수용 기준 2("기술 세부 사항이 결과 판정과 모순되지 않는다")에 어긋난다.

Changes:
- `diagnosis/FailureDiagnosis.kt` — `ACCESS_DENIED`를 별도 템플릿으로 분리했다.
  summary "ARL 접근 권한을 확인하지 못했습니다.", likelyCause에 Target 테스트 자격증명과 다른 값임을 명시,
  nextAction은 필요한 역할(viewer / profileEditor / executor)의 ARL 접근 토큰 입력을 안내한다.
  stage는 `CREDENTIALS`를 유지했다(ARL 자신의 자격증명 문제이므로 단계 의미가 바뀌지 않는다).
- 역할 이름은 REV-004 수정으로 `message`("Viewer authorization is required")가 더 이상 전체 마스킹되지 않아
  응답에 그대로 남는다. 역할 이름은 비밀값이 아니므로 별도 배관을 추가하지 않았다.

Verification:
- `FailureDiagnosisTests.separates ARL operator access from Target credential guidance` (신규) —
  `ACCESS_DENIED`가 ARL 안내를 주고 `nextAction`에 "preflight"가 없으며 `TARGET_CREDENTIAL_EXPIRED`와
  likelyCause가 다름을 고정한다.
- `ApiAuthorizationIntegrationTests` — 403 본문에 ARL 안내와 `Viewer authorization is required`가 있고
  "preflight"가 없음을 추가로 단언한다.
- `.\gradlew.bat check` — BUILD SUCCESSFUL, 359 tests / 0 failures (2026-08-28 16:33–16:35 KST).

---

## REV-002

Status: ACCEPTED

Reason:
확인했다. `ApiExceptionHandler.response()`가 모든 오류에 `diagnosis`를 붙이고, `formatApiError`는 `diagnosis`가
있으면 `error.code`와 `error.message`를 버렸다. `FailureDiagnosisFactory`가 매핑하는 코드는 12개뿐이라
`QUICK_OPENAPI_NOT_FOUND`, `TARGET_PROFILE_INVALID`, `INVALID_TEST_SPECIFICATION`, `INVALID_REQUEST`,
`RESOURCE_NOT_FOUND`, `PAYLOAD_TOO_LARGE`, `INTERNAL_ERROR`는 모두 `systemTemplate()`로 떨어진다.
`QuickTargetProfileRegistrationWorkflow`의 "등록한 URL에서 지원되는 Swagger/OpenAPI 문서를 찾지 못했습니다.
허용 경로: ..."는 marker에 걸리지 않아 서버는 안전하게 보내는데 프런트가 버렸다. "기술 정보를 확인하고"라고
안내하면서 화면에 기술 정보도 코드도 없었다. 요구사항 5(간편 등록 흐름 비회귀)와 수용 기준 2에 어긋난다.

Changes:
- `frontend/src/api/ApiClient.ts` — `formatApiError`가 한국어 안내 3줄을 앞에 두고,
  `summary`와 다른 서버 `message`를 "서버 메시지:"로, `technicalDetail`(코드 포함)을 "기술 정보:"로 덧붙인다.
  진단이 없을 때의 `${code}: ${message}` 경로는 그대로 둔다.

Verification:
- `frontend/src/api/ApiClient.test.ts` (신규) — 매핑되지 않은 `QUICK_OPENAPI_NOT_FOUND`에서 서버 메시지와
  코드가 남는지, 매핑된 코드에서 안내가 앞에 오고 summary와 같은 message가 중복되지 않는지,
  진단이 없을 때 기존 형식이 유지되는지 확인한다.
- `npm test` 50/50 PASS, `npm run build` PASS.

---

## REV-003

Status: ACCEPTED

Reason:
확인했다. `TestSpecRunner`가 pre-run reset 미검증 시 같은 `reason` 문자열을 `TrialExecution.failure`와
`evaluator.unrunnable(...)`의 `InvariantVerdict.detail`에 넣는데, `JdbcTestSpecRunRepository.insertTrial`은
`failure`와 `fault_events_json`에만 `safeFailure`를 걸고 `verdicts_json`은 원문으로 직렬화했다.
`toTrial()`도 `verdicts`만 마스킹 없이 역직렬화했고, 그 값이 `TestSpecTrialResponse.verdicts`로 API에 나가
`TestSpecRunWorkspace.tsx`에서 렌더된다. `reason`은 `EnvironmentResetService`가 Target 응답에서 만든
문자열이므로 요구사항 3·수용 기준 3(DB·API·Evidence·브라우저에 민감 원문 없음)과 D006에 어긋난다.

Changes:
- `testspec/infrastructure/JdbcTestSpecRunRepository.kt` — `safeVerdicts()`를 추가해
  `InvariantVerdict`의 `detail`, `observedValues`, `appliedException`을 `SensitiveDiagnosticRedactor`에 통과시킨다.
  쓰기(`insertTrial`의 `verdictsJson`)와 읽기(`toTrial()`) 양쪽에 적용해, 이미 저장된 행도 API로 나갈 때 걸러진다.

Verification:
- `JdbcTestSpecPersistenceTests.redacts a verdict detail carrying Target data before it reaches the verdicts column`
  (신규) — 리뷰 지적대로 `failure` 컬럼이 아니라 **`detail` 경로**로 민감값을 흘려 넣고,
  읽은 `verdict.detail`이 `[REDACTED]`인지와 `verdicts_json`/`failure` 컬럼에 원문이 없는지 확인한다.
  H2와 PostgreSQL 하위 클래스 양쪽에서 실행된다.
- `.\gradlew.bat check` — BUILD SUCCESSFUL, 359 tests / 0 failures (2026-08-28 16:33–16:35 KST).

---

## REV-004

Status: ACCEPTED

Reason:
양방향 모두 확인했다.
- 오탐: `potentiallySensitiveMarker`에 `authorization`과 `token`이 단독 단어로 들어 있어,
  `Viewer authorization is required`, `Profile editor authorization is required`,
  `Executor authorization is required`, `spec.steps[2].accessToken must not be blank`가 모두 전체 `[REDACTED]`가
  됐다. 사용자가 보는 것이 영어 한 줄에서 `[REDACTED]` 한 줄로 바뀔 뿐이라 요구사항 2를 직접 깬다.
- 미탐: 본문 탐지가 `(?:response\s+)?body\s*[:=]` 리터럴에만 의존해, 라벨 없는 JSON 본문은 알려진 키가 없으면
  통과한다. 요구사항 3이 요구하는 것은 "알려진 키워드가 있을 때"가 아니라 "응답 본문"이다.

Changes:
- `diagnosis/FailureDiagnosis.kt` — `SensitiveDiagnosticRedactor`를 세 조각으로 분리했다.
  1. `envelopeMarkers` — `x-arl-harness-key`, `set-cookie`, `bearer <값>`처럼 일반 문구에 나올 수 없는 것.
  2. `credentialValueMarker` — `authorization`/`cookie`/`token`류/`password`/`secret`/`body`가
     **값을 달고**(`\s*[:=]\s*\S`) 나타날 때만 걸린다. 단독 단어는 통과한다.
  3. `responseBodyFragments` — 키워드가 아니라 구조(`{"key":` 또는 `[{`)로 본문을 찾아 그 조각만 치환한다.
  1·2에 걸리면 종전처럼 문맥 전체를 버리고, 3만 걸리면 안전한 앞부분은 남긴다.
- 도달 불가능하던 기존 `sensitiveFragments` 부분 치환 목록은 제거했다(어떤 항목이든 매칭되면 marker에도
  반드시 걸려 전체 치환으로 갔다).

Verification:
- `FailureDiagnosisTests.keeps safe validation text that only names a credential field` (신규) — 비밀값 없는
  정상 문구 4종이 그대로 남는지.
- `FailureDiagnosisTests.redacts an unlabelled response body that carries no known key` (신규) — 키워드 없는
  JSON 본문이 사라지고 `HTTP 500 from POST /orders:` 접두는 남는지.
- 기존 `redacts credentials headers and response body from diagnostic text`,
  `api errors redact a Target exception message and attach a safe diagnosis`,
  `PilotTestSessionPersistenceTests`(`Cookie: session-secret`, `Authorization: Bearer ...`,
  `response body={...}`), `JdbcTestSpecPersistenceTests`의 기존 마스킹 단언은 새 규칙에서도 모두
  전체 마스킹 경로에 걸린다. `.\gradlew.bat check`에서 모두 통과했다(359 tests / 0 failures).

---

## REV-005

Status: ACCEPTED

Reason:
확인했다. 같은 `cleanupVerified === null`을 실행 직후 패널은 `NOT_VERIFIED`, 세션 결과 패널은 `-`로 표시했고,
세션 결과 패널 안에서도 `dt 정리 검증` 셀이 세 번째 구현을 따로 갖고 있었다. D010은 `null`을 "확인됨"으로
표시하지 말라고 요구하며 두 화면 모두 그 선은 지켰지만, 같은 사실에 다른 어휘를 쓰면 사용자가 두 화면을
다른 상태로 읽는다. `-`는 "검증 대상이 없다"를 전달하지 못한다.

Changes:
- `frontend/src/components/cleanupStatus.ts` (신규) — `cleanupLabel`을 한 곳에 두고 세 상태를
  `확인됨` / `미확인` / `검증 대상 없음`으로 정의했다.
- `PilotTemplateRunnerPanel.tsx`, `PilotTestSessionResultsPanel.tsx` — 각자의 구현 3개를 제거하고 공유
  함수를 쓴다. 실행 직후 패널의 `cleanup ...` 접두도 `정리 ...`로 통일했다.

Verification:
- `frontend/src/components/cleanupStatus.test.ts` (신규) — 세 상태의 문구를 고정한다.
- `PilotTemplateRunnerPanel.test.tsx` — 라벨 변경에 맞춰 단언을 갱신했다(`정리 확인됨` / `정리 미확인`).
  두 화면이 같은 어휘를 쓰게 되어 한 화면에 두 곳이 매칭되므로, 세션 줄은 `파일럿 세션 … 정리 확인됨`으로
  좁히고 개수도 함께 단언한다.
- `npm test` 50/50 PASS.

---

## REV-006

Status: ACCEPTED

Reason:
지적은 맞다. `response()`의 `require(message.isNotBlank())`는 `@RestControllerAdvice` **안에서**
`IllegalArgumentException`을 던지고, 같은 advice가 그것을 다시 처리하지 않으므로 응답이 ARL의 오류 계약이 아닌
컨테이너 기본 오류가 된다. 프런트 `toApiError`는 JSON 파싱 실패로 `HTTP_ERROR` fallback에 떨어져 사용자는
다시 일반 문구만 본다. 리뷰가 밝힌 대로 공백 메시지를 만드는 호출부는 현재 코드에 없어 **재현된 결함은 아니지만**,
가드가 걸리는 순간의 결과가 가드가 막으려던 것보다 나쁘고 수정이 1줄이며 이미 같은 fallback이 있으므로 반영했다.

Changes:
- `api/common/ApiExceptionHandler.kt` — `require`를 제거하고,
  `redact(message)?.takeIf { it.isNotBlank() } ?: diagnosis.summary`로 `null`과 공백을 같은 fallback으로 처리한다.
  이유는 주석으로 남겼다.

Verification:
- `FailureDiagnosisTests.falls back to the diagnosis summary instead of throwing on a blank source message` (신규) —
  공백 메시지에서 예외 없이 `diagnosis.summary`가 `message`가 되는지 확인한다.
- `.\gradlew.bat check` — BUILD SUCCESSFUL, 359 tests / 0 failures (2026-08-28 16:33–16:35 KST).

---

## REV-007

Status: ACCEPTED

Reason:
확인했다. 변경 전 `log.error(..., exception)`이 스택트레이스를 남겼으나 지금은 예외 클래스 이름만 남아,
`INTERNAL_ERROR`가 나면 어느 코드에서 났는지 알 수 없다. 요구사항 3이 막으려는 것은 저장·응답·화면·로그에
남는 **민감값**이고(`AGENTS.md`도 로그를 포함한다), 위험은 예외 **메시지**에 있는데 제거된 것은 메시지를 포함한
스택 전체였다. `AGENTS.md`의 "오류 원인을 확인 가능한 형태로 처리한다"에 어긋난다.

Changes:
- `api/common/ApiExceptionHandler.kt` — `messageFreeOrigin(exception)`을 추가했다.
  원인 체인의 클래스 이름(최대 5개)과 상위 스택 프레임(최대 12개, `class.method:line`)만 남기고
  예외 메시지는 필터링이 아니라 **애초에 포함하지 않는다**. 예외 객체 자체는 여전히 로거에 넘기지 않는다.

Verification:
- 자동 테스트는 추가하지 않았다. 이 변경은 로그 문자열 조립이며, 단언할 수 있는 것은 "메시지를 참조하지 않는다"는
  코드 사실 자체다. 로그 appender를 테스트로 붙이는 것은 이 TASK 범위에 비해 과하다고 판단했다.
  대신 `messageFreeOrigin`이 `exception.message` / `cause.message`를 전혀 읽지 않음을 코드로 확인했다.
- 스택을 남기지 않는 쪽을 택하지 않았으므로 `DECISIONS.md`에는 새 항목을 추가하지 않았다.

---

## REV-008

Status: ACCEPTED

Reason:
확인했다. `frontend/src/api/targetCredentials.ts`의 `import type { FailureDiagnosis }`가 파일 마지막 줄에 있었다.
동작에는 영향이 없지만 같은 변경에서 손댄 `pilotTemplates.ts`·`testSpecifications.ts`와 다르고
`AGENTS.md`의 "기존 구조, 명명법, 코딩 스타일을 우선한다"에 어긋난다.

Changes:
- `frontend/src/api/targetCredentials.ts` — import를 파일 상단으로 옮겼다.

Verification:
- `npm run build`(`tsc -b` 포함) PASS, `npm test` 50/50 PASS.

---

## 검증 결과와 한계

- **백엔드**: `.\gradlew.bat check` — **BUILD SUCCESSFUL**, detekt 0 findings,
  **359 tests / 0 failures / 0 errors** (2026-08-28 16:33–16:35 KST, 사용자 Windows 환경에서 실행).
  리뷰 반영 전 353에서 6 증가했다(`FailureDiagnosisTests` 4개, verdict `detail` 마스킹 1개가
  H2·PostgreSQL 양쪽에서 실행). 이 실행에서 나온 `FailureDiagnosisTests.kt:88`의 불필요한 `!!` 경고 2건은
  이후 로컬 변수로 정리했고, **그 정리 이후에는 다시 실행하지 않았다.**
- **프런트**: `npm test` 50 tests / 12 files PASS, `npm run build` PASS (2026-08-28 16:00 KST).
  원격 세션 Linux VM에 `frontend` 소스를 그대로 복사하고 같은 `package-lock.json`으로 설치한 사본에서
  수행했다(저장소의 `node_modules`가 Windows 네이티브 바이너리라 그 자리에서는 실행되지 않는다).
  소스는 `diff -rq`로 동일함을 확인했다.
- 실제 Target(SideProject Docker)에 대한 동작 확인과 브라우저 UI 확인은 하지 않았다. REV-001·REV-002·REV-005의
  화면 문구는 코드와 테스트로 확인한 것이고 렌더된 화면을 본 것은 아니다.
