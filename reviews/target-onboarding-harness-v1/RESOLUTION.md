# Review Resolution

Task: target-onboarding-harness-v1 (H1)
Reviewed Revision: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68 + working tree in `REVIEW.md`
Applied At: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68 + current working tree, 2026-09-28

Summary: ACCEPTED 3 / REJECTED 0 / ALREADY_RESOLVED 0 / DEFERRED 0 / STALE 0

## REV-001

Status: ACCEPTED

Reason:
`SpecHttpCaller` did not set the required V1 version header. The existing state observation and credential preflight transports also lacked it. A V1 adapter returns HTTP 426 without the header.

Changes:
- Runner-managed Harness calls now send `X-ARL-Harness-Version: 1` for reset, reset verification, readiness, fault injection/release, declared Harness state reads, and credential preflight. Business calls do not receive this header.
- `SpecRequestPolicy` rejects the version header in specification and auth-profile headers, including case variants.

Verification:
- `HarnessV1ConformanceTests` sends the real `SpecHttpCaller` to the local V1 HTTP adapter: a Harness call returns 200 and the same call without the managed version returns 426.
- `SpecWorkloadExecutorTests`, `TestSpecValidatorTests`, and `HttpDeclaredObservationSourceClientTests` cover the header boundary.

## REV-002

Status: ACCEPTED

Reason:
`awaitReadiness` accepted `ready: true` without validating the V1 response version, the current run ID, or the required reason.

Changes:
- A successful readiness response must contain version `1.0`, the requested run ID, a boolean `ready`, and a string `reason` before a later business request can run.

Verification:
- `SpecWorkloadExecutorTests` covers pending-to-ready polling, a foreign run ID, and missing version/reason. Invalid replies stop before the order call.

## REV-003

Status: ACCEPTED

Reason:
The previous `fixtureRecipe` string identified no inputs or supported recipe type, leaving H4 without a safe, target-independent way to construct a request body from the V1 manifest.

Changes:
- `harness-v1.schema.json` now defines `SYNTHETIC_JSON_V1` with JSON body pointers and bounded `RUN_TAGGED_STRING`, `BOUNDED_INTEGER`, and same-run `RUN_CAPTURE` sources.
- `docs/harness-v1.md` and `TARGET_ONBOARDING_V1.md` describe capture dependencies, OpenAPI/Profile checks, and the rule that incomplete or unsupported mappings yield no write candidate. The conformance manifest uses the structured recipe.
- The H4 generic manifest consumer remains future work; H1 fixes its contract, not the H4 implementation.

Verification:
- `HarnessV1ConformanceTests` validates a supported recipe and rejects a string recipe, an unsupported input source, and an out-of-range integer.

## Executed verification

- 2026-09-28 20:29 KST: `.\gradlew.bat check --no-daemon` passed; detekt, compilation, and 373 tests passed with 0 failures/errors/skipped.
- 2026-09-28 20:30 KST: after adding the Harness state header assertion, targeted tests for `HttpDeclaredObservationSourceClientTests`, `HarnessV1ConformanceTests`, `SpecWorkloadExecutorTests`, and `TestSpecValidatorTests` passed.
- 2026-09-28 20:33 KST: `./gradlew.bat detekt --no-daemon` passed after the final test assertion.
- The existing background outbox worker logged a connection attempt after Testcontainers PostgreSQL shutdown; `check` still completed successfully.
## H2 Review Resolution

Task: target-onboarding-harness-v1 (H2)
Reviewed Revision: ARL 4f0cadedf32dac0e4ee874f3a25aca629a12fe68; SideProject 286a62a639cb45861aa153124d5303c1afc6dac8 + `REVIEW.md`에 기록된 working tree
Applied At: ARL 4f0cadedf32dac0e4ee874f3a25aca629a12fe68 + working tree; SideProject 286a62a639cb45861aa153124d5303c1afc6dac8 + working tree, 2026-09-29

Summary: ACCEPTED 2 / REJECTED 0 / ALREADY_RESOLVED 0 / DEFERRED 0 / STALE 0. 두 finding 모두 수정·검증했다.

