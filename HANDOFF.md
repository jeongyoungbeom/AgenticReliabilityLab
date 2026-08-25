# HANDOFF — 다음 세션 인수인계

작성: 2026-08-23, 갱신: 2026-08-25 / 기준 커밋: `fd5cff5` (Target별 명세 목록 조회 API 추가) — Phase 0~22와 명세 목록 조회 API까지 전부 구현·빌드 검증·커밋 완료. **다음은 UI 작업(3.1절)부터, 바로 아래 절 참고**

## 개발 현황 요약 (2026-08-25 기준, 다음 세션은 여기부터 읽을 것)

### 지금까지 개발된 것 — 전부 구현·빌드 검증·커밋 완료

- **Phase 0~19**(`DESIGN.md`/`DESIGN2.md`/`DESIGN3.md`): Target Profile, 안전한 HTTP Batch, Target 이해
  모델, 테스트 후보·Test Plan, 범용 Test Harness, 선언형 테스트 명세 엔진(파싱·검증·실행·CEL 판정),
  `/harness/state`·Prometheus 관측, Tempo 트레이스·시간축 판정까지. 자세한 완료 기준은 2절 표.
- **Phase 20**(LLM 제안, `082b4ec`): 규칙 기반 후보가 놓친 명세를 LLM이 제안, 기존 검증기 게이트 재사용.
- **Phase 21**(장애 주입, `6abeb87`): `INJECT_FAULT`/`RELEASE_FAULT`, TTL 강제, 미해제 시 다음 실행 차단.
- **Phase 22**(되먹임과 자산화, `395eea7`): 22-A Profile 버전 재조정(CAS), 22-B 예외의 불변식 무력화
  거부, 22-C 오판 신고 → LLM 예외 초안 → 기존 승인 게이트, 22-D 회귀 재실행 트리거 API. 독립 리뷰
  발견사항 6건 전부 수정 완료(0절 참고 — 이 절들의 "커밋 대기" 표시는 지금은 낡았다. 전부 커밋됨).
- **명세 목록 조회 API**(`fd5cff5`, 이번 세션): `GET /api/targets/{targetSystemId}/test-specifications`.
  UI 승인 화면이 id 없이도 명세 목록을 볼 수 있게 하는 선행 작업. 상세 내용은 아래 남겨두되, 이제
  완료된 작업이라는 것만 확인하면 된다 — `git log --oneline -4`로 커밋 4개(fd5cff5, 395eea7, 6abeb87,
  d485258) 확인됨.

프론트엔드(`frontend/`)는 Phase 20~22와 이번 목록 API 어느 것도 건드리지 않았다 — 마지막 확인은
Phase 19 기준 `npm ci`, 46 tests, `tsc`, `vite build` 통과.

### 이제 개발해야 하는 것 — 사용자가 정한 순서: UI → Target 수정 → 전체 테스트/파일럿

1. **UI 작업 (3.1절, 10단계, 아직 착수 안 함).** 명세 엔진(Phase 17~22)에는 화면이 하나도 없다.
   3.1절의 "시작 전에 정할 것 둘"은 이제 둘 다 해소됐다 — 목록 API는 위에서 추가했고, 10단계
   순서는 사용자가 그대로 진행하기로 승인했다. **1번(공용 판정 어휘 컴포넌트)부터 시작하면 된다.**
   지난 세션에서 사용자가 "단계별로 나눠서 확인하면서 하자"고 명시했으므로, 10단계를 한 번에
   구현하지 말고 각 단계(또는 더 잘게)마다 결과를 보여주고 다음으로 넘어갈 것.
2. **Target 수정.** `TARGET_REQUIREMENTS.md` 6절 — 확인된 건 `X-ARL-Trial` → 스팬 속성 필터
   하나뿐. UI가 끝난 뒤에 손댄다.
3. **전체 테스트 / 파일럿.** `\\wsl.localhost\Ubuntu\home\jybeomss\sideProject`(eventful-commerce).
   맨 마지막에 붙인다. Phase 19 완료 기준을 실제 Target으로 처음 확인하는 자리다.

### 이 세션의 작업 제약 (다음 세션도 동일할 가능성이 높다)

- device_bash(사용자 로컬 Linux VM)만 쓸 수 있고 네트워크·Docker가 없어 `.\gradlew.bat clean check`를
  직접 못 돌린다 — 편집 후 원본을 다시 읽어 대조하고 줄 길이·괄호 균형만 스크립트로 확인한 뒤, 실제
  빌드 검증은 사용자가 직접 돌려서 알려줘야 한다.
- 이 device_bash는 `rm`을 못 하고, git 명령을 쓸 때마다 `.git/index.lock`(또는 `HEAD.lock`)이 남아
  다음 git 명령을 막는다 — 매번 `mv .git/index.lock _to_delete/...`로 치워야 한다(사용자의 IDE가
  같은 저장소를 열어 두고 있어서 그 프로세스가 잠깐씩 잠그는 것으로 보인다 — 재시도하면 대개 풀린다).
- **여러 결정이 걸린 작업은 한 번에 쭉 진행하지 말고 단계별로 확인받을 것.** 이번 세션 초반에
  사용자가 이 점을 명시적으로 지적했다.

---

## 0. Phase 22 독립 리뷰 + 발견사항 수정 (6건) — 구현 완료, 빌드 검증 완료(BUILD SUCCESSFUL), 커밋 완료(`395eea7`)

사용자가 "D까지 다하고 독립 리뷰 검토 ㄱㄱ"라고 승인해서, 22-D 구현을 마친 뒤 22-D/22-C에 대한
독립 리뷰를 진행했다. 22-A/22-B는 이전 세션에서 이미 리뷰를 마쳤다(각 절 참고).

**리뷰 방법:** `Agent` 도구로 이 대화와 무관한 완전히 새 컨텍스트의 서브에이전트 2개를
띄웠다(22-D용 1개, 22-C용 1개, `subagent_type: general-purpose`). 두 에이전트 모두 "리뷰만
하고 어떤 파일도 편집/수정하지 말 것"이라고 명시적으로 지시했다. 에이전트가 보고한 내용을
그대로 믿지 않고, 내가 직접 실제 소스 코드를 다시 읽어 각 발견사항이 진짜인지 하나하나
대조 검증한 뒤에만 사용자에게 보고했다. 보고 시점에는 **수정을 하나도 적용하지 않고** 6건을
전부 사용자에게 알리고 수정 여부를 물었다 — "리뷰 요청 = 수정 허가 아님"이라는 이 세션 초반의
피드백을 그대로 지켰다. 사용자가 수정 순서를 물어서 우선순위를 제안했고, 그중 범위가 가장 큰
6번(trialNumber)의 실익을 물어서 설명한 뒤, 사용자가 "그럼 5번도 같이 포함해서 수정해"라고
명시적으로 승인해서 6건 전부(+22-D 테스트 보강 1건) 구현했다.

수정한 6건:

1. **(22-D) `runOne()`의 catch가 `ClientRequestException`만 잡고 있었다.**
   `recoverConcurrentRun()`에서 raw `DuplicateKeyException`이 그대로 escape할 수 있는
   경로가 있는데(동시 재시도가 같은 최상위 Idempotency-Key로 부딪히는 실제 도달 가능한
   레이스), 이 경우 배치 전체가 500으로 죽어서 이미 계산된 다른 명세들의 결과까지 날아갔다.
   `catch (exception: Exception)` 폴백을 추가해서 어떤 예외든 배치를 죽이지 않고 개별
   `failureCode: "TEST_SPECIFICATION_REGRESSION_RUN_FAILED"` outcome으로 남게 했다.
2. **(22-D) "혼합 배치" 테스트가 사실 혼합이 아니었다.** 기존 5번째 테스트
   (`reports a per-specification failure without losing the rest of the batch`)는
   `hasBlockingRun`이 Target 전체에 걸리는 조건이라 배치 안 두 명세가 전부 실패했다 —
   진짜 "하나는 성공, 하나는 실패"를 증명하지 못했다. `policy.trials`를 서로 다르게 준 뒤
   Profile의 `maxTrials`를 낮춰서 한쪽만 검증 실패하게 만드는 방식으로 진짜 혼합 outcome
   테스트를 새로 추가했다(6번째 테스트).
3. **(22-C) `requestHash()`가 `modelKey`/`modelId`/`promptVersion`을 빼먹었다.**
   Phase 20 `TestSpecGenerationService`의 `configurationHash` 패턴(모델 해석을 멱등성
   조회보다 먼저 하고, 해시에 모델 설정 전체를 포함)과 다르게 구현되어 있었다. 같은 패턴으로
   맞췄다 — `report()`에서 모델 해석을 먼저 하고, `requestHash()`가 `modelKey`/`modelId`/
   `promptVersion`(+ 아래 6번 수정으로 `trialNumber`)까지 해시에 포함한다.
4. **(22-C) `draft()`의 "절대 던지지 않는다"는 KDoc이 실제로는 깨져 있었다.**
   `draftedDocumentJson()` 호출이 `try` 블록 밖에 있어서, 그 안의 `check(matched)`가 던지는
   `IllegalStateException`이 그대로 escape해 `executeOutboxJob()`의 범용 catch까지 가서
   REJECTED가 아니라 알 수 없는 FAILED로 오분류됐다. 호출을 `try` 안으로 옮기고
   `catch (exception: IllegalStateException)`을 추가해 명확한 REJECTED 사유로 남게 했다.
5. **(22-C) 동시에 같은 base 버전에 오판 초안을 두 개 이상 만들면 DB unique 제약 위반이
   그대로 노출됐다.** `draft()`에 `catch (_: DuplicateKeyException)`을 추가해서 "동시에 다른
   초안이 먼저 그 버전을 선점했다"는 명확한 REJECTED 사유로 남게 했다(범용 catch가 주던
   "Rejected due to an unexpected failure: DuplicateKeyException" 대신).
6. **(22-C) 오판 보고가 어떤 trial을 가리키는지 지정할 방법이 없었다.** `StopPolicy.RUN_ALL`
   때문에 한 run 안에 같은 invariant에 대한 VIOLATED verdict가 trial마다 다른 관측값으로
   여러 개 있을 수 있는데(도달 가능한 시나리오로 확인함), 기존 `requireViolatedVerdict()`는
   무조건 trialNumber가 가장 낮은 VIOLATED verdict 하나만 골랐다. 리뷰어가 실제로 본 trial과
   다른 trial의 증거로 LLM에 예외를 초안시킬 위험이 있었다. `trialNumber`를 API 요청부터
   도메인 레코드·store 커맨드·SQL/migration(`trial_number integer not null` 컬럼 +
   `>= 1` 체크 제약)·리포지토리·서비스 로직까지 끝까지 관통시켰다 — `requireViolatedVerdict()`가
   이제 trial을 먼저 찾고(`TEST_SPEC_MISJUDGMENT_TRIAL_NOT_FOUND`) 그 trial 안에서
   VIOLATED verdict를 찾는다(`TEST_SPEC_MISJUDGMENT_VERDICT_NOT_FOUND`). 새 실패 경로
   테스트 하나 추가(`refuses a report that references a trial the run does not have`).

