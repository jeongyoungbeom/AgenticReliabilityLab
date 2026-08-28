# HANDOFF

> 이 문서는 **현재 어디까지 되어 있는가**만 전달한다.
> 해야 할 일은 `TASK.md`, 유지해야 하는 설계 판단은 `DECISIONS.md`,
> 독립 리뷰 결과는 `reviews/<task>/REVIEW.md`에 있다.
> 이 문서는 코드나 요구사항보다 우선하지 않는다. 여기 적힌 완료 주장과 실제 코드가 다르면 **코드가 사실**이다.
> 2026-08-27 이전의 전체 개발 이력은 `docs/history/HANDOFF-2026-08-27.md`에 보존돼 있다.

Updated: 2026-08-28

## Current Goal

7단계 오류 진단 모델은 구현과 **독립 리뷰 반영까지 완료**했다. 파일럿 세션과 Test Spec 실행·시행·정리 결과의
실패를 단계·한국어 설명·예상 원인·다음 행동·기술 정보로 저장·표시하며, 민감값은 저장·API·화면 경계에서
마스킹한다. 다음 작업은 사용자가 재개를 명시할 때의 8단계 실제 Docker 통합 검증이다.

현재 TASK는 `TASK.md`를 본다.

## Repository State

- Branch: `master`
- HEAD: `8ecbba5` (`feat: streamline pilot target setup`)
- Working tree: 1–7단계 구현과 두 번의 `apply-review` 반영분이 모두 미커밋 상태다. 파일럿 세션 집계·오류 노출·
  결과 UI, 간편 등록의 3개 입력 문구·회귀 테스트, 7단계 오류 진단 모델(`diagnosis/` 패키지, V30·V31 migration,
  `FailureDiagnosisDetails.tsx`), 이번 세션의 7단계 리뷰 반영(아래 Completed), `README.md`, `TASK.md`,
  `reviews/*/RESOLUTION.md` 및 이 문서를 변경했다. 되돌리거나 덮어쓰지 않는다.
- 이 저장소는 OneDrive 위에 있어 `git checkout --`가 unlink 권한 오류로 실패할 수 있다. 파일을 되돌려야 하면 삭제 대신 내용을 직접 고친다.
- 커밋·push는 이 세션에서 수행하지 않았다.

## Completed

UX 단순화 계획의 **1–7단계 구현 완료**.

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 간편 등록 — `name`/`baseUrl`/`environment` 3개로 표준 Profile 생성·Swagger 자동 발견·즉시 활성화 | 구현 완료 |
| 2 | Target 목록 정리 — 사용자 등록 Target만 노출, 이름·URL·환경·문서 수 표시 | 구현 완료 |
| 3 | 세션 자격증명 — HttpOnly 쿠키 세션, 유휴 TTL 8시간, 새로고침 복구 | 구현 완료 |
| 4 | UI 축소 — 상단 nav 3개(테스트/결과/세션 종료), 미사용 화면 32개 삭제 | 구현 완료 |
| 5 | Harness 4개 계약 게이트 + 생성된 전체 YAML을 고급 편집 출발점으로 제공 | 구현 완료 |
| 6 | 파일럿 테스트 세션 저장 모델 — 승인한 선택 1회를 세션으로 영속, 멱등 재생, 재기동 복구 | 구현 완료 |
| 7 | 오류 진단 모델 — 실패 단계·한국어 조치 안내·안전한 기술 정보의 저장과 표시 | 구현·리뷰 반영 완료 |

1–5단계는 독립 리뷰 2회와 그 반영까지 끝났다. **6단계 독립 리뷰 REV-001~REV-016도 현재 코드 기준으로
재검증·반영을 완료했다.** 판정과 근거는 `reviews/pilot-ux-simplification/RESOLUTION.md`에 있다.

**7단계 독립 리뷰 REV-001~REV-008도 이번 세션에 재검증·반영했다(8건 전부 ACCEPTED).**
판정과 근거는 `reviews/error-diagnosis-model/RESOLUTION.md`에 있다. 반영 내용 요약:

