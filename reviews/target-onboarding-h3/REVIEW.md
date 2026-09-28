# Independent Review

Task: target-onboarding-h3
Base Commit: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Current HEAD: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68
Working Tree Included: yes
Relevant Diff: H3 범용 Profile 제안·계약 매핑·활성화 재검사, 기존 읽기 실행 흐름과 Profile API 통합 테스트. H0-H2 미커밋 변경은 H3에 영향을 주는 호출 경계에서만 확인했다.

## 검토 범위

TASK.md의 H3 요구사항·완료 조건에 따라 범용 제안, OpenAPI/manifest 교집합, DRAFT와 활성화, Profile 읽기 후보, 역할별 preflight, 관련 테스트를 검토했다.

## 요구사항 출처

TASK.md (target-onboarding-h3), TARGET_ONBOARDING_V1.md 2·3·5절, docs/harness-v1.md의 manifest/fixture 계약, DECISIONS.md D002·D012.

## 수행한 검증

- 현재 HEAD와 미커밋 변경을 확인하고 제안 → 저장 → 활성화 → 기존 읽기 후보 실행 흐름을 정적으로 추적했다.
- 기존 TargetProfileApiIntegrationTests 6개를 현재 작업 트리에서 실행했고 모두 통과했다.
- git diff --check는 공백 오류 없이 종료했다. 줄 끝 형식 경고만 있었다.

## REV-001 — 계약 내용이 바뀌어도 활성화되는 경우

심각도: Major
상태: OPEN

### 위치

src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/TargetProfileActivationWorkflow.kt:49-50; src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/GenericProfileContractMapper.kt:81-91

### 문제

활성화 시 문서 원문이나 정규화된 계약을 비교하지 않고 새로 만든 TargetProfileDefinition만 기존 정의와 비교한다. mapper는 fixtureRecipe의 값, observations.expected, idempotency, readinessKind를 검증한 뒤 Profile 정의에는 넣지 않는다. 유효한 다른 값으로 이 필드를 바꾸면 두 정의가 같아 변경을 감지하지 못한다.

### 발생 조건

제안 후 활성화 전에 manifest의 같은 operation에서 BOUNDED_INTEGER 값을 10에서 20으로 바꾸거나 observations.expected를 1에서 2로 바꾸는 경우. 새 값이 현재 검증 범위에 들어 있으면 재매핑과 정의 비교가 통과한다.

### 영향

사용자가 검토한 것과 다른 fixture·판정·멱등성 계약을 가진 Target에 쓰기 allowlist가 활성화된다. TASK.md의 활성화 직전 계약 변경 거부 조건과 맞지 않는다.

### 근거

mapper는 최종 write를 method/path/authProfile/operationId의 ProfileHttpCallDefinition으로만 만든다(91행). observations에서는 field만 이후 state/reset에 사용하고 expected는 저장하지 않는다(81-86, 107-112행). 활성화는 이 투영 결과의 동등성만 확인한다. 통합 테스트의 drift 사례는 authProfile 변경만 검사한다.

### 권장 수정

제안한 OpenAPI/manifest의 안전한 정규화 결과 또는 해시를 버전에 고정하고 활성화 시 다시 비교한다. 적어도 쓰기 실행 의미에 영향을 주는 recipe, 기대값, 멱등성, readiness 참조의 변경을 감지하는 회귀 테스트를 추가한다.

## REV-002 — 중첩 필수 요청 필드를 빠뜨린 recipe 수용

심각도: Major
상태: OPEN

### 위치

src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/GenericProfileContractMapper.kt:176-216, 특히 212-215행

### 문제

recipe가 OpenAPI 요청 schema의 최상위 required 속성만 검사한다. 필수 객체 안의 required 자식 속성은 순회하지 않는다.

### 발생 조건

요청 schema가 address 객체를 필수로, 그 안의 street과 city를 필수로 선언하고 recipe가 /address/street만 채우는 경우. 현재 로직은 최상위 /address에 대한 접두 경로가 있다는 이유로 통과시킨다.

### 영향

필수 필드를 채울 수 없는 쓰기 operation이 검토 가능한 Profile의 허용 목록에 포함된다. 이후 해당 recipe로 만든 요청은 필수 city가 없어 실패한다. TARGET_ONBOARDING_V1.md와 Harness 계약의 필수 요청 필드 매핑 조건을 위반한다.

### 근거

schema.property(pointer)는 입력에 있는 경로의 속성만 확인하고, required는 최상위 schema의 required 한 단계만 읽는다. 기존 H3 통합 테스트는 알 수 없는 최상위 포인터만 거부하는 사례를 확인한다.

### 권장 수정

inline object schema의 중첩 required 필드를 재귀적으로 모아 recipe 포인터가 각 필수 leaf를 채우는지 확인한다. 중첩 필수 필드가 빠진 계약을 거부하는 테스트를 추가한다.

## REV-003 — 인증이 필요한 GET을 무인증 health 후보로 지정

심각도: Major
상태: OPEN

### 위치

src/main/kotlin/com/project/agenticreliabilitylab/targetprofile/application/GenericProfileContractMapper.kt:96-105, 152행; src/main/kotlin/com/project/agenticreliabilitylab/targetspec/application/TargetTestBatchExecutionService.kt:147-160

### 문제

첫 번째 고정 GET을 인증 요구 여부와 관계없이 Target healthPath로 설정한다. 기존 Target 읽기 Batch는 후보를 실행할 때 인증 헤더를 보내지 않는다.

### 발생 조건

OpenAPI에 고정 GET이 있지만 그 경로가 테스트 역할의 bearer 인증을 요구하는 경우. H3 통합 테스트의 두 타겟도 GET business 경로에서 Authorization이 없으면 401을 반환한다.

### 영향

정상적으로 활성화하고 역할별 preflight가 READY여도 health 및 읽기 후보는 401로 실패한다. 검토한 범용 Profile의 기존 읽기 점검을 사용할 수 없고 타겟 상태를 잘못 실패로 표시한다.

### 근거

mapper는 static GET의 인증 조건을 확인하지 않은 채 첫 경로를 healthPath로 고른다. TargetTestBatchExecutionService.executeItem은 transport에 emptyMap() 헤더를 전달하며 2xx 외 응답을 FAILED로 처리한다. H3 테스트는 활성화와 preflight만 확인하고 읽기 Batch는 실행하지 않는다.

### 권장 수정

무인증 2xx GET만 health 후보로 선택하거나, 읽기 후보의 역할과 런타임 자격증명을 기존 Batch 실행까지 전달한다. 인증이 필요한 고정 GET만 있는 타겟을 통합 테스트에 포함한다.

## 검토 제외 범위

H4 범용 후보 생성·실행, H5 AI/UI, H6 두 번째 실제 타겟, SideProject 참조 Harness 구현 전체, H0-H2 기존 finding의 해결 판정은 이번 H3 리뷰에서 제외했다.

## 미검증 항목

실제 별도 타겟과 Gateway를 통한 E2E 실행은 수행하지 않았다. 위 finding의 발생 조건은 코드 흐름과 기존 테스트 fixture로 확인했으며, 새 재현 테스트는 독립리뷰 지침에 따라 추가하지 않았다.