수정한 파일(6건 전체, 22-D 관련은 위 0.1절과 겹침): `testspec/application/
TestSpecificationService.kt`(1번), `testspec/api/TestSpecRegressionRunApiIntegrationTests.kt`
(2번), `testspec/application/TestSpecMisjudgmentReportService.kt`(3·4·5·6번),
`testspec/domain/TestSpecMisjudgmentModels.kt`(6번, `trialNumber` 필드),
`testspec/application/port/TestSpecMisjudgmentReportStore.kt`(6번),
`testspec/application/ReportTestSpecMisjudgment.kt`(6번),
`testspec/infrastructure/sql/TestSpecMisjudgmentReportSql.kt`(6번),
`testspec/infrastructure/JdbcTestSpecMisjudgmentReportRepository.kt`(6번),
`db/migration/V27__phase22c_misjudgment_reports.sql`(6번, 아직 커밋 안 된 마이그레이션이라
V28을 새로 만들지 않고 V27을 직접 수정했다), `testspec/api/dto/
ReportTestSpecMisjudgmentRequest.kt`(6번, `@field:Min(1) trialNumber`),
`testspec/api/dto/TestSpecMisjudgmentReportResponse.kt`(6번),
`testspec/api/TestSpecMisjudgmentReportApiIntegrationTests.kt`(6번, 테스트 5개로 증가).

검증: 수정한 파일 전부 `cat -n`으로 다시 읽어 대조했고, 줄 길이(120자)·괄호/중괄호/대괄호
균형을 전체 변경 파일에 대해 스크립트로 재확인했다(위반 0건). `grep`으로
`ReportTestSpecMisjudgment(`/`NewTestSpecMisjudgmentReport(`/`requireViolatedVerdict(` 옛
시그니처를 참조하는 곳이 남아 있는지 전체 검색해서 orphan 호출부가 없음을 확인했다.
`git status --short`로 예상한 파일 집합(29개, 수정 15 + 신규 14)과 정확히 일치하는지
확인했다.

**사용자가 직접 `.\gradlew.bat clean check`를 돌려서 detekt 위반 2건이 나왔고, 둘 다 고쳤다** (device_bash로는
빌드를 못 돌리므로 이 두 건은 실제 빌드를 돌려봐야만 드러나는 것이었다):

- `TestSpecMisjudgmentReportService.kt`: `parseDraftedException()`이 throw 3개(JSON 파싱 실패/condition
  누락/description 누락)라 detekt `ThrowsCount`(최대 2) 위반. condition/description 누락 처리를
  `requireDraftedField(node, field)` private 헬퍼로 뽑아서 `parseDraftedException()`은 throw 1개만
  남기고, 헬퍼가 나머지 throw 1개를 담당하게 했다.
- `JdbcTestSpecMisjudgmentReportRepository.kt`: 함수 13개로 `TooManyFunctions`(최대 11) 위반. 같은
  상황의 다른 Jdbc 레포지토리들(`JdbcAnalysisRunRepository` 등)이 이미 쓰던 관행대로 `@Repository`
  아래 `@Suppress("TooManyFunctions") // 이유`를 추가했다(오판 보고 하나의 생명주기+조회+행 매핑을
  한 어댑터가 담당한다는 이유).

두 파일 다 수정 후 줄 길이·괄호 균형 재확인 완료. 재실행한 `.\gradlew.bat clean check`가
**BUILD SUCCESSFUL**로 통과했다(사용자가 직접 확인). **22-A/22-B/22-C/22-D + 이번 리뷰 수정
6건 전체가 이제 빌드·detekt·테스트를 통과한 상태다.** 커밋은 아직 안 했다(사용자의 별도 요청
대기).

---

## 0.1 Phase 22-D(회귀 재실행 트리거 API) — 구현 완료, 빌드 검증 완료, 커밋 완료(`395eea7`)

Phase 22의 마지막 부분, 22-D를 구현했다. 이 세션도 device_bash만 썼고 `.\gradlew.bat clean
check`를 못 돌렸다 — 아래 0.2/0.3/0.4절부터 이어지는 같은 제약. 편집은 `cat -n`으로 원본을
읽거나 새 파일을 통째로 쓴 뒤, 편집/작성한 전체 파일을 다시 읽어 대조했고 줄 길이(120자)·괄호
균형도 스크립트로 확인했다. **다음 세션(또는 사용자)이 22-A/22-B/22-C와 함께
`.\gradlew.bat clean check`로 직접 확인해야 한다.**

무엇을 했는지: `POST /api/targets/{targetSystemId}/test-specifications/regression-runs` —
한 Target의 현재 APPROVED 명세를 전부 골라 기존 `TestSpecificationService.execute()`를
그대로 반복 호출하는 동기 배치 트리거. 외부 CI가 배포 뒤 회귀를 걸 수 있게 트리거만 하고,
ARL이 스케줄링·배포를 대신하지 않는다(ARL의 명시적 비목표, `HANDOFF.md` 3절과 동일 원칙).

설계에서 결정한 것들:

- **동기 배치 루프로 갔다(비동기 배치+폴링 대신).** 개별 명세 실행(`POST
  /test-specifications/{id}/runs`)이 이미 동기이므로, "전부 실행" 엔드포인트도 같은 게이트를
  반복하는 편이 새 비동기 패턴을 하나 더 들여오는 것보다 일관적이라고 판단했다. 사용자에게
  두 방식의 트레이드오프를 설명하고 동기 배치를 추천했으며(응답이 느려질 수 있지만 CI 쪽에서
  타임아웃을 넉넉히 잡으면 되고, 필요해지면 나중에 비동기로 바꿀 수 있다), 사용자가 그 판단에
  맡겨서 이 방향으로 구현했다.
- **Phase 13의 `TargetTestBatchService`(`targetspec` 패키지)는 재사용하지 않았다.** 읽어서
  확인한 결과 그건 `TargetTestCandidate`(HEALTH_REACHABILITY/HTTP_STATUS_ASSERTION 프로브)라는
  완전히 다른, 더 오래된 도메인을 다루는 코드라서 `testspec`의 선언적 명세 엔진과 무관하다.
  22-D는 `testspec` 안에서 독립적으로 설계했다.
- **같은 specKey에 APPROVED 버전이 여럿이면 최신 버전만 실행한다.** `supersede()` 호출
  지점을 grep해서 확인했는데, 새 버전을 승인해도 그 specKey의 이전 APPROVED 버전이 자동으로
  superseded 되지 않는다(22-A의 Profile Version 재조정 경로에서만 `supersede()`가 불린다).
  그대로 두면 배치가 같은 specKey를 중복 실행하거나 이미 지나간 버전을 되살릴 수 있어서,
  `triggerRegressionRuns()`가 specKey로 묶어 `maxBy(StoredTestSpecification::version)`만
  골라 실행하도록 했다(`TestSpecificationService.kt` KDoc과 `TestSpecificationStore.
  findApprovedByTarget()` 자체에도 이유를 남겼다).
- **한 명세의 `execute()` 실패가 배치 전체를 날리지 않는다.** `execute()`가 던지는
  `ClientRequestException`(예: 같은 Target에 복구 필요한 이전 run이 남아 있어서 나는
  `TEST_SPECIFICATION_RECOVERY_REQUIRED`)을 명세별로 잡아서 `TestSpecRegressionRunOutcome`에
  `failureCode`/`failureMessage`로 담는다 — Phase 20 `toCandidate()`, 22-C `draft()`가 이미
  쓰던 "한 항목의 실패가 나머지를 잃게 하면 안 된다" 원칙을 그대로 따랐다. HTTP 응답은 항상
  200이고, 배치 안에서 성공/실패가 섞여도 실패한 항목은 `run: null` + 실패 코드로, 성공한
  항목은 `run`에 기존 `TestSpecRunResponse`를 그대로 채워서 함께 돌려준다.
- **명세별 파생 Idempotency-Key 길이를 이 엔드포인트에서만 더 좁게 제한한다.** `execute()`
  내부에서 `"$idempotencyKey:${specification.id}"`를 파생시켜 쓰는데(콜론 + 36자 UUID = 37자
  추가), 호출자가 준 요청 레벨 키가 `execute()` 자체의 200자 한도에 거의 닿아 있으면 파생 키가
  `test_spec_run.idempotency_key`(varchar(200))를 넘칠 수 있다. 그래서
  `triggerRegressionRuns()`는 요청 레벨 Idempotency-Key를 160자로 더 좁게 검증한다
  (160 + 37 = 197 < 200, 여유를 남겼다).
- **응답 DTO는 새로 만들지 않고 최대한 재사용했다.** `TestSpecRunResponse.from(view)`를
  성공한 항목마다 그대로 쓰고, 배치 전체를 감싸는 `TestSpecRegressionRunsResponse`
  (`targetSystemId`, `runs: List<TestSpecRegressionRunOutcomeResponse>`)만 새로 만들었다.

새 파일: `testspec/api/dto/TestSpecRegressionRunsResponse.kt`(`TestSpecRegressionRunOutcomeResponse`,
`TestSpecRegressionRunsResponse`), `testspec/api/TestSpecRegressionRunApiIntegrationTests.kt`(신규
테스트 5개). 기존 파일 수정: `testspec/application/port/TestSpecificationStore.kt`
(`findApprovedByTarget()` 추가), `testspec/infrastructure/sql/TestSpecificationSql.kt`
(`FIND_APPROVED_BY_TARGET` 추가), `testspec/infrastructure/JdbcTestSpecificationRepository.kt`
(구현 추가), `testspec/application/TestSpecViews.kt`(`TestSpecRegressionRunOutcome` 추가),
`testspec/application/TestSpecificationService.kt`(`triggerRegressionRuns()`/`runOne()` 추가),
`testspec/api/TestSpecificationController.kt`(`POST /targets/{targetSystemId}/
test-specifications/regression-runs` 엔드포인트 추가). 새 migration 없음(기존 테이블·컬럼만
읽는다). 자세한 목록은 7절.

새 통합 테스트 5개(`TestSpecRegressionRunApiIntegrationTests.kt`, 픽스처는 기존
`TestSpecificationApiIntegrationTests.kt`와 같은 Profile 활성화/원복 패턴을 그대로 복제):

| 테스트 | 무엇을 고정했나 |
|---|---|
| `executes every approved specification across distinct specKeys and reports each outcome` | 서로 다른 specKey의 APPROVED 명세 2개가 모두 실행되고 각각의 outcome이 성공(run.status=COMPLETED)으로 보고됨 |
| `runs only the highest approved version when a specKey has more than one approved version` | 같은 specKey에 APPROVED 버전이 2개 있어도 배치는 버전 2(최신)만 실행 |
| `returns an empty result for a target with no approved specifications` | APPROVED 명세가 하나도 없으면 200 + 빈 `runs` 배열 |
| `replaying the same Idempotency-Key returns the same runs instead of executing again` | 같은 Idempotency-Key로 두 번 호출해도 같은 run이 반환되고 `test_spec_run`에 새 행이 추가되지 않음 |
| `reports a per-specification failure without losing the rest of the batch` | 복구 필요한 이전 run으로 Target이 막혀 있으면 배치의 두 명세 모두 `failureCode=
TEST_SPECIFICATION_RECOVERY_REQUIRED`로 보고되지만 HTTP 응답은 200이고 두 outcome이 모두 온전히 돌아옴(배치 격리 증명) |

**독립 리뷰 완료, 발견사항 2건 모두 수정 완료.** 자세한 내용은 위 0절 참고. 이 절의 코드는
이제 리뷰 반영이 끝난 최신 상태다(`runOne()` catch 확장, 진짜 혼합 배치 테스트 추가).

Phase 22는 22-A/22-B/22-C/22-D로 계획된 범위가 전부 구현됐다. 이 뒤로 더 계획된 하위 작업은
없다.

---

## 0.2 Phase 22-C(오판 되먹임 → LLM 예외 초안 → 기존 승인 게이트) — 구현 완료, 빌드 검증 완료, 커밋 완료(`395eea7`)