- `ACCESS_DENIED`를 Target 자격증명 템플릿에서 분리해 ARL 접근 토큰 안내로 바꿨다(D006).
- `formatApiError`가 서버 메시지와 오류 코드를 버리지 않고 한국어 안내 뒤에 함께 보여 준다.
- `InvariantVerdict`의 `detail`·`observedValues`·`appliedException`을 `verdicts_json` 쓰기·읽기 양쪽에서 마스킹한다.
- redactor를 "키워드 단독 → 전체 삭제"에서 **값이 붙은 비밀값 / 구조로 판별한 응답 본문**으로 바꿔
  오탐(`Viewer authorization is required` 전체 삭제)과 미탐(라벨 없는 JSON 본문 통과)을 함께 없앴다.
- `cleanupVerified`의 세 상태 라벨을 `components/cleanupStatus.ts` 한 곳에서 정의해 두 화면이 공유한다.
- 오류 핸들러 안의 `require`를 fallback으로 바꾸고, 예외 메시지 없이 원인 체인·스택 프레임만 로깅한다.

## Verification

Last verified at the current working tree, 2026-08-28 16:00–16:35 KST (7단계 리뷰 반영 이후):

- 백엔드 `.\gradlew.bat check` — **BUILD SUCCESSFUL** (16:33–16:35 KST, `5 executed, 2 up-to-date`).
  detekt 0 findings, **359 tests / 0 failures / 0 errors / 0 skipped** (리뷰 반영 전 353 + 신규 6:
  `FailureDiagnosisTests` 4개, `JdbcTestSpecPersistenceTests`의 verdict `detail` 마스킹 1개가 H2·PostgreSQL
  양쪽에서 실행돼 2회 계산).
  Testcontainers PostgreSQL 종료 뒤 `OutboxJobWorker` 스케줄러의 연결 거부 로그가 남지만 Gradle과 테스트
  결과에는 실패가 없다(기존과 동일한 종료 시점 노이즈).
  이 실행에서 `FailureDiagnosisTests.kt:88`의 불필요한 `!!` 경고 2건이 나와 이후 로컬 변수로 정리했다.
  **그 정리 이후 백엔드를 다시 실행하지 않았다** — 테스트 본문만 바뀐 변경이지만 재확인은 하지 않은 상태다.
- 프런트 `npm test` — **12 files / 50 tests PASS** (16:00 KST). 신규 `ApiClient.test.ts`,
  `components/cleanupStatus.test.ts` 포함.
- 프런트 `npm run build` (`tsc -b && vite build`) — **PASS** (16:00 KST).
  두 실행 모두 원격 세션 Linux VM에 `frontend` 소스를 그대로 복사하고 같은 `package-lock.json`으로 설치한
  사본에서 수행했다(저장소의 `node_modules`는 Windows 네이티브 바이너리라 그 자리에서 실행되지 않는다).
  소스 동일성은 `diff -rq`로 확인했다.
- `git diff --check` — **PASS** (16:05 KST).
- 7단계 대표 경로 — 인증/권한·연결/preflight·실행·정리/복구 진단, API 및 DB/Evidence의 민감값 마스킹을
  `FailureDiagnosisTests`, `ApiAuthorizationIntegrationTests`, 파일럿·Test Spec 영속화 테스트로 확인했다.
  이번 리뷰 반영으로 ARL 접근 안내 분리, 비밀값 없는 정상 문구 보존, 라벨 없는 JSON 본문 마스킹,
  공백 메시지 fallback, verdict `detail` 마스킹이 회귀 테스트로 고정됐다.
- 향후 8단계 사전 환경 대조 (13:00 KST) — SideProject source의 Harness 4개 경로, `HARNESS_STATE_V1` 필드,
  JSON 상품 생성·주문·결제 webhook 계약은 ARL의 표준 Profile/고정 템플릿과 일치했다. 실제 Target 호출은
  Docker 엔진이 중지되어 수행하지 못했다.
- 향후 8단계 Docker 준비 (13:31–13:39 KST) — 사용자가 Docker Desktop Linux 엔진을 시작한 뒤,
  SideProject의 `docker-compose.yml` + `docker-compose.arl-local.yml` overlay를 적용했다.
  `reliability-harness`와 Nginx `127.0.0.1:18080`이 기동됐고, `/actuator/health`는 **200**이다.
  ARL Workbench와 전용 PostgreSQL도 당시 소스로 재빌드·기동했으며 `127.0.0.1:8090/actuator/health`는 **200**이다.
  현재 소스는 그 이후 바뀌었으므로 8단계 재개 전에 다시 빌드해야 한다. Target의 상태 변경 API·Harness
  reset/fault·비즈니스 실행은 호출하지 않았다.

