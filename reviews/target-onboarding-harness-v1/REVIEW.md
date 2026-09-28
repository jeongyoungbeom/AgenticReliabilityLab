# Independent Review

Task: target-onboarding-harness-v1 (H0-H1)
Base Commit: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Current HEAD: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Working Tree Included: yes
Relevant Diff: H0의 동시 실행 수정과 H1의 `docs/harness-v1.md`, `harness-v1.schema.json`, 후보별 capability 판정, readiness 실행 코드·테스트. H2 참조 adapter는 제외.

## H0 Findings

H0 추가 finding 없음.

## H0에서 확인한 범위

- 동일 멱등 키의 재시도가 슬롯 점유를 발견한 경우 저장된 run을 재조회하고, 요청 해시가 다르면 충돌을 유지하는 흐름을 확인했다.
- 서로 다른 키의 실행 차단은 `requireExecutionSlot`과 DB의 `(target_system_id, active_slot)` 고유 제약, 기존 실행 중·복구 필요 API 테스트로 확인했다.
- 변경된 경쟁 테스트가 10회 모두 HTTP 201, 동일 run ID, DB 단일 run을 요구하는 것을 확인했다.
- 현재 소스 수정 이후 생성된 `build/test-results/test` XML에는 364 tests, 0 failures, 0 errors, 0 skipped가 기록되어 있다. PostgreSQL API 통합 테스트 10개도 통과했다. 이 리뷰에서는 테스트를 다시 실행하지 않았다.

## H0에서 확인하지 못한 범위

- H0에서 재실행하지 않은 Eventful Commerce local 파일럿 7개의 현재 동작·cleanup은 직접 확인하지 않았다. 기존 실행 결과는 `HANDOFF.md`에 기록돼 있다.
- H0 리뷰 당시 H1-H2의 Harness V1 계약·참조 adapter는 구현 전이어서 H0 리뷰 범위에서 제외했다.

## H1 Review

Base Commit: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Current HEAD: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Working Tree Included: yes
Relevant Diff: `docs/harness-v1.md`, `src/main/resources/schema/harness-v1.schema.json`, `PilotDiscoveryService`, `PilotTestTemplateFactory`, `SpecHttpCaller`, `SpecWorkloadExecutor`, 관련 Profile·parser·validator와 테스트. H2의 실제 adapter는 아직 범위 밖.

## REV-001

Severity: Major
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecHttpCaller.kt` / `buildHeaders` (96-119행); `docs/harness-v1.md` (24-35행)

### Problem

V1 계약은 모든 Harness 요청에 `X-ARL-Harness-Version: 1`을 요구하지만, 현재 ARL 공통 HTTP 호출기는 그 헤더를 넣지 않는다. 새 readiness 경로도 이 호출기를 사용한다.

### Trigger

버전 헤더를 계약대로 검증하는 H2 V1 adapter에 현재 Profile의 state/reset/readiness/fault 요청을 보낼 때.

### Impact

adapter가 HTTP 426을 반환하므로 readiness는 다음 business 요청 전에 실패하고, reset·정리도 검증되지 않아 쓰기 후보 실행이 막힌다. 기존 구형 adapter에서만 작동하는 상태가 V1 계약과 호환되는 것으로 오인될 수 있다.

### Evidence

`SpecHttpCaller.buildHeaders`는 `Accept`, 선택적 `Content-Type`, `X-ARL-Run-Id`, trial과 인증 헤더만 구성한다. `HarnessV1ConformanceTests.LocalHarness.handle` (288-296행)는 version이 `1`이 아니면 426을 반환한다. `HANDOFF.md`도 현행 클라이언트와 H2 헤더 호환을 후속 작업으로 남긴다. 실제 H2 adapter는 아직 없어 통합 실패를 실행으로 확인하지는 않았다.

### Recommendation

Harness 요청의 버전 헤더를 Runner가 고정해 넣고 명세·인증 설정에서 덮어쓰지 못하게 한다. 현재 클라이언트를 conformance adapter에 연결하는 테스트를 추가한다.

## REV-002

Severity: Major
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecWorkloadExecutor.kt` / `awaitReadiness` (187-201행)

### Problem

readiness GET의 HTTP 2xx 응답에서 `ready`만 검사하고 필수 `version`·`runId`가 요청과 일치하는지 검사하지 않는다. 다른 run의 `ready:true`나 계약 형식이 아닌 응답도 준비 완료로 처리한다.

### Trigger

adapter의 캐시·라우팅 오류 또는 잘못된 구현이 다른 run의 readiness 성공 본문을 반환할 때.

### Impact

현재 run의 fixture가 아직 준비되지 않았는데 다음 주문·결제 쓰기를 보내어 잘못된 결과를 만들 수 있다. run 격리 위반을 성공 경로가 감지하지 못한다.

### Evidence