Phase 22의 세 번째 부분, 22-C를 구현했다. 이 세션도 device_bash만 썼고 `.\gradlew.bat clean
check`를 못 돌렸다 — 아래 0.3절부터 이어지는 같은 제약. 편집은 `cat -n`으로 원본을 읽거나 새
파일을 통째로 쓴 뒤, 편집/작성한 전체 파일을 다시 읽어 대조했고 줄 길이(120자)도 스크립트로
확인했다. **다음 세션(또는 사용자)이 22-A/22-B와 함께 `.\gradlew.bat clean check`로 직접
확인해야 한다.**

무엇을 했는지: 리뷰어가 "이 위반은 오판이다"라고 보고하면, 그 판정을 낸 불변식·관측값·사유를
LLM에게 보여 주고 좁은 예외 하나를 초안하게 한 뒤, **새 승인 경로를 하나도 만들지 않고** 기존
`TestSpecificationService.create()`(source: `MODEL_PROPOSED`)/`approve()` 게이트로 그대로
통과시킨다. 오케스트레이션은 Phase 20(`TestSpecGenerationService`)을 거의 그대로 베꼈다 —
Idempotency-Key → outbox job(`MISJUDGMENT_EXCEPTION_DRAFT`, 분석 permits 그룹) → 기존
`TestSpecProposalModel` 포트(새 모델 포트 불필요, 이미 범용) → 검증기 통과/거부 기록. 새로
만든 건 딱 하나, `POST /api/targets/{targetSystemId}/test-spec-misjudgment-reports`(보고
접수) + `GET /api/test-spec-misjudgment-reports/{reportId}`(폴링)뿐이고, 초안이 통과해서
생긴 새 `PENDING_APPROVAL` 명세 버전은 **기존** `POST /api/test-specifications/{id}/approve`로
승인한다 — 22-C 전용 승인 엔드포인트는 없다.

설계에서 결정한 것들:

- **입력 번들을 저장하지 않는다.** Phase 20은 `inputBundleJson`을 run에 영속화하지만, 22-C는
  그럴 필요가 없다고 판단했다 — 오판 보고가 참조하는 명세 문서(버전별로 불변)와 완료된 run의
  trial verdict(한번 저장되면 안 바뀜)가 이미 결정론적으로 재구성 가능한 불변 데이터라서,
  outbox job 실행 시점에 `specificationService.findSpecification()`/`findRun()`으로 다시
  읽어서 매번 같은 입력 번들을 새로 만든다. 큰 텍스트 컬럼 하나를 아꼈다.
- **명세 문서에 예외를 끼워 넣는 방법은 타입 있는 역직렬화가 아니라 `Map<String, Any?>` 기반
  읽기-변경-재작성이다.** 저장소 전체를 grep해서 확인했는데 기존 코드는 전부 `readTree()`(읽기
  전용 `JsonNode` 순회) 아니면 `writeValueAsString(Map(...))`(새 문서를 처음부터 조립)만 쓰고,
  기존 문서를 부분 수정해서 다시 쓰는 코드는 하나도 없었다. `TestSpecParser`가 이미 한 번
  검증한 신뢰할 수 없는 입력이므로, `Invariant`→JSON 역방향 매핑 전체를 새로 만드는 대신
  `objectMapper.readValue(documentJson, Map::class.java)`로 읽고 해당 invariant의
  `exceptions` 리스트에 새 예외 하나를 불변식 함수형으로 덧붙인 뒤(`invariant + mapOf(...)`
  스타일, 원본 mutate 안 함) `version`을 `+1` 하고 다시 `writeValueAsString`한다
  (`TestSpecMisjudgmentReportService.draftedDocumentJson()`).
- **버전 충돌은 별도로 계산하지 않고 기존 게이트가 그대로 걸러내게 둔다.** 초안이 참조하는
  명세 버전 다음 번호(`specification.version + 1`)를 낙관적으로 쓰고, 그사이 실제로 더 새
  버전이 생겼으면 `TestSpecificationService.create()`의 `requireNextVersion()`이
  `TEST_SPECIFICATION_VERSION_CONFLICT`를 던지게 두고 그걸 REJECTED 사유로 기록한다 — 22-C가
  버전 계산 로직을 중복으로 갖지 않는다.
- **거부 사유에 `condition`/`description` 모두 남긴다(모델이 실제로 뭘 시도했는지).** DRAFTED든
  REJECTED든 `drafted_condition`/`drafted_description`은 항상 채운다 — Phase 20이 거부된 후보의
  `documentJson`을 남기는 것과 같은 이유다.
- **22-B의 무력화 거부 규칙은 손대지 않고 그대로 적용받는다.** 초안된 예외도 다른 명세와 똑같이
  `TestSpecValidator.expressionViolations()`를 통과해야 하므로, 모델이 조건 `true`나 관측값을
  전혀 참조하지 않는 예외를 제안하면 22-B가 이미 추가한 검사가 그대로 잡는다 — 통합 테스트로
  이 조합(22-C + 22-B)이 실제로 작동하는지 증명했다(아래).

새 파일: `testspec/domain/TestSpecMisjudgmentModels.kt`(`TestSpecMisjudgmentReportStatus`,
`TestSpecMisjudgmentReportRecord`), `testspec/application/port/TestSpecMisjudgmentReportStore.kt`,
`testspec/application/port/TestSpecMisjudgmentSettings.kt`,
`testspec/application/ReportTestSpecMisjudgment.kt`(명령),
`testspec/application/TestSpecMisjudgmentReportService.kt`(핵심 오케스트레이션),
`testspec/infrastructure/TestSpecMisjudgmentProperties.kt`,
`testspec/infrastructure/sql/TestSpecMisjudgmentReportSql.kt`,
`testspec/infrastructure/JdbcTestSpecMisjudgmentReportRepository.kt`,
`testspec/api/TestSpecMisjudgmentReportController.kt`,
`testspec/api/dto/ReportTestSpecMisjudgmentRequest.kt`,
`testspec/api/dto/TestSpecMisjudgmentReportResponse.kt`, `V27__phase22c_misjudgment_reports.sql`,
`testspec/api/TestSpecMisjudgmentReportApiIntegrationTests.kt`(신규 테스트 4개). 기존 파일 수정:
`OutboxJob.kt`(`MISJUDGMENT_EXCEPTION_DRAFT` 추가), `JobExecutionCapacity.kt`(분석 permits
그룹에 배분), `OutboxJobHandlerConfiguration.kt`(핸들러 등록),
`TestSpecExecutionConfiguration.kt`(재시작 복구 `ApplicationRunner` 추가). 자세한 목록은 7절.

새 통합 테스트 4개(`TestSpecMisjudgmentReportApiIntegrationTests.kt`, 픽스처 run/trial은
`TestSpecRunStore`를 직접 호출해 조작 — 기존
`TestSpecificationApiIntegrationTests.kt`의 "blocks a new execution while an earlier run..."
테스트들이 이미 쓰던 것과 같은 방식):

| 테스트 | 무엇을 고정했나 |
|---|---|
| `drafts a narrow exception and lets the resulting specification be approved` | 위반된 verdict에 대한 보고가 DRAFTED로 끝나고, 새 `PENDING_APPROVAL` 버전(version 2)이 실제로 저장되며, 기존 `/approve`로 승인됨 |
| `rejects a drafted exception that would nullify the invariant instead of narrowing it` | 모델이 조건 `true`를 제안하면 22-B 검사가 거부하고, REJECTED로 끝나며 새 명세는 생성되지 않음(22-B와의 조합 증명) |
| `returns the same report for a repeated idempotency key without calling the model again` | 같은 Idempotency-Key 재요청이 모델을 다시 부르지 않고 같은 보고를 반환 |
| `refuses a report when the run has no VIOLATED verdict for the named invariant` | 지정한 invariant에 VIOLATED verdict가 없는 run을 참조하면 즉시 409(`TEST_SPEC_MISJUDGMENT_VERDICT_NOT_FOUND`) |

**독립 리뷰 완료, 발견사항 4건 모두 수정 완료.** 자세한 내용은 위 0절 참고. 이 절의 코드와
테스트 표는 리뷰 반영 전 기준이라 일부 낡았다 — 특히 `requestHash()`는 이제 `modelKey`/
`modelId`/`promptVersion`/`trialNumber`까지 포함하고, `ReportTestSpecMisjudgment`에는
`trialNumber` 필드가 추가됐다(테스트도 5개로 늘었다). 0절에서 각 수정의 이유를 설명한다.

다음: 22-D(회귀 재실행 트리거 API) — 구현 완료, 위 0.1절 참고.

---

## 0.3 Phase 22-B(예외의 불변식 무력화 거부) — 구현 완료, 빌드 검증 완료, 커밋 완료(`395eea7`)

Phase 22의 두 번째 부분, 22-B를 구현했다. 이 세션도 device_bash만 썼고 `.\gradlew.bat clean
check`를 못 돌렸다 — 아래 0.4절부터 이어지는 같은 제약. 편집은 `cat -n`으로 원본을 읽고 정확한
문자열 치환으로 했고, 편집 후 전체 파일을 다시 읽어 대조했다. **다음 세션(또는 사용자)이 22-A와
함께 `.\gradlew.bat clean check`로 직접 확인해야 한다.**

무엇을 했는지: `TEST_SPEC.md` 14절("불변식을 통째로 무력화하는 예외" 거부)과 12절
("`조건: true` 같은 예외는 불변식을 없애는 것과 같아서 거부한다")을 구현했다. `TestSpecValidator`가
이미 모든 표현식을 컴파일하던 자리(`expressionViolations`)에, 예외 조건 전용 검사
`exceptionViolations`를 추가했다 — 컴파일이 성공하면 (1) 조건이 문자 그대로 `true`인지, (2) 조건이
명세 자신의 관측값을 하나도 참조하지 않는지(`1 == 1`처럼 상수만 있는 경우 포함) 확인해서 둘 중
하나라도 해당하면 거부한다. 두 번째 검사는 새로 만들지 않고 `SpecExpressionEnvironment.compile()`이
이미 CEL AST에서 뽑아 주던 `CompiledExpression.referencedIdentifiers`를 그대로 재사용했다 — 이
표현식에 전달되는 식별자 집합이 관측 id뿐이므로(다른 바인딩은 없음), 컴파일이 성공한 이상 참조된
식별자는 전부 관측 id이고 그 집합이 비어 있으면 곧 "관측값을 하나도 안 씀"과 같다. 별도의
새 CEL 순회 로직을 만들지 않았다.

기존 동작에 회귀는 없다 — 컴파일 실패 시 메시지 형식("Exception on '...'")을 그대로 보존해서
기존 테스트(`refuses an exception that cannot be evaluated`)는 그대로 통과해야 한다.
`TestSpecValidatorTests.kt`에 새 테스트 3개를 추가했다: 리터럴 `true` 거부, 관측값 미참조(`1 == 1`)
거부, 실제 관측값을 좁게 참조하는 예외는 통과(회귀 방지 — 정당한 예외까지 막지 않는지 확인).

이 검사는 검증기(`TestSpecificationService.create()`/`approve()`가 부르는 게이트) 안에 있으므로,
아직 만들지 않은 22-C(LLM이 초안하는 예외)도 같은 게이트를 그대로 통과해야 하고 별도로 손댈
필요가 없다 — Phase 20 때 확정한 "LLM 출력도 기존 검증기를 그대로 통과해야 한다" 원칙과 같다.

다음(아직 시작 안 함): 22-C(오판 보고 → LLM 예외 초안 → 기존 승인 게이트), 22-D(회귀 재실행
트리거 API).

---

## 0.4 Phase 22-A(Profile 버전 재조정) — 구현 완료, 빌드 검증 완료, 커밋 완료(`395eea7`)