실행 방법 메모:

- 백엔드는 저장소 루트에서 `.\gradlew.bat check`. 소스가 그대로면 `7 actionable tasks: 7 up-to-date`로 끝나는데
  이는 "안 돌았다"가 아니라 직전 성공 실행의 입력과 현재 소스가 같다는 뜻이다. 강제 재실행은 `--rerun-tasks`.
- 프런트는 **반드시 `frontend` 디렉터리에서** `npm test` / `npm run build`. 루트에는 `package.json`이 없다.

## Current Risks

1. **`check` 통과 이후 테스트 파일 1개가 더 바뀌었다.** `FailureDiagnosisTests.kt`의 불필요한 `!!` 경고를
   지운 정리이며 테스트 본문 밖의 동작은 건드리지 않았지만, 그 상태로는 아직 실행하지 않았다.
   다음 세션의 첫 `.\gradlew.bat check`가 이를 함께 확인한다.
2. **8단계 실제 UI 통합 검증은 아직 하지 않았다.** 7단계 완료 뒤에도 Target 상태 변경 API·Harness reset/fault·
   비즈니스 실행은 호출하지 않았다.
3. **실제 UI 검증이 아직 없다.** in-app browser가 ARL Workbench를 처음 열어 기존 화면을 읽은 뒤,
   재빌드 후 reload 시 로컬 URL 정책으로 차단됐다. 이 세션에서는 다른 브라우저·직접 HTTP 호출로 UI 검증을
   우회하지 않는다. 사용자가 `http://localhost:8090`을 열어 화면 확인/입력을 이어가야 한다.
4. **실제 SideProject Docker Target에 대한 ARL 통합 검증이 아직 없다.** Docker health만 확인했으며
   Harness state·자격증명 preflight·Swagger 등록은 아직 UI에서 실행하지 않았다.
5. **간편 등록의 실행 allowlist가 SideProject 모양에 맞춰 코드에 고정돼 있다.**
   다른 모양의 Target은 Swagger를 읽어도 실행 후보가 비게 된다. 일반화 여부는 미결이다(`DECISIONS.md` D005).

## Next

1. 저장소 루트에서 `.\gradlew.bat check`를 한 번 실행해 위 경고 정리 이후 상태를 확인한다.
2. 사용자가 8단계 재개를 지시하면 `http://localhost:8090`의 UI에서 등록, Harness 4개 계약, 세 역할 preflight,
   안전한 파일럿 실행, 결과 재조회·세션 종료를 순서대로 검증한다.
3. in-app browser의 로컬 URL 정책이 계속 막히면 사용자가 같은 UI를 열어 조작해야 하며, 직접 HTTP 호출로
   UI 검증을 우회하지 않는다.
4. commit·push는 하지 않았다. 1–7단계의 미커밋 변경은 그대로 보존한다.

## Relevant Files

읽는 순서:

1. `TASK.md` — 지금 무엇을 해야 하는가
2. `DECISIONS.md` — 유지해야 하는 설계 판단
3. `reviews/error-diagnosis-model/RESOLUTION.md` — 7단계 리뷰 finding의 판정과 반영 내용
4. `reviews/pilot-ux-simplification/RESOLUTION.md` — 6단계 리뷰 finding의 판정과 반영 내용

주요 코드:

- 간편 등록: `targetprofile/application/QuickTargetProfileRegistrationWorkflow.kt`
- 적용 설정·YAML 렌더: `targetprofile/application/EffectiveTargetProfile*.kt`
- 자격증명 세션: `targetcredential/{api/TargetCredentialSessionCookie.kt,application/*}`
- 파일럿 후보·실행·세션: `targetdiscovery/**`
- 프런트 화면: `frontend/src/App.tsx`, `frontend/src/features/profiles/*`, `frontend/src/features/specifications/*`

배경 문서(필요할 때만): `DESIGN4.md`(파일럿 계약 초안), `TARGET_REQUIREMENTS.md`(Target 요구사항),
`TEST_SPEC.md`(명세 스키마), `docs/history/HANDOFF-2026-08-27.md`(과거 이력).
