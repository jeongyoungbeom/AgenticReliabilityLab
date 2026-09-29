# Independent Review

Task: target-onboarding-h5
Base Commit: c41955f
Current HEAD: c41955f
Working Tree Included: yes
Relevant Diff: H5 생성 API·서비스·fingerprint·저장소·AI 제안 UI와 테스트, H4 기본 후보 및 Test Specification 생성 의존 경로

## REV-001

Severity: Major
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/TestSpecProposalFingerprint.kt:29-33`

### Problem

중복 fingerprint에 `specification.category.name`이 들어간다. category는 분류 표시이며 operation 순서나 판정 조건을 바꾸지 않는다.

### Trigger

모델이 기본 또는 이전 명세와 동일한 호출·관측·불변식을 제안하면서 category만 바꾸고 specKey·제목을 새로 붙이면 해시가 달라져 `seen` 검사를 통과한다.

### Impact

기존 테스트가 새로운 AI 후보로 `PENDING_APPROVAL`에 저장되어 H5의 결정적 중복 제거 조건을 위반한다.

### Evidence

`TestSpecProposalFingerprint.of`는 operations와 purpose 사이에 category를 넣는다. `TestSpecGenerationService.kt:294-303`은 새 해시면 `specificationService.create`를 호출한다. H5 중복 테스트는 이름과 식별자만 바꾸며 category 변경을 검증하지 않는다.

### Recommendation

operation 순서와 판정 의미를 정규화하되 category는 fingerprint에서 제외한다. 기본·이전 명세와 category, 제목, key만 다른 사례를 테스트한다.

## REV-002

Severity: Major
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/TestSpecGenerationService.kt:138-145`; `TestSpecificationService.kt:254-259,488`

### Problem

생성 서비스는 최신 50개로 제한된 화면용 `findByTarget` 결과로 `baselineFingerprints`를 만든다. 같은 활성 Profile의 오래된 명세는 중복 검사에서 빠진다.

### Trigger

한 Profile에 명세가 50개를 넘고 모델이 50개 이전 명세와 동일한 호출 순서·판정을 새 specKey나 JSON으로 제안한다.

### Impact

오래된 중복 명세가 AI 추가 후보로 승인 대기 저장될 수 있다. 모델에 전달되는 `previousSpecifications`도 일부만 포함되지만 잘림 여부가 표시되지 않는다.

### Evidence

`TestSpecificationService.findByTarget`은 `MAX_LISTED_SPECIFICATIONS = 50`을 저장소에 전달하며 최신순으로 읽는다. 생성 경로는 그 결과만 fingerprint로 만들고, 이후 `seen`은 그 값과 현재 기본 후보로만 초기화된다. 승격 시 전체 명세 대상의 별도 fingerprint 조회는 없다.

### Recommendation

승격 시 Profile 범위 전체를 대상으로 중복을 조회하거나 페이지 조회·fingerprint 인덱스를 사용한다. 모델 입력의 요약 목록만 제한하고 잘림을 표시한다.

## REV-003

Severity: Major
Status: OPEN

### Location

`src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/TestSpecGenerationService.kt:211-228,290-303`; `TestSpecificationService.kt:45-55`

### Problem

생성 worker는 모델 응답 후 원래 Profile을 한 번 확인하지만, 후보별 `TestSpecificationService.create`는 그 순간의 활성 Profile을 다시 조회해 명세를 바인딩한다. 생성 run의 예상 Profile 버전을 전달하거나 완료 전 비교하지 않는다.

### Trigger

`requireRunProfile` 직후 또는 다중 후보 승격 사이에 활성 Profile이 바뀌고 새 Profile도 제안된 호출을 허용한다.

### Impact

옛 Snapshot·기본 후보에 근거한 제안이 새 Profile의 명세로 저장되면서 옛 생성 run에서 ACCEPTED로 보고될 수 있다. 한 run에 서로 다른 Profile 버전의 명세가 섞여 H5의 동일 활성 Profile 제약을 위반한다.

### Evidence

`executeOutboxJob`은 211행에서 Profile을 확인한 뒤 재확인 없이 후보를 순회한다. `toCandidate`는 기존 `profileVersionId`로 파싱하지만 `specificationService.create`에 그 ID를 전달하지 않는다. `create`는 활성 Profile을 다시 읽고 그 버전으로 재파싱한다. 기존 통합 테스트는 모델 호출 중 전환만 검증하고 후보 승격 중 전환은 다루지 않는다.

### Recommendation

승격에 run의 예상 Profile 버전을 전달하고 다르면 거부 또는 중단하며 저장 시점까지 원자적으로 보장한다. 두 후보 승격 사이의 Profile 전환 테스트를 추가한다.