이번 세션에서 Phase 22(되먹임)의 첫 부분인 22-A(Profile 버전 재조정)를 구현했다. **이 세션도
`.\gradlew.bat clean check`를 한 번도 돌리지 못했다** — 사용자 기기의 device_bash만 쓸 수 있었고
네트워크·Gradle 캐시·Docker 전부 없었다(0.5절부터 이어지는 같은 제약). 편집은 전부 기존 파일을
`cat -n`으로 먼저 읽고 정확한 원본 텍스트에 대해 문자열 치환으로 했고, 편집 후 전체 파일을 다시
읽어 대조했다 — 컴파일이 되는지, 테스트가 실제로 통과하는지는 **다음 세션(또는 사용자)이
`.\gradlew.bat clean check`로 직접 확인해야 한다.**

무엇을 했는지: `TestSpecificationService.approve()`/`execute()`가 저장된 명세의
`profileVersionId`와 현재 활성 Profile Version이 다를 때 무조건 `supersede()`하던 것을, "명세를
새 Profile Version에 대해 다시 검증해서 여전히 유효하면 `profileVersionId`만 옮기고 재승인 없이
계속 쓰고, 실제로 참조가 깨졌을 때만 supersede"하는 방식(`reconcileProfileVersion`)으로 바꿨다 —
`TEST_SPEC.md` §11이 요구하는 동작이다. 새 저장소 메서드
`TestSpecificationStore.reviseProfileVersion(id, expectedProfileVersionId, profileVersionId)`
(상태·버전·승인은 안 건드리고 Profile Version 포인터만 이동, `expectedProfileVersionId`에 대한
compare-and-swap — 이유는 아래 독립 리뷰 항목 참고)를 추가했고, SQL/JDBC 구현을 붙였다.

기존 통합 테스트 `supersedes and blocks a specification when its Profile Version is no longer
active`의 전제가 낡았다는 걸 코드를 읽고 확인했다: 이 테스트가 쓰는 `executionProfileFrom()`
헬퍼는 `base` 인자의 내용과 무관하게 항상 똑같은 하드코딩된 `testSpecExecution`을 만들어서
"교체된" Profile이 사실은 기존과 capability가 완전히 같았다 — 새 재조정 로직에서는 이런
replacement가 더 이상 supersede를 일으키지 않는 게 맞는 동작이라 이 테스트는 통과할 수 없게
됐다. 이는 회귀가 아니라 이 테스트가 Phase 22-A 이전의 "무조건 supersede" 동작을 그대로
인코딩하고 있었다는 증거였다. `executionProfileFrom()`에 `maxTrials` 파라미터(기본값 3)를
추가해서 실제로 호환 불가능한 replacement(`maxTrials = 0`, 이 WAIT-only 픽스처의
`policy.trials = 1`을 깨뜨림)를 만들 수 있게 했고, 테스트 이름을 실제로 증명하는 내용에 맞게
바꿨다("Profile Version bump가 참조를 깨뜨릴 때"). 그리고 새 테스트
`keeps a specification approved and executable across a compatible Profile Version bump`를
추가해서 호환되는 bump(같은 capability)에서는 승인된 명세가 재승인 없이 그대로 실행되고
`profileVersionId`만 갱신됨을 고정했다.

독립 리뷰(제 코드를 처음 보는 별도 에이전트 + 직접 코드 대조 검증)가 결함 2건을 찾았다, 둘 다 고쳤다:

- **`REVISE_PROFILE_VERSION`에 CAS 가드가 없었다.** 최초 구현은 `where id = :id`만으로
  `profile_version_id`를 덮어썼다 — `APPROVE`(`status = :pending`)와 `SUPERSEDE`
  (`status in (...)`)는 둘 다 쓰던 조건부 갱신을, 새로 추가한 이 메서드만 빠뜨렸다. 두 요청이
  겹쳐서(관리자가 Profile Version을 활성화하는 그 순간과 맞물려) 서로 다른 활성 Profile을 읽고
  각각 `revise`/`supersede`를 부르면, 나중에 도착하는 쪽이 앞선 쓰기를 조용히 덮어써서 결과가
  "실제 현재 상태의 함수"가 아니게 될 수 있었다(검증을 우회하는 건 아니다 — `revise`는 항상 그
  직전에 성공한 재검증 뒤에만 불리므로, 최악의 경우도 "멀쩡한 명세가 레이스로 supersede됨" 정도다).
  `reviseProfileVersion`에 `expectedProfileVersionId` 파라미터를 추가해 SQL을
  `where id = :id and profile_version_id = :expectedProfileVersionId and status in (:draft,
  :pending, :approved)`로 바꿨다(CAS). 스왑이 실패하면(다른 요청이 이미 같은 행을 옮겼거나
  supersede했다는 뜻) `reconcileProfileVersion()`이 행을 다시 읽어서 이미 같은 목표
  Profile Version에 가 있으면(동시 요청이 같은 결론에 먼저 도달한 것) 성공으로 처리한다.
  `JdbcTestSpecPersistenceTests.kt`에 CAS 자체를 직접 증명하는 테스트를 추가했다(정상 스왑,
  낡은 기대값 거부, 존재하지 않는 id, superseded 행에 대한 거부 — 4가지 모두 스왑 후에도
  `profileVersionId`가 실제로 그대로인지까지 확인). HTTP 레벨 동시성 테스트(두 실행 스레드가
  Profile 활성화와 겹치는 것)는 결정적으로 재현하기 어려워 시도하지 않았다 — CAS 자체를 저장소
  계층에서 직접 증명하는 쪽을 택했다.
- **잡은 예외를 안 쓰면서 이름을 붙였다.** `catch (exception: SpecValidationException) { false }`
  — `exception`을 본문에서 안 쓴다. 이 파일 자신을 포함해 저장소 전체가 "안 쓰는 캐치 예외는
  `_`로 이름 붙인다"는 관례를 쓰고(`config/detekt/baseline.xml`에 이미 다른 파일 8곳의
  grandfather 항목이 있고, 이 파일 자신도 340행에 `catch (_: IllegalArgumentException)` 예가
  있다), 이 새 캐치만 빠뜨렸다 — detekt `SwallowedException`(baseline에 없는 새 위반)에 걸려
  `.\gradlew.bat check`를 그대로 실패시켰을 가능성이 높다. `catch (_: SpecValidationException)`로
  고쳤고, 형제 코드(`TestSpecGenerationService.toCandidate()`)가 같은 재검증 자리에서
  `SpecParseException`도 같이 잡는 것과 맞춰 그것도 추가했다(현재 `TestSpecParser.parse()`가
  `profileVersionId`만 다르게 받는 재파싱에서는 도달할 수 없는 방어적 코드지만, 형제 패턴과의
  일관성을 위해 넣었다).

리뷰가 확인하고 결함이 아니라고 판단한 것: 승인 우회 위험(재검증은 저장된 문서가 새 Profile이
"허용하지 않는" 것을 요구하지 않는지만 보므로, Profile이 능력을 넓히는 방향으로 바뀌어도 이미
승인된 명세가 스스로 선언하지 않은 새 능력에 닿을 길이 없다), 예외 무력화·LLM 신뢰·LOCAL/TEST
제한 등 이 프로젝트의 절대 규칙(5절) 어디에도 영향 없음.

(2026-08-23 갱신: 22-B도 이어서 구현했다 — 위 0절 참고.) 세부 설계는 사용자와
`AskUserQuestion`으로 확정한 것을 이 세션 대화에만 남겼고 아직 문서화하지 않았다 — 다음 세션이
이어받으려면 0절과 3절을 먼저 읽을 것.

---

## 0.5 Phase 21(장애 주입) — 커밋 완료 (`6abeb87`)

(2026-08-23 갱신: 사용자가 `.\gradlew.bat clean check` 로컬 빌드 성공을 확인했고 `6abeb87`로
커밋·푸시됐다. 아래는 그 세션 당시 기록으로, 검증 과정과 설계 이유가 남아 있어 그대로 둔다.)

이번 세션에서 Phase 21(장애 주입)을 구현했다. **`.\gradlew.bat clean check`를 이 세션에서 한 번도
돌리지 못했다** — 작업 환경이 사용자 기기의 로컬 Linux VM(device_bash)뿐이었고, 거기엔 네트워크가
없어(Maven Central 접근 확인 시도 실패) Gradle 9.5.1 배포판조차 받을 수 없었다. `gradlew`(Windows용
`.bat` 아닌 리눅스 래퍼)는 있었지만 의미가 없었다. 즉 **컴파일이 되는지도, 아래 설명하는 테스트가
실제로 통과하는지도 이 세션은 직접 확인하지 못했다.** 코드는 기존 파일을 전부 읽고 정확한 원본
텍스트에 대해 문자열 치환으로 편집했고, 편집 후 전체 파일을 다시 읽어 괄호 균형·타입·참조를 손으로
대조했다 — 이게 이번에 할 수 있었던 전부다. **다음 세션(또는 사용자)이 가장 먼저 할 일은
`.\gradlew.bat clean check`를 돌려서 이 절의 주장을 실제로 검증하는 것이다.**

무엇을 했는지: `SpecWorkloadExecutor`가 `INJECT_FAULT`/`RELEASE_FAULT` 워크로드 단계를 실제로
실행하게 했고, `TestSpecRunner`가 트라이얼이 죽어도 남은 결함 핸들을 `finally`에서 해제하며, 해제
실패는 기존 `cleanupVerified`/`RECOVERY_REQUIRED` 인터록에 그대로 합류해 다음 실행을 차단한다.
`TARGET_REQUIREMENTS.md`/`TEST_SPEC.md`/`README.md`/`target-profile.sample.yaml`도 갱신했다.
`INFRA_ACTION`/`INFRA_RESTORE`(인프라 정지·재시작)는 사용자가 이번 Phase 범위에서 제외하기로
명시적으로 결정했다 — Docker/K8s 소켓 접근이 필요한 별도의, 더 위험한 결정이라서다. 자세한 설계는
아래 "Phase 21에서 확정한 설계 결정"에 있다.

독립 리뷰(제 코드를 처음 보는 별도 에이전트 + 직접 코드 대조 검증)가 결함 1건을 찾았다:

- **`FaultInjectionService`의 요청 바디가 이스케이프 없이 문자열 치환으로 채워짐.**
  `INJECT_BODY_TEMPLATE`/`RELEASE_BODY_TEMPLATE`은 `SpecReferenceResolver.resolve()`(순수
  정규식 치환, JSON 이스케이프 없음)로 채워지는데, `faultScope`는 `TestSpecParser`에서 자유
  텍스트로 파싱되고 `TestSpecValidator`는 `faultType`/`faultTtl`만 검증할 뿐 `faultScope`는
  전혀 검증하지 않는다. 즉 명세(LLM이 제안한 것 포함, `source = MODEL_PROPOSED`)가 `scope`에
  `"`를 넣으면 Target으로 보내는 JSON 바디를 조작·확장할 수 있었다 — 코드 자신이 명시한 설계
  의도("명세는 fault type, scope, TTL만 지정할 수 있다")를 정면으로 어기는 결함이었다.
  `FaultInjectionService`에 `jsonEscaped()`를 추가해 `runId`/`faultType`/`scope`/`faultId`
  (Target이 돌려주는 값도 방어적으로) 네 값 모두 템플릿에 넣기 전에 JSON 이스케이프하도록
  고쳤다. `SpecHttpCaller`/`SpecReferenceResolver`가 다른 모든 명세 기반 요청 바디에도 똑같이
  이스케이프 없는 치환을 쓰는 것은 Phase 21 이전부터 있던 기존 패턴이라 이번엔 건드리지
  않았다 — 범위를 벗어난 구조 변경이고, 빌드를 돌릴 수 없는 이 세션에서 검증도 못 하는 위험을
  지는 것보다 새로 생긴 구멍만 좁혀 막는 쪽을 택했다. 기존/신규 테스트가 전부 리터럴 값
  (`"PAYMENT_FAILURE"`, `"f-1"`, `"run-1"` 등)만 써서 `jsonEscaped()`가 항등 변환이라 테스트
  변경은 필요 없었다.