V1 `readiness` schema는 `version`, `runId`, `ready`, `reason`을 모두 요구하고 계약 문서는 응답 `runId`가 요청 헤더와 같아야 한다고 명시한다. 그러나 실행 코드는 `response.body.ready`가 Boolean true이면 바로 반환한다. 신규 executor 테스트도 `ready`와 `reason`만 있는 본문으로 성공한다.

### Recommendation

readiness 성공 본문에서 V1 버전과 요청 run ID, 필수 필드를 확인한 뒤 `ready:true`를 수락한다. 다른 run과 누락 필드 응답이 후속 business 호출을 중지하는 테스트를 추가한다.

## REV-003

Severity: Major
Status: OPEN

### Location

`src/main/resources/schema/harness-v1.schema.json` / `$defs.operation.fixtureRecipe` (21-40행); `docs/harness-v1.md` / Manifest fields (60-69행)

### Problem

확정된 manifest 계약은 `fixtureRecipe`를 임의의 비어 있지 않은 이름으로만 정의한다. 그 이름이 가리키는 입력 필드, 안전한 값의 생성 규칙, 다른 operation의 capture와 연결하는 방법 또는 공통 recipe 식별자 목록이 없다.

### Trigger

Eventful Commerce와 다른 타겟이 V1 manifest에 자체 recipe 이름을 선언하고, H4 범용 후보 생성기가 그 operation의 쓰기 요청 body를 만들려고 할 때.

### Impact

ARL은 OpenAPI·manifest만으로 안전한 fixture 입력을 구성할 수 없다. 코드에 타겟별 recipe를 추가하거나 개발자에게 별도 Test Spec/요청 본문을 요구해야 하므로 H1의 타겟 연결 계약과 H4/H6의 범용 연결 목표가 충족되지 않는다.

### Evidence

schema는 `fixtureRecipe`에 `minLength: 1`만 적용하고 conformance fixture는 `synthetic-product`라는 이름만 제공한다. 계약 문서도 named recipe라고만 설명한다. `TARGET_ONBOARDING_V1.md`는 부족한 request body를 추측하지 말라고 하면서 H4에서 공통 recipe를 재사용하도록 요구한다. 현재 계약에는 그 공통 recipe와 타겟별 매개변수 연결 형식이 정의돼 있지 않다.

### Recommendation

V1 계약에 ARL이 이해하는 recipe 종류와 입력 매핑·안전 제약을 명시하고 schema와 conformance 사례에 유효·미지원 recipe를 포함한다. manifest가 실행 입력을 충분히 설명하지 못하는 operation은 쓰기 후보로 만들지 않는다.

## H1에서 확인한 범위

- 후보별 게이트에서 availability의 Harness 비의존성, fault 후보만의 fault 요구, 비동기 후보의 readiness 경로 요구를 코드·테스트와 대조했다.
- readiness의 10초/200ms polling, timeout 후 다음 workload 중단, 명세의 GET·allowlist 검증을 확인했다.
- 기존 H0 finding과 중복되는 항목은 없었다.

## H1에서 확인하지 못한 범위

- H2 Eventful Commerce adapter가 아직 없어서 실제 V1 endpoint의 인증·run 격리·reset/fault 정합성과 7개 파일럿을 실행 검증하지 못했다.
- 이번 독립 리뷰에서는 테스트를 재실행하지 않았다. `HANDOFF.md`의 371개 통과 기록은 구현 세션의 결과로만 취급했다.



## H2 Review

Task: target-onboarding-harness-v1 (H2)
Base Commit: ARL 4f0cadedf32dac0e4ee874f3a25aca629a12fe68; SideProject 286a62a639cb45861aa153124d5303c1afc6dac8
Current HEAD: ARL 4f0cadedf32dac0e4ee874f3a25aca629a12fe68; SideProject 286a62a639cb45861aa153124d5303c1afc6dac8
Working Tree Included: yes (두 저장소)
Relevant Diff: ARL의 H2 state 관측·quick Profile reset 변경과 WSL SideProject의 현행 reliability-harness, payment fault, Gateway local overlay. 기존 H0/H1 finding은 위에 보존했다.

### 검토 범위와 요구사항 출처

`TASK.md`의 H2 요구사항 및 완료 조건, `TARGET_ONBOARDING_V1.md` 2·5절, `docs/harness-v1.md`와 V1 schema를 현행 코드와 대조했다. ARL의 state/readiness/reset 경로, SideProject의 Harness controller·응답 모델·run별 reset·Gateway route를 확인했다.

### 수행한 검증

두 저장소의 Git 상태와 HEAD를 확인하고, 현재 소스의 endpoint·응답 필드·호출 흐름을 정적으로 대조했다. WSL 참조 구현에는 manifest endpoint 및 V1 버전 처리 코드가 없고, H2 변경은 아직 적용되지 않았다. 이번 리뷰에서는 테스트나 7개 실제 파일럿을 실행하지 않았다.