## REV-004

Status: ACCEPTED

Reason:
SideProject의 현재 controller에는 manifest 경로가 없고 state/reset/readiness가 구형 응답을 반환한다. Gateway local route에도 manifest가 없다. ARL V1 state reader와 readiness 검증은 이 응답을 수락할 수 없으므로 실제 adapter와 7개 파일럿의 H2 완료 조건이 충족되지 않는다.

Changes:
- 사용자 승인 후 SideProject `reliability-harness`의 manifest/state/reset/readiness와 결제 fault 주입·해제를 V1 버전·run 범위·응답·오류 계약에 맞췄다. 기존 상품 projection/Redis 재고·결제 예약 준비 판정과 run별 정리 동작을 유지했다.
- Gateway의 `arl-local` manifest route와 접근 정책을 추가하고 production profile에서 Harness 경로를 차단했다. Manifest의 안전한 operation 매핑은 현재 빈 배열이다. 이 단계의 고정 7개 파일럿은 유지하고, 범용 operation 매핑 및 소비는 H3/H4에서 구현한다.
- 참조 구현의 V1 HTTP 회귀 테스트와 Gateway route 테스트를 추가했다.

Verification:
- SideProject의 `HarnessControllerV1Test`, `PaymentFaultControllerV1Test`, `HarnessRoutePolicyTest`가 통과했다. 변경된 세 모듈의 컴파일과 local Docker `bootJar` 빌드도 통과했다.
- local overlay의 실제 Gateway HTTP에서 manifest 200, 잘못된 키 401, 누락·미지원 버전 426, 잘못된 run ID 400, state/reset 200, 미상 readiness 404, 다른 run fault 주입·해제 409, 잘못된 TTL 422, fault 주입 201·반복 해제 200, active fault 1→0을 확인했다.
- production-profile Gateway에서 정상 seller business 경로 `/api/products/my`는 200이고 `/api/harness/manifest`는 익명·인증 요청 모두 403이었다.
- ARL local quick Profile의 후보 7개 모두 READY였고 실행 세션 `fe0c336f-d364-4658-aa01-3706a625f753`에서 7개 모두 COMPLETED/PASSED, `cleanupVerified=true`였다. 런타임 자격증명은 실행 후 해제했다.
- 2026-09-29 00:55 KST 기준, 두 저장소의 `git diff --check`에 공백 오류가 없었다(줄 끝 형식 경고만 출력).

## REV-005

Status: ACCEPTED

Reason:
기존 quick Profile은 `orderCount`만 확인했고 reset 본문의 2xx만으로 정리 hook 성공을 처리했다. 후속 state가 다른 run의 응답이어도 통과할 수 있어 부분 정리를 `verified`로 표시할 수 있었다.

Changes:
- `EnvironmentResetService`에서 표준 V1 reset 본문의 `version`, 동일한 `runId`, `clean`, 0 이상의 `removedFixtureCount`, 0인 `activeFaultCount`를 확인한 뒤 state 검증으로 진행한다.
- `SpecValueReader`에서 표준 V1 state의 버전과 run ID를 확인한 뒤 reset 검증 값을 읽는다.
- quick Profile에서 상품·주문·결제·활성 fault의 run별 잔여 필드 6개가 모두 0인지 확인한다. 기존 사용자 정의 reset 경로의 선언된 검증 동작은 유지했다.

Verification:
- `EnvironmentResetServiceTests`에 다른 run, 누락·부분 reset 응답, 활성 fault, 다른 run의 state, 남은 상품, 정상 정리 회귀 사례를 추가했다.
- `TargetProfileApiIntegrationTests`에서 quick registration이 6개 검증 경로를 내보내는지 확인했다.
- 2026-09-28 23:27 KST: ARL `check --no-daemon` 통과(전체 테스트·detekt·컴파일). 이후 테스트 단언을 강화했다.
- 2026-09-28 23:29 KST: 최종 main 소스에서 detekt 통과. 23:30 KST: 최종 테스트 소스에서 위 두 targeted 테스트 클래스 통과.