이 수정도 이 세션은 빌드로 검증하지 못했다 — 위 0절의 한계가 이 수정에도 그대로 적용된다.

**추가: 사용자가 실제로 `.\gradlew.bat clean check`를 돌려서 나온 결과 (이 세션이 처음으로 받은 실제
빌드 피드백).** 세 번에 걸쳐 다음을 고쳤다 — 전부 사용자가 붙여준 로그를 보고 고친 뒤 파일을
직접 대조 확인했고, 다음 실행 결과는 아직 못 받았다.

- detekt 7건(위에 나열: LongMethod, TooManyFunctions, MagicNumber ×2, ThrowsCount ×2, ReturnCount) —
  실제 구조 개선(함수 추출, 매직넘버 상수화) 또는 이 저장소가 이미 쓰던 `@Suppress` + 이유 주석
  방식으로 고쳤다.
- 그 수정으로 붙인 이유 주석 4개가 120자 줄 길이 제한(`MaxLineLength`, detekt 기본값)을 넘겨서
  다시 실패 — trailing comment를 어노테이션 위 별도 줄(1~2줄)로 옮겨 고쳤다. 이 저장소가 긴 이유
  주석에 이미 쓰던 방식과 동일하다.
- 그다음 컴파일까지 통과해서 테스트가 처음 돌았고, `TestSpecParserTests`의 `reports a step it
  declares but cannot run yet`이 실패했다 — `expected: [INJECT_FAULT] but was: []`. 원인은 회귀가
  아니라 이 테스트가 Phase 21 이전에 작성돼서: "아직 실행 못 하는 step kind"의 예시로 `INJECT_FAULT`를
  썼는데, Phase 21이 정확히 그 kind를 지원 목록(`TestSpecModels.kt`의 `SUPPORTED_STEPS`)에 넣었으니
  이 테스트의 전제 자체가 낡은 것이었다. `SpecWorkloadExecutorTests`의 `refuses to run a step kind
  this build still cannot execute`가 이미 이 세션 안에서 같은 문제를 `INFRA_ACTION`으로 바꿔 대응해
  놓은 전례가 있어서, `TestSpecParserTests`도 같은 방식으로 고쳤다 — 픽스처를 `FAULT_SPEC`에서
  `UNSUPPORTED_STEP_SPEC`으로 이름을 바꾸고 workload step을 `INJECT_FAULT`에서 `INFRA_ACTION`
  (`action: STOP`, `target: payment-service`, `maxHold: 30000`)으로 바꿨다. **다음 세션(또는
  사용자)이 가장 먼저 할 일은 여전히 `.\gradlew.bat clean check`를 다시 돌려서 이번엔 정말
  끝까지 통과하는지 보는 것이다.**

---

## 1. 한 줄 요약 (Phase 20)

Phase 20(LLM 제안)은 **ARL 쪽이 끝났다.** `DESIGN3.md`가 적어 둔 완료 기준 — "규칙 생성기가
못 찾은 유효한 테스트를 LLM이 1개 이상 찾을 것" — 을 만족하는 경로가 구현되고 테스트로 고정됐다.
새 규칙 기반 생성기를 따로 만들지 않고 Phase 12의 `TestCandidateService`를 그대로 재사용해서
"그 목록에 없던 유효한 테스트를 LLM이 찾는가"만 답하도록 범위를 좁혔다(설계 재검토 결과 채택한 B안).

Phase 19까지와 마찬가지로, **빌드가 통과했다고 판정이 옳은 것은 아니다.** 이번에도 독립 리뷰가
녹색 빌드 뒤에서 결함 3건을 찾았다 — 한 후보의 실패가 같은 run 안의 다른(이미 커밋된) 후보 기록을
지우는 문제, Idempotency-Key 재사용이 Knowledge Snapshot을 검증하지 않는 문제, title/specKey
길이를 저장 전에 검증하지 않는 문제. 셋 다 고치고 회귀 테스트로 고정한 뒤에 커밋했다.

(아래 2절 "지금 상태"의 283 tests / 검증 내용은 Phase 20 기준이다. Phase 21이 테스트를 더
추가했지만 이 세션은 그 숫자를 직접 셀 수 없었다 — 0절 참고.)

---

## 2. 지금 상태

검증: `.\gradlew.bat clean check` 통과 — **283 tests, failures 0, errors 0, skipped 22**
(`build/test-results/test/TEST-*.xml`에서 직접 셈). Phase 19 종료 시점 279개에서 +4 —
전부 `TestSpecGenerationApiIntegrationTests`에 있고, 그중 2개는 독립 리뷰가 찾은 결함의
회귀 테스트다.

| 테스트 | 무엇을 고정했나 |
|---|---|
| `records every proposal and promotes only the one the validator accepts` | 채택 1건 + 거부 1건, 채택된 것만 `test_specification`에 실제로 저장됨 |
| `returns the same run for a repeated idempotency key without calling the model again` | 같은 Idempotency-Key 재요청이 모델을 다시 호출하지 않고 같은 run을 반환 |
| `completes the run and keeps the valid candidate when another candidate is too long to store` (신규, 리뷰 대응) | title이 저장 컬럼 폭을 넘는 후보가 있어도 run은 COMPLETED로 끝나고, 같은 run의 다른(유효한) 후보는 실제로 저장됨 |
| `rejects reusing an idempotency key against a different Knowledge Snapshot` (신규, 리뷰 대응) | 같은 키를 다른 스냅샷으로 재사용하면 409(`TEST_SPEC_GENERATION_IDEMPOTENCY_CONFLICT`) |

독립 리뷰(제 코드를 처음 보는 별도 에이전트 + 직접 코드 대조 검증)가 찾고 고친 결함 3건:

- **부분 커밋 감사기록 유실.** `toCandidate()`가 `SpecParseException`/`SpecValidationException`/
  `ClientRequestException` 세 타입만 잡아서, `specificationService.create()`에서 나올 수 있는
  다른 예외(예: DB 제약 위반)가 후보 순회 루프를 뚫고 나가면 run 전체가 FAILED로 끝나고
  `store.complete()`(run/candidate 레코드를 쓰는 유일한 지점)가 아예 실행되지 않았다. 그런데
  같은 run의 앞선 후보가 이미 `specificationService.create()`로 실제 커밋된 `test_specification`
  row라면? 그 row는 DB에 남는데 어떤 generation run이 그걸 제안했는지 기록이 사라진다.
  범용 catch를 마지막에 추가해서 어떤 후보가 어떤 이유로 실패하든 해당 후보만 REJECTED로
  기록되고 run은 항상 COMPLETED로 끝나게 고쳤다.
- **Idempotency-Key가 Knowledge Snapshot을 검증하지 않음.** `configurationHash`가 모델/프롬프트
  버전만 해시하고 `knowledgeSnapshotId`는 빠져 있었다. 같은 키를 새 스냅샷으로 재요청하면 에러
  없이 예전 run을 그대로 돌려줬다 — `TestSpecificationService.execute()`의 `ensureSameRunRequest`가
  참조 엔티티 id를 해시에 포함시켜 막는 것과 같은 종류의 문제를 Phase 20만 놓쳤다.
  `knowledgeSnapshotId`를 해시에 넣어서 불일치 시 409를 던지도록 고쳤다.
- **title/specKey 길이 미검증.** `rejectionReason`은 저장 전에 방어적으로 truncate하는데
  `title`/`specKey`는 그대로 저장을 시도했다. `test_specification`/`test_spec_generation_candidate`
  둘 다 `varchar(500)`/`varchar(200)` 제한이 있고 `TestSpecParser`/`TestSpecValidator` 어디에도
  길이 검증이 없어서, 악의적이지 않은 평범한 모델 출력만으로도 위 첫 번째 결함이 트리거됐다.
  `specificationService.create()` 호출 전에 길이를 확인해서 넘으면 명확한 이유로 거부하도록
  고쳤다.

detekt(2.0.0-alpha.3, baseline만 있고 커스텀 설정 없음)는 새 파일 기준으로 5건을 잡았고 전부
기존 관례를 그대로 따라 고쳤다 — `MatchingDeclarationName`(단일 최상위 선언 파일명 불일치 →
파일명 변경), `TooGenericExceptionCaught`/`SwallowedException`(원본 예외를 실제로 `cause`로
체이닝), `ThrowsCount`(`@Suppress` + 이유 주석). 컴파일 경고 하나(Elvis 연산자가 항상 좌변만
반환)는 `FollowUpSuggestionService`의 동일 패턴(`?: fallback` 없음)과 맞춰 제거했다. `when`
분기 누락(`JobExecutionCapacity`가 새 `OutboxJobType.TEST_SPEC_GENERATION`을 놓침)도 하나
있었다 — 같은 enum에 대한 다른 exhaustive `when`이 더 있는지 grep으로 확인했고 여기가 유일했다.

| 영역 | 완료된 것 |
|---|---|
| 형식 | JSON Schema, 엄격 parser, Profile 기반 의미 검증 |
| 실행 | setup, CALL/WAIT workload, 동시 발사, API/응답 관측 |
| 판정 | CEL 샌드박스, 불변식·예외·선행조건, trial 집계 |
| 안전 | LOCAL/TEST 쓰기 제한, 읽기 전용 observation, 경로·auth·헤더 이중 검증, 실행 상한, 검증된 reset 전 재실행 차단 |
| 영속화 | V23 명세/run/verdict/reset 저장 + V24 Target별 단일 활성 실행 슬롯과 재시작 복구 |
| API | 명세 등록·승인·조회, 멱등 run 생성·실행·조회 |
| 완료 기준 | 동시성 3 trials, 멱등성 2 trials, 정합성 2 trials가 같은 엔진에서 모두 `PASSED` |
| Phase 18 관측 | `HARNESS_STATE_V1`, Profile 소유 PromQL, source별 auth, 실패한 값만 `NOT_EVALUATED` |
| Phase 19 트레이스 | Profile 소유 TraceQL, 엔진이 정하는 조회 창, `traceId` 짝짓기, 시행 귀속, 잘림 감지, 빈 시간축은 판정 불가 |
| Phase 20 LLM 제안 | 규칙 기반 목록 재사용(신규 생성기 없음), 비동기(202+폴링) Ollama 제안, 채택/거부 전량 기록, 기존 검증기 게이트 재사용, 원본 문서는 비영속·매 요청 프롬프트 컨텍스트 |

완료 기준 테스트는 `Phase17CompletionIntegrationTests`다(Phase 17~19 범위, 변경 없음).
Phase 20 자체의 완료 기준은 `TestSpecGenerationApiIntegrationTests`의 첫 테스트로 고정했다 —
가짜 모델이 규칙 기반 목록에 없는 유효한 후보 1개를 제안하면 실제로 승격되어 저장된다.

프론트엔드는 Phase 20에서 건드리지 않았다(마지막 확인은 `npm ci`, **46 tests**, `tsc`,
`vite build` 통과 — Phase 19 기준).

---

## 3. 다음 작업

**독립 리뷰(22-A~22-D)와 빌드 검증 둘 다 끝났고, 커밋도 끝났다(`395eea7`).** 발견사항 6건 전부
수정했고(0절), 사용자가 직접 `.\gradlew.bat clean check`를 돌려 detekt 위반 2건을 추가로 잡아냈고
그것도 수정해서 **BUILD SUCCESSFUL**을 확인했다.

