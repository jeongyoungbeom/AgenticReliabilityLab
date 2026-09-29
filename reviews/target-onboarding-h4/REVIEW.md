# Independent Review

Task: target-onboarding-h4
Base Commit: c41955f688d09f9b9df3d37ceae4cc88130c07cb
Current HEAD: c41955f688d09f9b9df3d37ceae4cc88130c07cb
Working Tree Included: yes
Relevant Diff: H4 범용 후보 생성·실행, Profile 계약 매핑, Test Spec 요청 본문 치환·Runner, 관련 통합·단위 테스트의 미커밋 변경

## 검토 범위

H4의 읽기·쓰기·멱등성·비동기 후보 생성부터 명세 검증, 선택 실행, capture 전달, 계약 변경 차단까지 관련 호출 흐름을 검토했다. 기존 고정 후보의 연결과 회귀 테스트 변경도 확인했다.

## 요구사항 출처

`TASK.md`의 `target-onboarding-h4` 요구사항·완료 조건, `TARGET_ONBOARDING_V1.md` 2·3·5절, `docs/harness-v1.md`의 fixture·capture 계약, `DECISIONS.md` D009·D011·D012 및 H3 공개 GET 처리 결정.

## 수행한 검증

- HEAD, 작업 트리 변경 및 관련 테스트를 읽고 제안된 계약 → 활성 Profile → 후보 → Test Spec 실행 흐름을 정적으로 추적했다.
- 이 독립 리뷰에서는 테스트를 새로 실행하지 않았다. `HANDOFF.md`의 전체 `check` 통과 기록은 읽었지만 별도로 재검증한 결과로 취급하지 않았다.

## 검토 제외 범위

H5 AI/UI, H6 두 번째 실제 타겟 구축, Eventful Commerce 실제 7개 파일럿 재실행은 H4 완료 조건 밖이다.

## 미검증 항목

아래 조건의 별도 실행 재현과 실제 외부 타겟 연결은 수행하지 않았다. 각 finding은 현재 코드의 분기와 데이터 흐름으로 확인했다.

## REV-001 — Harness 세션이 없으면 읽기 후보의 계약 변경 검사를 건너뜀

심각도: Major
상태: OPEN

### 위치

`src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/GenericPilotTemplateFactory.kt:62-75` (`plans`)

### 문제

Harness 키가 없는 경우 `plans`가 저장된 Profile의 읽기 후보만 반환하고 fresh OpenAPI를 읽지 않는다. 실행 경로도 같은 discovery와 `document`를 거치므로 읽기 후보에는 활성화 뒤의 OpenAPI 변경 검사가 적용되지 않는다.

### 발생 조건

범용 Profile 활성화 후 공개 GET의 경로·응답 계약이 바뀌고 사용자가 Harness 세션 없이 읽기 후보를 실행할 때.

### 영향

현재 계약과 더 이상 일치하지 않는 Profile의 GET 명세가 READY로 표시되고 실행될 수 있다. `TASK.md`의 실행 직전 fresh 계약 재대조와 변경 계약의 사전 거부 조건을 만족하지 못한다.

### 근거

67행의 조기 반환이 68-75행의 OpenAPI/manifest 재조회와 계약 해시 비교보다 앞선다. `PilotTemplateExecutionService.execute`는 discovery 결과로 READY를 판단하고, `GenericPilotTemplateFactory.document`도 같은 `plans`를 다시 호출한다. H4 통합 테스트는 키 없는 읽기 실행은 확인하지만 이 경로에서 계약이 바뀐 사례는 확인하지 않는다.

### 권장 수정

Harness 없이 읽기를 유지하면서도 fresh OpenAPI를 활성 Profile에 고정한 승인 당시 문서와 비교하고, 변경 시 읽기 후보를 실행 전에 차단한다.

## REV-002 — 공개 GET과 보호된 쓰기가 같은 경로면 읽기 후보가 NOT_READY가 됨

심각도: Major
상태: OPEN

### 위치

`src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/GenericProfileContractMapper.kt:132-145`; `src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/GenericPilotTemplateFactory.kt:90-99`

### 문제

mapper가 공개 GET의 호출 역할을 같은 path의 쓰기 역할에서 유추한다. 후보 생성기는 그 GET에 `authProfile == null`인 허용 호출이 있어야 READY로 판단한다.

### 발생 조건

예를 들어 공개 `GET /api/items`와 `writer` 인증이 필요한 `POST /api/items`를 함께 선언한 정상적인 REST 계약에서 발생한다. GET은 제안 시 실제 무인증 성공으로 검증돼 `readOnlyOperations`에 들어가지만, `readCalls`가 `writer` 역할을 붙인다.

### 영향