## REV-004

Severity: Major
Status: OPEN

### Location

WSL `~/sideProject/reliability-harness/src/main/kotlin/com/eventfulcommerce/reliabilityharness/HarnessController.kt` 11-50행, `HarnessModels.kt` 3-11행; `api-gateway/src/main/resources/application-arl-local.yml` 90-101행

### Problem

현재 참조 Harness는 H2의 V1 adapter가 아니다. manifest route가 없고, state는 `contractVersion`·`fields`·중첩 `state`를, readiness는 `ready`·`reason`만 반환한다. reset도 V1 reset 응답 대신 구형 state를 반환하며 Gateway에는 manifest route가 없다.

### Trigger

현재 ARL quick Profile로 Eventful Commerce local 파일럿을 실행하거나 H1 V1 conformance 요청을 실제 Gateway에 보낼 때.

### Impact

ARL의 변경된 state reader는 `version: 1.0`과 같은 run ID가 없어 관측을 거부하고, readiness 실행기도 해당 필드가 없어 비동기 단계를 중단한다. reset 확인식은 최상위 `orderCount`를 기대하지만 기존 응답에는 중첩되어 있다. 따라서 H2 완료 조건인 실제 V1 endpoint, 7개 파일럿 PASS와 cleanup은 현재 코드에서 충족되지 않는다.

### Evidence

`HarnessController`에는 state/readiness/reset만 있고 V1 manifest 및 version header 검증이 없다. `HarnessModels`의 응답 모양은 V1 schema와 다르다. `application-arl-local.yml`의 route 목록에도 manifest가 없다. ARL `HttpDeclaredObservationSourceClient`는 `version`·`runId`를 필수로 확인하고, `SpecWorkloadExecutor.readinessFields`도 두 필드를 필수로 확인한다. `TASK.md`도 H2를 대기 상태로 기록한다. 실제 HTTP 실패는 실행하지 않았고 이 결과는 코드 흐름에 따른 판단이다.

### Recommendation

기존 run별 상태·정리·준비 판정을 유지하면서 참조 adapter와 Gateway route를 V1 경로·헤더·응답·오류 계약에 맞춘다. 격리된 overlay에서 실제 HTTP conformance, 운영 비노출, 7개 파일럿과 cleanup을 확인한다.

## REV-005

Severity: Critical
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/EnvironmentResetService.kt` 37-58, 68-86행; `src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecValueReader.kt` 70-85행; `src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/QuickTargetProfileRegistrationWorkflow.kt` 179-190행

### Problem

ARL의 reset 검증은 hook의 2xx 여부와 state 응답의 `orderCount == 0`만 확인한다. reset 본문의 V1 `clean`, `runId`, `activeFaultCount`를 검사하지 않고, 후속 state 응답의 `version`·`runId` 및 상품·결제·fault 잔여량도 확인하지 않는다.

### Trigger

adapter가 잘못된 run의 state를 반환하거나, 주문은 0이지만 상품·결제·active fault가 남은 부분 정리 결과를 2xx와 함께 반환할 때.

### Impact

`ResetOutcome.verified`가 true가 되어 `cleanupVerified`가 확인된 것처럼 표시되고 다음 쓰기 실행이 허용될 수 있다. 실제 fixture/fault가 남은 상태를 성공으로 믿게 되는 경로다.

### Evidence

`EnvironmentResetService.reset`은 2xx 뒤 선언된 verification만 평가한다. `SpecValueReader.readOnce`는 응답에서 지정 expression 값만 추출하며 V1 버전·run ID를 검사하지 않는다. quick Profile의 유일한 verification은 `response.body.orderCount == 0`이다. 반면 V1 계약은 reset 응답에 `clean: true`, `activeFaultCount: 0`을 요구하고 ARL의 독립적인 결과 확인을 명시한다. 현재 구형 adapter의 내부 reset 검증은 이 ARL 경로의 누락을 보완하지 않으며, V1 연동 후의 오응답을 ARL이 감지할 수 없다.

### Recommendation

reset 성공 본문과 확인용 state를 V1 형식 및 요청 run ID에 대해 검증하고, Profile에서 해당 run의 모든 잔여 fixture·예약·fault 관측 필드를 확인한 뒤에만 cleanup을 검증 완료로 표시한다. 다른 run 응답과 부분 정리 응답의 회귀 사례를 추가한다.

### 검토 제외 범위와 미검증 항목

H3-H6의 범용 Profile·후보 생성·AI UI는 H2 범위에서 제외했다. 현재 WSL adapter가 V1으로 적용되지 않아 실제 HTTP conformance, Gateway 운영 비노출, 7개 local 파일럿 및 cleanup을 실행 검증하지 못했다. 기존 H0/H1 finding의 판정은 `RESOLUTION.md` 담당이므로 이 리뷰에서 변경하지 않았다.