Phase 22(22-A~22-D)는 계획된 범위가 전부 구현·리뷰·빌드 검증·커밋까지 끝났다.

**사용자가 순서를 정했다: UI 먼저 → Target 수정 → 전체 테스트.** 원래
"의도적으로 맨 뒤로 미룬 것"이었지만, Phase 22까지 코드가 다 끝나서 이제 그 차례가 됐다.
UI 작업 계획은 3.1절에 정리해 뒀다 — **목록 조회 API(`fd5cff5`)를 추가해서 시작 전 확인할 것
2가지가 모두 해소됐으므로, 이제 1번(공용 판정 어휘 컴포넌트)부터 착수하면 된다.**
Target 쪽에 필요한 것은 `TARGET_REQUIREMENTS.md`에 있고, 확인된 것은 딱 하나
(`X-ARL-Trial` → 스팬 속성 필터, 6절 요약 참고) — UI가 끝난 뒤에 손댄다. Phase 19의 완료
기준("3·7·9번이 같은 시각에 예약을 읽었고 반영이 340ms 늦었다")을 **실제 Target으로 눈으로
확인하는 것은 그때 처음 가능해진다.** 그전까지는 스텁과 단위 테스트로만 확인된 상태다.

전체 순서: Phase 20(완료, `082b4ec`) → Phase 21(완료, `6abeb87`) →
**Phase 22(완료, `395eea7`) → 명세 목록 조회 API(완료, `fd5cff5`) → UI 작업(착수 전, 3.1절)**.

나중으로 미뤄 둔 것은 문서 두 개에 모아 뒀다. 해당 시점에 열어 보면 된다.

- `TARGET_REQUIREMENTS.md` — Target(sideProject)이 갖춰야 할 것. 맨 마지막에 붙인다.
- `UI_BACKLOG.md` — 화면. 명세 엔진(Phase 17~20)에는 아직 UI가 하나도 없다.

파일럿 타겟은 `\\wsl.localhost\Ubuntu\home\jybeomss\sideProject` (eventful-commerce). **맨 마지막에** 붙인다.

---

## 3.1 UI 작업 계획 (우선순위 순서, 아직 착수 안 함)

`UI_BACKLOG.md`·현재 백엔드 DTO(`testspec/api/dto/*.kt`)·기존 프론트 관례
(`features/<도메인>/<이름>Workspace.tsx` + `api/<도메인>.ts`, `App.tsx`의 `WorkspaceView`/
`SectionNav`)를 대조해서 정리한 순서다. Phase 20/21/22는 `UI_BACKLOG.md` 5절에서
"후속 Phase에서 생길 것 — 지금 만들 필요 없다"고 미뤄뒀지만, 이제 셋 다 백엔드가 끝났으므로
뒤로 안 미루고 아래 순서에 포함시켰다.

**시작 전에 정할 것 둘 — 둘 다 해소됨:**

1. ~~명세 목록 조회 API가 없다.~~ **해소됨.** `GET /api/targets/{targetSystemId}/test-specifications`를
   추가해서 커밋했다(`fd5cff5`) — target 존재 검증 없이(없으면 빈 배열) 명세를 최신순 최대 50개
   돌려준다. 승인 화면은 이제 이 목록에서 id를 얻어 단건 조회/승인으로 이어가면 된다.
2. ~~아래 1~10 순서대로 진행해도 되는지.~~ **해소됨.** 사용자가 그대로 진행하기로 승인했다.

**작업 순서 — 1번부터 시작. 단, 10단계를 한 번에 쭉 구현하지 말고 단계마다(또는 더 잘게) 확인받을 것:**

1. **공용 판정 어휘 컴포넌트부터.** `PASSED`/`VIOLATED`/`NOT_EVALUATED`,
   `OBSERVATION_MISSING`/`REQUIREMENT_UNMET`/`EXPRESSION_FAILED`(`NotEvaluatedReason` 3종),
   trial 레벨 `INCONCLUSIVE`를 절대 뭉개지 않는 배지·라벨 컴포넌트 하나. 다른 모든 화면이
   이걸 가져다 쓴다 — 나중에 만들면 이미 만든 화면들을 전부 고쳐야 한다(`UI_BACKLOG.md` 0절).
2. **`api/testSpecifications.ts` 클라이언트.** `TestSpecificationResponse`/
   `TestSpecRunResponse` 타입 + `create`/`approve`/`execute`/`findSpecification`/`findRun`
   함수. `api/testPlans.ts` 관례 그대로(타입 + 순수 헬퍼 함수, 상태 라벨 매핑 등).
3. **승인 화면.** `unfoundedThresholds`를 해당 불변식 옆에 붙여서 보여주고(숫자 하나가 아니라
   "무엇을 그 값으로 재는가"가 검토 대상), `risk`/장애 주입 여부/예상 소요시간
   (`시행 × (워크로드+정리)`, sideProject 리셋 120초 기준)을 승인 전에 노출.
   `profileVersionActive: false`는 경고로. `requiredConfirmation`을 그대로 타이핑하게 하는
   입력.
4. **실행/결과 화면.** 실행 버튼(멱등키 자동 생성, 이미 도는 run이 있으면 "오류"가 아니라
   "상태"로 표시), `RECOVERY_REQUIRED`일 때 무엇을 해야 하는지 문구, trial별 결과를
   집계(`trialsRun`/`trialsViolated`/`trialsInconclusive`)와 함께 보여주기(합치지 않기),
   `cleanupVerified: false` 강조.
5. **22-D 회귀 재실행.** `TestSpecRegressionRunsResponse`의 배치 outcome을 명세별로
   나열(성공/실패 섞여도 각자 표시).
6. **20 (LLM 제안) 화면.** `api/testSpecGenerations.ts` + candidate 목록(ACCEPTED/REJECTED
   배지, REJECTED도 document는 그대로 보여줌 — 모델이 실제로 뭘 시도했는지가 유일한 근거).
7. **22-C (오판 신고) 화면.** 신고 → DRAFTED/REJECTED 상태 폴링 → `resultingSpecificationId`로
   승인 화면(3번)으로 바로 연결.
8. **Profile 화면 보강.** `features/profiles/`에 관측 소스 3종(`HARNESS_STATE`/
   `PROMETHEUS`/`TRACE`) 입력, `TRACE` 쿼리의 `${trial}` 자리표시자 도움말, `X-ARL-Trial`
   계측 여부 사전 경고(`TARGET_REQUIREMENTS.md` 참고). `ProfileValidationSummary.tsx`가
   이미 있으니 새 규칙 메시지가 잘 흘러오는지만 확인하면 될 수 있다.
9. **트레이스 근거 시각화(`UI_BACKLOG.md` 3절)는 뒤로 미룬다.** 스팬 원본이 지금 6개째부터
   유실되는 백엔드 선행 작업(`StoredTrialResult`에 근거 컬럼 추가)이 먼저라, 지금은
   `N spans across M traces` 요약 문자열을 자르지 않고 그대로 보여주는 선에서 멈춘다.
10. **`App.tsx` 통합.** 새 다섯 번째 `WorkspaceView`(가칭 `spec`)로 붙이고, 그 아래
    `SectionNav`로 등록/승인/실행/회귀/제안/오판신고 하위 섹션을 나눈다(기존 흐름과
    다른 축이라는 `UI_BACKLOG.md`의 판단을 그대로 따름 — `batches` 아래에 합치지 않는다).

---

## 4. Phase 20에서 확정한 설계 결정

- **새 규칙 기반 생성기를 만들지 않는다.** Phase 12의 `TestCandidateService`가 이미 같은
  Knowledge Snapshot에서 규칙 기반 후보 목록을 만든다. Phase 20이 답해야 할 질문은 "그 목록이
  놓친, 유효하고 실행 가능한 테스트를 LLM이 찾는가" 하나뿐이다 — 처음에 새 파서·필드 역할 추론
  로직을 짜려던 접근(A안)은 재검토 끝에 폐기했고, 규칙 기반 목록 재사용(B안)으로 확정했다.
- **원본 OpenAPI 문서는 저장하지 않고 매 요청마다 프롬프트 컨텍스트로만 쓴다.** 영속화된
  Knowledge Snapshot은 의도적으로 필드 단위 스키마를 갖지 않는다(불변성 유지). 문서를 매번
  새로 받는 것이 모델이 request/response 필드 이름을 실제 근거에 접지시키는 유일한 방법이고,
  생략해도 run은 생기되 대부분 거부될 뿐이다.
- **모델의 모든 제안(채택·거부 불문)을 기록한다.** 거부된 제안도 `document`와 `rejectionReason`을
  포함해 응답에 남긴다 — 리뷰어가 모델이 실제로 뭘 시도했는지 볼 수 있는 유일한 자리이기 때문이다.
- **채택은 기존 검증기 게이트를 그대로 통과해야 한다.** `TestSpecificationService.create()`를
  한 줄도 바꾸지 않고 그대로 호출한다(source: `MODEL_PROPOSED`). LLM 출력은 가설이지 사실이
  아니라는 원칙(5절)을 지키는 유일한 방법은 다른 명세와 똑같은 문 하나만 통과시키는 것이다.
- **오케스트레이션은 비동기(202+폴링)다.** Ollama 응답이 느리거나 HTTP 타임아웃이 우려되는
  경로라서, `FollowUpSuggestionService`가 이미 쓰는 idempotency-key 멱등성 + outbox job
  claim/complete/fail + 재시작 시 `recoverIncompleteRuns()` 패턴을 그대로 따랐다. 새 오케스트
  레이션 관례를 만들지 않았다.
- **`JobExecutionCapacity`에서는 분석(analysis) permits 그룹이다.** 이 job은 살아있는 Target을
  건드리지 않고 LLM 호출과 기존 검증기를 통한 DB 쓰기만 한다 — `FOLLOW_UP_SUGGESTION`과 같은
  분류다.
- **완료 기준을 만족 못 하면 아무것도 붙이지 않는다.** 모델이 규칙 기반 목록에 이미 있는 것만
  제안하거나 전부 거부되면, run은 COMPLETED로 끝나되 승격된 명세는 0개다 — 이것도 정상 종료다.

---

## 4.5 Phase 21에서 확정한 설계 결정