실행 가능한 공개 읽기 operation이 `approved public GET` 누락으로 NOT_READY가 된다. 같은 경로의 쓰기 역할 때문에 무관한 읽기 후보가 막혀 H4의 후보별 의존성 원칙에 어긋난다.

### 근거

`readCalls`는 `writes.filter { it.path == read.path }`의 단일 역할을 GET에 적용한다. `readPlans`는 null 역할의 GET만 찾는다. H3 검토 결과의 공개 GET 결정도 무인증으로 성공한 읽기 경로만 후보로 쓰도록 명시했다.

### 권장 수정

공개 읽기 호출의 역할은 실제 공개 GET 판정대로 null로 유지하고, 쓰기 역할의 GET이 별도로 필요한 경우 method/path/역할의 의미를 혼동하지 않게 분리한다. 같은 경로의 공개 GET과 보호된 POST를 통합 테스트에 포함한다.

## REV-003 — capture의 생산·소비 타입이 달라도 쓰기 후보가 READY가 됨

심각도: Major
상태: OPEN

### 위치

`src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/GenericProfileContractMapper.kt:80-86,231-237`; `src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/GenericPilotTemplateFactory.kt:257-272,324-341`

### 문제

`RUN_CAPTURE`는 이전 operation과 capture 이름의 존재, 생산 응답 필드와 소비 요청 필드가 각각 scalar인지만 검사한다. 두 필드의 타입이 같은지는 검사하지 않는다. 후보의 `unsupportedInput` 검사도 소비 필드 타입만 본다.

### 발생 조건

생산 operation의 OpenAPI 응답 `id`가 string이고 후속 operation의 필수 요청 `parentId`가 integer인 계약에서, 후속 recipe가 `RUN_CAPTURE`로 둘을 연결할 때. Profile 제안·활성화가 통과하고 후속 후보도 READY가 될 수 있다.

### 영향

사전에 실행 불가능한 fixture를 식별하지 못하고, 실행 중 typed JSON 치환이 `Scalar capture is not a number`로 실패한다. H4의 필수 입력·fixture 불일치에 대한 실행 전 거부 또는 NOT_READY 조건을 위반한다.

### 근거

mapper의 capture 검사는 `SCALAR_TYPES` 포함만 확인하고, `validateRecipe`는 생산 capture 이름과 소비 property의 scalar 여부만 확인한다. 생성기는 소비 property가 integer이면 `arl-number` 참조를 만들지만 생산 응답 타입과 비교하지 않는다. 현재 숫자 capture 통합 테스트는 양쪽 타입이 일치하는 사례만 검증한다.

### 권장 수정

계약 교집합을 만들 때 생산 응답의 capture 타입과 소비 요청 property 타입을 대조하고, 호환되지 않으면 제안 단계에서 거부하거나 해당 후보를 NOT_READY로 표시한다.

## REV-004 — 문자열 capture가 후속 setup의 JSON을 깨뜨림

심각도: Major
상태: OPEN

### 위치

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecWorkloadExecutor.kt:85-95`; `src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecReferenceResolver.kt:30-60`

### 문제

후속 setup 호출을 보내기 전에 `runSetupStep`이 본문 JSON 텍스트에 `references.resolve`를 수행하고 이를 `bodyFields`에서 파싱한다. 이 단계는 새 `resolveJsonBody`의 JSON 문자열 escaping을 거치지 않는다.

### 발생 조건

한 setup operation의 정상적인 문자열 ID capture에 따옴표나 역슬래시가 포함되고, 후속 setup operation이 그 ID를 `RUN_CAPTURE` 입력으로 쓰는 다단계 후보에서 발생한다. 예를 들어 capture 값 `a"b`를 `{"parentId":"{{setup.fixture1.id}}"}`에 직접 대입하면 유효하지 않은 JSON이 된다.

### 영향

계약상 허용된 문자열 capture workflow가 후속 HTTP 호출 전에 실패한다. 실행기의 안전한 JSON 치환을 추가했어도 setup의 사전 body 추출 경로가 이를 우회한다.

### 근거

`runSetupStep` 93행은 원시 텍스트 치환 결과를 `references.bodyFields`에 넘긴다. 실제 전송 단계의 `SpecHttpCaller.send`만 `resolveJsonBody`를 사용한다. 추가된 resolver 단위 테스트는 `resolveJsonBody` 자체의 escaping만 확인하고 여러 setup 단계를 실행하지 않는다.

### 권장 수정

setup의 body field 추출에도 JSON 값 단위 치환을 적용하거나, 필요한 literal field만 원본 JSON에서 안전하게 추출한다. 특수 문자가 든 문자열 capture를 후속 setup에 전달하는 실행 테스트를 추가한다.