- **인프라 제어(`INFRA_ACTION`/`INFRA_RESTORE`)는 이번 Phase에서 빼기로 사용자가 직접 결정했다.**
  Docker/K8s 소켓 접근처럼 ARL 배포 자체에 영향을 주는 별도 어댑터가 필요해서, 장애 주입보다
  위험도가 다른 결정이라 분리했다(`AskUserQuestion`으로 세 가지 선택지를 제시했고 "이번엔 장애
  주입만"을 골랐다). `WorkloadStepKind.unsupportedSteps()`에서 `INFRA_ACTION`/`INFRA_RESTORE`만
  남기고 `INJECT_FAULT`/`RELEASE_FAULT`는 뺐다.
- **기존 `cleanupVerified`/`active_slot` 인터록을 그대로 재사용한다.** 새 인터록을 만들지 않고,
  `TestSpecRunner.run()`의 `finally`에서 미해제 결함을 해제한 결과(성공/실패)를 `cleanupVerified`
  계산식에 접어 넣었다(`(resets.isEmpty() || resets.last().verified) && faultsReleased`). 그러면
  기존 `JdbcTestSpecRunRepository.complete()`와 `TestSpecificationService.requireExecutionSlot()`이
  손대지 않아도 그대로 "해제 실패 → RECOVERY_REQUIRED → 다음 실행 차단"을 만든다 — 이게 맞는지는
  실제 코드를 읽고 확인했다(추측이 아니다).
- **`EnvironmentResetService`를 그대로 베낀 패턴으로 `FaultInjectionService`를 새로 만들었다.**
  수행-후-확인 구조가 같고, `SpecHttpCaller`를 그대로 재사용해 Profile이 선언한 inject/release
  hook을 호출한다. 다만 reset과 달리 검증 체크 목록 개념은 만들지 않았다 — 성공/실패 불리언과
  `faultId` 하나면 충분하다고 보고 범위를 최소로 잡았다.
- **주입 요청 본문은 엔진이 고정 템플릿으로 만든다, 명세가 만들지 않는다.** `{"runId","faultType",
  "ttlMs","scope"}`를 엔진이 조립해서 보낸다 — 이미 있는 관례(reset hook 호출도 Runner가
  본문을 만든다)와 같다. 명세가 직접 쓰는 것은 `faultType`/`scope`/`ttl` 세 필드뿐이고, 그마저
  Profile의 `supported-faults`와 `max-ttl`을 벗어나면 검증기가 거부한다.
- **TTL 상한은 Profile이 소유한다(`fault-injection.max-ttl`).** 스키마는 이미 `ttl`에 하한
  (0 이상)만 뒀지만 상한이 없었다 — 이번에 `TestSpecValidator`가 `capabilities.maxFaultTtl`과
  비교해서 거부하도록 추가했다. Profile이 `fault-injection`을 선언하지 않으면(즉
  `supported-faults`가 비었으면) `maxFaultTtl`은 `Duration.ZERO`로 매핑되어 사실상 모든 TTL을
  거부한다 — Profile 검증기가 `supported-faults`가 비어 있지 않은데 `fault-injection`이 없으면
  아예 등록을 거부하므로, 정상 등록된 Profile에서는 이 상황 자체가 나오지 않는다.
- **release 실패/faultId 없음은 트라이얼을 실패시킨다.** `INJECT_FAULT`가 `faultId`를 못 받거나
  `RELEASE_FAULT`가 실패하면 `SpecExecutionException`을 던져서 해당 트라이얼을 `completed=false`로
  끝낸다 — setup 단계 실패와 같은 취급이다. 다만 이미 성공적으로 주입된 결함의 핸들은 트라이얼
  실패와 무관하게 `pendingFaultHandles`에 남아서 Runner의 `finally`가 여전히 해제를 시도한다.
- **TTL 자동 만료는 여전히 Target의 책임으로 남긴다.** ARL은 명시적 해제(워크로드의 `해제` 단계
  또는 Runner의 `finally`)만 시도한다 — 해제 요청 자체가 네트워크 문제 등으로 Target에 닿지
  못할 가능성은 항상 남으므로, `TARGET_REQUIREMENTS.md`가 이미 요구하던 "Target 쪽 TTL 자동 만료"가
  마지막 안전망이라는 원래 설계를 그대로 유지했다(엔진이 자동 만료를 대신하려 하지 않는다).

---

## 5. 절대 어기면 안 되는 것

- **자격증명·비밀번호·토큰·DB 접속 문자열은 문서·Profile·프롬프트·Evidence 어디에도 넣지 않는다.**
  참조 ID만 DB에 남고 값은 Runner 환경에서만 해석된다.
- **ARL은 Target 코드를 고치지 않는다. PR도, 배포도, 프로덕션 변경도 없다.**
- 상태 변경·부하·장애 테스트는 **`LOCAL`/`TEST`에서만**. `TestSpecRunner.requireSafeEnvironment`가 마지막 방어선.
- **LLM 출력은 가설이지 사실이 아니다. 합격/불합격은 절대 LLM이 정하지 않는다.** Phase 20에서도
  모델이 제안한 명세는 기존 `TestSpecValidator`를 그대로 통과해야만 저장되고, 모델 스스로 자신의
  제안을 승인하는 경로는 없다.
- 되돌릴 수 없는 상태를 만들 수 없다 — TTL·최대유지 없는 장애·인프라 조작은 거부.
- 예외(`exceptions`)가 불변식을 무력화할 수 없다.
- **빈 관측도, 덜 읽은 관측도 통과로 만들지 않는다.** 계측이 없거나 뒤처진 Target이 아무도 측정하지
  않은 속성에 깨끗한 통과를 받는 것이 이 도구가 낼 수 있는 가장 나쁜 답이다. 완전 부재만이 아니라
  **부분 부재**도 막아야 한다 — 실전에서 압도적으로 흔한 쪽이 그것이다.
- **귀속할 수 없는 트레이스로 판정하지 않는다.** 남의 요청이나 준비 단계로 위반을 보고하면,
  틀린 통과만큼은 아니어도 판정 자체를 무의미하게 만든다.

---

## 6. 개발 환경에서 걸렸던 것들

| | |
|---|---|
| **Jackson 3 `JsonNode.map()`** | Kotlin `Iterable.map` 확장을 가린다. `.values().map { }` / `.values().filter { }` 를 써라. `properties()` 아니고 `propertyNames()` |
| **CEL 식별자** | ASCII만 (`[_a-zA-Z][_a-zA-Z0-9]*`). 한글은 `label`/`설명`에 둔다. 매크로(`all`/`exists`/`map`/`filter`)는 `setStandardMacros()`를 **안 부르는 것**으로 차단 |
| **ArchUnit** | `..application..` 은 `..infrastructure..`·`..api..`·`..http..` 에 의존 못 한다. 설정값은 `application/port`에 인터페이스, `infrastructure`에 `@ConfigurationProperties` 구현 (`FollowUpSuggestionProperties` 패턴) |
| **detekt** | 2.0.0-alpha.3 + baseline. 기본 설정이라 줄 120자, `ReturnCount` 2, `ThrowsCount` 2. 필요하면 `@Suppress`에 **왜인지 주석**을 붙여라 |
| **detekt `MatchingDeclarationName`** | 파일에 최상위 선언이 하나뿐이면 파일명이 그 선언 이름과 정확히 같아야 한다. 여러 선언이 있는 `*Commands.kt` 묶음 파일은 안 걸리지만, 하나만 있으면 걸린다 — `@Suppress`보다 파일명을 선언 이름으로 바꾸는 게 관례에 맞다 |
| **detekt `SwallowedException`** | catch한 예외의 `.message`/`.javaClass.simpleName`만 새 메시지에 문자열로 넣는 것으로는 안 풀린다. 새로 던지는 예외 생성자에 `cause: Throwable? = null`을 두고 실제로 `cause = exception`을 넘겨야 스택트레이스가 안 사라진다 |
| **Spring + 함수형 생성자** | `EnvironmentSpecAuthProvider(private val environment: (String) -> String?)` 는 Spring이 못 채운다. `@Autowired constructor() : this(System::getenv)` 로 무인자 생성자를 하나 더 둔다 |
| **`vite.config.ts`** | `defineConfig` 를 `'vitest/config'` 에서 가져와야 한다. `'vite'` 에서 가져오면 `test` 키를 거부하는데 **`npm run build`에서만** 터진다 |
| **빌드 결과 확인** | `BUILD SUCCESSFUL` 을 믿지 말 것. `build/test-results/test/TEST-*.xml` 에서 `tests=` / `skipped=` 를 직접 세라. up-to-date로 안 돌고 지나간 적 있다 |
| **CEL 커스텀 함수 (0.9.1)** | 이 버전의 binding은 **중첩 타입** `CelRuntime.CelFunctionBinding`이다. top-level `dev.cel.runtime.CelFunctionBinding`은 더 최신 버전에만 있다. 선언은 `dev.cel.common.CelFunctionDecl.newFunctionDeclaration(name, CelOverloadDecl.newGlobalOverload(overloadId, resultType, paramTypes...))`, 컴파일러는 `addFunctionDeclarations`, 런타임은 `addFunctionBindings`. 바인딩 인자 타입은 `List::class.java`면 되고, CEL이 넘겨주는 값은 그대로 `List<Map<String, Any>>`로 읽힌다 |
| **detekt `ReturnCount`** | 한도가 2다. `?: return null`을 두 번 쓰면 바로 걸린다. `?.let { }` 체인으로 바꾸는 편이 `@Suppress`보다 낫다 — 실제로 반환 경로가 하나로 줄어드는 자리가 대부분이다 |
| **detekt는 컴파일보다 먼저 돈다** | `check`에서 detekt가 실패하면 `compileKotlin`이 아예 실행되지 않는다. detekt만 고쳐 놓고 "빌드가 되는구나" 하면 안 된다 |
| **detekt `TooManyFunctions`** | 인터페이스 한도가 11이다. 다른 모듈의 포트를 저장소 인터페이스에 **직접 구현시키면** 금방 넘는다. 별도 어댑터로 빼는 편이 억제보다 낫다 — 그쪽이 "이 경로가 볼 수 있는 것"을 한 곳에서 말해 주기도 한다 |
| **H2는 부분 인덱스를 모른다** | `create index ... where ...`는 PostgreSQL 전용이다. 테스트는 `MODE=PostgreSQL`인 H2에서 도는데 이건 지원 범위 밖이다. `alter table drop constraint`와 `CASE` 기반 check 제약은 H2에서도 통과한다(V25에서 확인) |
| **새 `OutboxJobType` 추가 시 exhaustive `when`이 하나가 아니다** | 핸들러 등록(`OutboxJobHandlerConfiguration`)만 고치고 끝내기 쉬운데, `JobExecutionCapacity.permitsFor(type)`도 같은 enum에 대한 exhaustive `when`이라 새 항목을 놓치면 컴파일이 깨진다. 새 enum 값을 추가할 때는 `grep -rn "when (type)\|OutboxJobType\."`으로 다른 switch가 더 있는지 먼저 확인하는 편이 안전하다 |
| **Kotlin이 제네릭 Java 메서드 반환 타입을 non-null로 좁힐 때가 있다** | `transactionTemplate.execute { ... }`의 람다가 항상 non-null을 반환하면 Kotlin이 호출 전체를 non-null로 추론해서, 뒤에 붙인 `?: fallback`이 "항상 죽은 코드"라는 컴파일 경고가 뜬다. `FollowUpSuggestionService`처럼 애초에 `?: fallback`을 안 붙이는 게 맞다 |

---

## 7. Git 상태와 변경 범위

- Phase 17 설계·명세: `DESIGN3.md`, `TEST_SPEC.md`, JSON Schema, Profile 예시
- 백엔드: 명세 등록·승인·실행·조회 API, 검증기, Runner, 관측·CEL 판정·reset, auth provider, H2/PostgreSQL 영속화와 V23/V24 migration
- 회귀 검증: Phase 17 완료 기준, 안전 경계, 재시작 복구, PostgreSQL 계약 테스트
- 프런트엔드: Phase 11–15 Workbench 화면/API 모듈과 46개 테스트
- 문서: 현재 구현 범위와 다음 Phase를 반영한 `README.md`, 이 인수인계 문서

Phase 17 범위는 `1b939c8`에, Phase 18(observation source Profile 계약, HTTP reader, 완료 기준
테스트)과 Phase 19 범위는 `abafb97`에, Phase 20(LLM 제안) 범위는 `082b4ec`에 커밋됐다.

Phase 20이 건드린 파일 (전부 `082b4ec`):

- 도메인: `TestSpecGenerationModels.kt`(신규 — `TestSpecGenerationRunStatus`,
  `TestSpecGenerationCandidateOutcome`, `TestSpecGenerationRunRecord`,
  `TestSpecGenerationCandidateRecord`, `TestSpecGenerationRunDetails`)
- 포트: `TestSpecGenerationStore.kt`, `TestSpecProposalModel.kt`, `TestSpecGenerationSettings.kt`(전부 신규)
- 애플리케이션: `StartTestSpecGeneration.kt`(명령), `TestSpecGenerationService.kt`(핵심 오케스트레이션 —
  멱등성, 크기 상한, 스냅샷/프로필 최신성 확인, outbox 핸드오프, 후보별 검증기 통과·기록)
- 인프라: `OllamaTestSpecProposalModel.kt`, `TestSpecGenerationProperties.kt`,
  `sql/TestSpecGenerationSql.kt`, `JdbcTestSpecGenerationRepository.kt`, `V26` migration,
  `TestSpecExecutionConfiguration.kt`(재시작 복구 `ApplicationRunner` 추가)
- outbox 배선: `OutboxJob.kt`(`TEST_SPEC_GENERATION` 추가), `OutboxJobHandlerConfiguration.kt`
  (핸들러 등록), `JobExecutionCapacity.kt`(분석 permits 그룹에 배분)
- API: `TestSpecGenerationController.kt`, `dto/StartTestSpecGenerationRequest.kt`,
  `dto/TestSpecGenerationCandidateResponse.kt`, `dto/TestSpecGenerationRunResponse.kt`
- 테스트: `TestSpecGenerationApiIntegrationTests.kt`(신규 — 4개, 그중 2개는 독립 리뷰 대응 회귀)
- 문서: 이 문서

PostgreSQL Testcontainers는 Docker Desktop을 켠 환경에서 다시 실행해야 한다.

Phase 21이 건드린 파일 (`6abeb87`로 커밋됨):

- 도메인: `testspec/domain/FaultInjection.kt`(신규 — `FaultInjectionPlan`, `FaultInjectionOutcome`),
  `testspec/domain/SpecExecutionModels.kt`(`TrialExecution.pendingFaultHandles` 추가),
  `testspec/domain/TestSpecModels.kt`(`SUPPORTED_STEPS`에 `INJECT_FAULT`/`RELEASE_FAULT` 추가)
- 애플리케이션: `testspec/application/FaultInjectionService.kt`(신규 — inject/release),
  `SpecWorkloadExecutor.kt`(두 단계 종류 실행 + `TrialState.activeFaultHandles`),
  `TestSpecRunner.kt`(`finally`에서 미해제 결함 해제 + `cleanupVerified`에 반영),
  `TargetSpecCapabilities.kt`(`maxFaultTtl` 추가), `TestSpecValidator.kt`(TTL 상한 검증 추가),
  `TestSpecExecutionProfileMapper.kt`(Profile → `FaultInjectionPlan`/`maxFaultTtl` 매핑),
  `application/port/TestSpecExecutionProfileCatalog.kt`(`faultInjectionPlan` 필드 추가),
  `TestSpecificationService.kt`(`runner.run()` 호출에 `faultInjectionPlan` 전달)
- Target Profile: `targetprofile/domain/TargetProfileModels.kt`(`ProfileFaultInjectionDefinition`,
  `TestSpecExecutionProfileDefinition.faultInjection` 추가), `targetprofile/infrastructure/
  TargetProfileYamlSchema.kt`(`fault-injection` 필드 허용), `targetprofile/infrastructure/
  TestSpecExecutionYamlMapper.kt`(YAML → 도메인 매핑), `targetprofile/application/
  TestSpecExecutionProfileValidator.kt`(엔드포인트·TTL 상한 검증, `supported-faults`가 있으면
  `fault-injection` 필수)
- 테스트: `SpecWorkloadExecutorTests.kt`(+3 신규, 기존 "cannot execute" 테스트를
  `INFRA_ACTION`으로 이동 — 이유는 테스트 내 주석 참고), `TestSpecRunnerTests.kt`(+2 신규),
  `TestSpecValidatorTests.kt`(TTL 상한 테스트 신규 + 기존 "cannot execute" 테스트를 TTL
  상한/`INFRA_ACTION` 두 개로 교체), `TestSpecExecutionProfileMapperTests.kt`(+4 신규)
- 문서: `TARGET_REQUIREMENTS.md`(5절 신설 — 장애 주입 Target 계약, 4절에서 이동),
  `TEST_SPEC.md`(3절에 `fault-injection` YAML 키 추가), `README.md`(capability 표 갱신),
  `target-profile.sample.yaml`(`fault-injection` 블록 추가), 이 문서

프론트엔드는 Phase 21에서 건드리지 않았다.

Phase 22-A가 건드린 파일 (아직 커밋 안 됨 — 커밋 전 0/0.2/0.3/0.4절의 빌드 검증부터):

- 애플리케이션: `testspec/application/port/TestSpecificationStore.kt`(`reviseProfileVersion(id,
  expectedProfileVersionId, profileVersionId)` 인터페이스 메서드 추가, CAS 시맨틱 명시),
  `testspec/application/TestSpecificationService.kt`(`approve()`/`execute()`가
  `reconcileProfileVersion()`을 거치도록 변경, 기존 private `requireCurrentProfile()` 제거,
  독립 리뷰 대응으로 CAS 실패 시 재조회·`catch (_: ...)` 로 수정)
- 인프라: `testspec/infrastructure/sql/TestSpecificationSql.kt`(`REVISE_PROFILE_VERSION` SQL에
  CAS·status 가드 추가), `testspec/infrastructure/JdbcTestSpecificationRepository.kt`(구현 갱신)
- 테스트: `testspec/api/TestSpecificationApiIntegrationTests.kt`(`executionProfileFrom()`에
  `maxTrials` 파라미터 추가, 기존 "supersedes..." 테스트를 실제 호환 불가 replacement로 고치고
  이름 변경, 호환 bump를 증명하는 새 테스트 1개 추가 — `PostgreSqlTestSpecificationApiIntegrationTests`
  가 이 클래스를 상속하므로 별도 수정 불필요), `testspec/infrastructure/JdbcTestSpecPersistenceTests.kt`
  (독립 리뷰 대응 신규 — `reviseProfileVersion`의 CAS를 저장소 계층에서 직접 증명하는 테스트 1개)
- 문서: 이 문서

새 migration은 없다 — `test_specification.profile_version_id` 컬럼은 이미 있고, 이번엔 그 값을
쓰는 방식만 바꿨다.

Phase 22-B가 건드린 파일 (아직 커밋 안 됨 — 커밋 전 0/0.2/0.3/0.4절의 빌드 검증부터):

- 애플리케이션: `testspec/application/TestSpecValidator.kt`(`expressionViolations`의 예외 검사
  자리에 `exceptionViolations` 추가 — 컴파일된 조건이 리터럴 `true`이거나
  `CompiledExpression.referencedIdentifiers`가 비어 있으면 거부)
- 테스트: `testspec/application/TestSpecValidatorTests.kt`(신규 3개 — 리터럴 `true` 거부,
  관측값 미참조 거부, 실제 관측값을 좁게 참조하는 정당한 예외는 통과)
- 새 migration·API 변경 없음, 문서 갱신 없음(`TEST_SPEC.md` 12·14절이 이미 이 동작을 명시하고
  있어서 구현만 했다)


Phase 22-C가 건드린 파일 (아직 커밋 안 됨 — 커밋 전 0/0.2/0.3/0.4절의 빌드 검증부터):

- 도메인: `testspec/domain/TestSpecMisjudgmentModels.kt`(신규 — `TestSpecMisjudgmentReportStatus`,
  `TestSpecMisjudgmentReportRecord`)
- 포트: `testspec/application/port/TestSpecMisjudgmentReportStore.kt`,
  `testspec/application/port/TestSpecMisjudgmentSettings.kt`(전부 신규)
- 애플리케이션: `testspec/application/ReportTestSpecMisjudgment.kt`(명령),
  `testspec/application/TestSpecMisjudgmentReportService.kt`(핵심 오케스트레이션 — 멱등성,
  위반 verdict 조회, 입력 번들 재구성, outbox 핸드오프, 초안 문서 조립, 검증기 통과·기록)
- 인프라: `testspec/infrastructure/TestSpecMisjudgmentProperties.kt`,
  `testspec/infrastructure/sql/TestSpecMisjudgmentReportSql.kt`,
  `testspec/infrastructure/JdbcTestSpecMisjudgmentReportRepository.kt`, `V27` migration
- outbox 배선: `OutboxJob.kt`(`MISJUDGMENT_EXCEPTION_DRAFT` 추가),
  `OutboxJobHandlerConfiguration.kt`(핸들러 등록), `JobExecutionCapacity.kt`(분석 permits
  그룹에 배분), `testspec/infrastructure/TestSpecExecutionConfiguration.kt`(재시작 복구
  `ApplicationRunner` 추가)
- API: `testspec/api/TestSpecMisjudgmentReportController.kt`,
  `testspec/api/dto/ReportTestSpecMisjudgmentRequest.kt`,
  `testspec/api/dto/TestSpecMisjudgmentReportResponse.kt`
- 테스트: `testspec/api/TestSpecMisjudgmentReportApiIntegrationTests.kt`(신규 4개 — DRAFTED,
  22-B와의 조합으로 REJECTED, 멱등성 재요청, VIOLATED verdict 없는 run 거부. 픽스처 run은
  `TestSpecRunStore`를 직접 호출해 조작해서 실제 Target 실행 없이 위반 verdict를 만들었다)
- 문서: 이 문서

새 승인 엔드포인트는 추가하지 않았다 — 초안이 통과해서 생기는 새 명세 버전은 기존
`POST /api/test-specifications/{id}/approve`로 승인한다.


Phase 22-D가 건드린 파일 (아직 커밋 안 됨 — 커밋 전 0/0.2/0.3/0.4절의 빌드 검증부터):

- 애플리케이션: `testspec/application/port/TestSpecificationStore.kt`(`findApprovedByTarget()`
  추가), `testspec/application/TestSpecViews.kt`(`TestSpecRegressionRunOutcome` 추가),
  `testspec/application/TestSpecificationService.kt`(`triggerRegressionRuns()`/`runOne()` 추가
  — specKey별 최신 APPROVED 버전만 골라 `execute()`를 반복 호출하고, 명세별
  `ClientRequestException`을 개별로 잡아 outcome으로 담는다)
- 인프라: `testspec/infrastructure/sql/TestSpecificationSql.kt`(`FIND_APPROVED_BY_TARGET`
  추가), `testspec/infrastructure/JdbcTestSpecificationRepository.kt`(구현 추가)
- API: `testspec/api/TestSpecificationController.kt`(`POST /targets/{targetSystemId}/
  test-specifications/regression-runs` 엔드포인트 추가, `requireExecutor` — 개별 `/runs`
  엔드포인트와 같은 권한), `testspec/api/dto/TestSpecRegressionRunsResponse.kt`(신규 —
  `TestSpecRegressionRunOutcomeResponse`, `TestSpecRegressionRunsResponse`, 기존
  `TestSpecRunResponse.from()`을 그대로 재사용)
- 테스트: `testspec/api/TestSpecRegressionRunApiIntegrationTests.kt`(신규 5개 — 서로 다른
  specKey 전체 실행, 같은 specKey 다중 버전 중 최신만 실행, 승인된 명세 없는 target의 빈 결과,
  Idempotency-Key 재요청, 배치 내 개별 실패 격리. 픽스처는
  `TestSpecificationApiIntegrationTests.kt`의 Profile 활성화/원복 패턴을 그대로 복제했다)
- 문서: 이 문서

새 migration 없음 — 기존 `test_specification` 테이블/컬럼만 조회한다. 새 outbox job
타입도 없음 — 동기 배치라서 outbox를 거치지 않는다.
