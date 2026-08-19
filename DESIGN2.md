# Target 이해 기반 테스트 계획·실행 확장 설계

## 1. 문서 목적

이 문서는 기존 `DESIGN.md`의 Phase 0–10.7 이후에 구현할 기능을 정의한다.

목표는 사용자가 테스트 대상 프로젝트의 정보를 제공하면 ARL이 대상의 기능과 위험 영역을 이해하고, 근거 있는 테스트 후보를 제안하며, 사용자가 선택·승인한 테스트만 안전하게 실행한 뒤 분석과 개선 제안까지 연결하는 것이다.

기존의 Target Profile, 안전한 HTTP Batch, Evidence, 분석, 원인 가설·개선 제안, 권한과 승인 구조는 유지하고 재사용한다. 이 문서는 그것들을 대체하지 않는다.

## 2. 최종 사용자 흐름

```text
프로젝트 정보 제공
  └─ OpenAPI / README / 아키텍처 설명 / 선택적 소스 스냅샷
        ↓
Target 이해 모델 생성 및 사용자 확인
        ↓
테스트 후보 제안
  └─ 가용성, 입력 경계, workflow, 재시도, 멱등성, 동시성, 정합성
        ↓
사용자가 여러 후보를 선택하거나 필요한 테스트를 직접 요청
        ↓
테스트 계획·사전 조건·데이터 lifecycle 확인
        ↓
명시 승인된 테스트만 안전하게 실행
        ↓
불변식 판정, Evidence 저장, AI 분석
        ↓
원인 가설과 개선 제안 제시
```

ARL은 코드 수정, PR 생성, 배포, 운영 환경 변경을 수행하지 않는다. 개선안은 항상 제안으로 끝나며 구현 여부는 사용자가 결정한다.

## 3. 문제를 두 단계로 나눈다

### 3.1 테스트 후보 제안

OpenAPI, README, endpoint 목록, 아키텍처 설명과 소스 스냅샷은 Target을 **이해하고 테스트 후보를 제안**하는 데 사용한다. 이 단계는 Target에 HTTP 요청을 보내지 않으며 Target 수정도 필요 없다.

endpoint 정보만으로는 health, 읽기 API, 입력 경계, timeout, 재시도 같은 후보를 비교적 잘 제안할 수 있다. 하지만 재고 음수 금지, 주문 중복 금지, 결제 한 번만 처리처럼 도메인 불변식은 endpoint만으로 확정할 수 없다. ARL은 추론 결과를 사실로 처리하지 않고, 사용자 확인이 필요한 가설로 표시한다.

### 3.2 실제 상태 변경 테스트 실행

동시성·멱등성·정합성 테스트는 데이터를 만들고, 병렬 요청을 보내고, 최종 상태를 관측하고, 데이터를 정리해야 한다. 이 작업은 Target의 실행 계약이나 Test Harness 없이 범용적으로 안전하게 수행할 수 없다.

따라서 ARL이 프로젝트마다 개별 Adapter나 테스트 endpoint를 계속 만드는 방식은 채택하지 않는다. 대신 Target이 한 번 구현하거나 배포하는 **범용 Test Harness 계약**을 정의한다. Harness는 Target 측의 표준 제어 계약이고, ARL 측에서는 기존 `ExperimentTargetAdapter`를 구현한 범용 HTTP Adapter가 이 계약을 호출한다.

Test Harness는 별도 실행 도메인이나 별도 상태 엔진이 아니다. 표준 Harness를 제공하는 Target은 공통 `HttpTestHarnessExperimentTargetAdapter`를 사용하고, 표준 계약으로 표현할 수 없는 Target만 기존 설계대로 Target Package Plugin에서 `ExperimentTargetAdapter`를 직접 구현한다. 두 경우 모두 기존 `ExperimentRun`이 실행·cleanup·복구의 단일 진실원천이다.

## 4. 범위와 비목표

### 4.1 범위

- 사용자가 제공한 문서와 선택적 소스 스냅샷을 읽어 Target 이해 모델을 만든다.
- 이해 모델과 확인된 Target Profile을 근거로 테스트 후보를 생성한다.
- 사용자가 후보를 여러 개 선택하고 필요한 테스트를 요청할 수 있게 한다.
- 읽기 전용 후보는 기존 안전한 HTTP Batch 실행 경로에 연결한다.
- 상태 변경 후보는 Test Harness capability가 있을 때만 계획·승인·실행한다.
- 실행 결과를 deterministic oracle과 Evidence로 저장하고, 기존 분석·원인 가설 기능에 연결한다.

### 4.2 비목표

- 임의의 로컬 경로, Git 저장소 URL, 네트워크 URL을 자동으로 읽거나 clone하지 않는다.
- OpenAPI의 외부 `$ref`, README 링크, 문서 안의 URL을 자동 fetch하지 않는다.
- ARL이 Target DB, Docker, 셸, Kubernetes, 메시지 브로커를 직접 제어하지 않는다.
- `STAGING` 또는 `PRODUCTION`에서 쓰기·부하·장애 테스트를 실행하지 않는다.
- LLM이 만든 자연어 요청이나 임의 HTTP body를 그대로 실행하지 않는다.
- 테스트 결과만으로 코드 변경이나 배포를 자동 승인하지 않는다.

## 5. 사용자가 제공하는 정보

### 5.1 최소 입력: 추천 전용

아래 중 하나 이상을 제공하면 ARL은 테스트 후보를 제안할 수 있다.

- OpenAPI 문서
- README 또는 서비스 설명
- endpoint 목록과 요청·응답 예시
- 주요 기능과 사용자 흐름 설명

이 입력만으로 생성한 후보에는 신뢰도와 확인이 필요한 가정이 함께 표시된다.

### 5.2 권장 입력: 더 정확한 추천

- 도메인 용어와 핵심 workflow
- 중요 불변식 예시
  - 동일 멱등성 키의 주문은 한 번만 생성된다.
  - 재고는 음수가 되지 않는다.
  - 결제 성공과 주문 상태는 모순되지 않는다.
- 비동기 처리, 이벤트, 캐시, 외부 결제 등 구성 요소 설명
- 테스트 가능한 `LOCAL` 또는 `TEST` 환경의 범위

### 5.3 상태 변경 테스트에 추가로 필요한 정보

- 격리된 테스트 계정 또는 test namespace
- 테스트 데이터의 생성·관측·정리 capability
- 검증할 불변식과 기대 결과
- 테스트에 사용할 operation과 허용한 parameter 범위

인증정보, 비밀번호, access token, DB 접속 문자열은 문서·Profile·prompt·Evidence에 넣지 않는다. 필요한 credential은 추후 Runner 환경에서만 secret reference로 해석하며, ARL DB에는 reference ID만 남긴다.

## 6. Target 정보 수집과 이해 모델

### 6.1 입력 경계

입력은 UI에서 명시적으로 붙여넣거나 업로드한 파일만 받는다. OpenAPI와 README의 원시 파싱·크기 제한·외부 fetch 금지는 기존 Phase 10.6의 bounded parser와 validation을 그대로 재사용한다. Phase 11은 이를 다시 구현하지 않고, 공용 `TargetDocumentationParseResult`를 받아 Target Profile Draft와 Target Knowledge Snapshot이라는 두 projection을 만든다.

수집 전 다음을 검증한다.

- 입력 종류별 최대 byte 수와 최대 파일 수
- 허용 확장자와 UTF-8 텍스트 형식
- YAML/JSON의 깊이, alias, duplicate key, unknown field 제한
- 소스 스냅샷의 압축 해제 크기, 파일 수, 경로 깊이 제한
- `.env`, credential, build output, dependency cache, binary, lock되지 않은 생성물 제외
- 문서와 소스 안의 모든 명령·URL·지시문은 실행 지시가 아닌 비신뢰 데이터로 취급

기존 Phase 10.6의 OpenAPI 1 MiB, README 256 KiB 한도와 internal `$ref` 처리 규칙은 유지한다. 처음에는 OpenAPI, README, 구조화한 Target Brief만 지원한다. 소스 스냅샷은 이후 Phase에서 명시적 업로드 방식으로만 추가하며 별도 압축 해제·파일 수 한도를 둔다. Git URL이나 임의 파일 경로 지원은 별도 인증·allowlist·감사 설계가 없으므로 포함하지 않는다.

소스 스냅샷을 추가하는 Phase에서는 원본 소스를 기본적으로 영구 보관하지 않는다. 기본 저장 대상은 해시, 파일 경로, 인용 위치와 정규화한 추출 결과로 제한하고, 원본은 분석 후 삭제하거나 명시적 보관 기간을 두는 정책을 그 Phase에서 별도로 확정한다. `.env`, credential, build output, dependency cache, binary 제외 규칙은 그대로 유지한다.

### 6.2 Target Knowledge Snapshot

ARL은 입력 원문과 해시를 바탕으로 불변 `TargetKnowledgeSnapshot`을 저장한다.

| 항목 | 내용 |
| --- | --- |
| Target Profile version | 어떤 실행 범위를 기준으로 만들었는지 |
| 입력 목록 | OpenAPI, README, Brief, 소스 스냅샷의 유형·해시·크기 |
| 추출한 operation | method, path, 요청 형식, 응답 상태, 읽기/쓰기 분류 |
| 추출한 workflow | 관련 operation 순서와 근거 |
| 도메인 가설 | 재고, 주문, 결제 같은 개념과 확인 필요 여부 |
| 추출한 불변식 | 문서에서 명시된 규칙과 근거 위치 |
| 위험 신호 | retry, async, cache, event, shared resource, idempotency key 등 |
| 경고 | 모호한 입력, 충돌, 누락, 지원하지 않는 형식 |

모델이 만든 요약은 원문 근거 위치를 반드시 가진다. 근거가 없는 도메인 규칙은 `ASSUMPTION`으로 표시하고, 실행 oracle로 사용하지 않는다.

### 6.3 활성화와 무효화

- Knowledge Snapshot은 Target Profile version에 귀속된다.
- Profile version, Target 환경, 허용 operation, CIDR, execution-enabled 상태가 바뀌면 이전 Snapshot을 새 실행에 사용할 수 없다.
- 기존 Snapshot·후보·계획·결과는 감사와 재현을 위해 보존한다.
- 실행 직전에는 기존 Phase 10과 동일하게 현재 활성 Profile과 실행 권한을 재검증한다.

## 7. 테스트 후보 생성

### 7.1 후보 분류

후보 생성기는 규칙 기반 추출과 LLM 제안을 결합한다. LLM은 후보를 제안할 뿐 실행 권한이 없고, deterministic validator가 category·parameter·capability·안전 조건을 검사한다.

초기 후보 분류는 다음과 같다.

| 분류 | 예시 | 실행 조건 |
| --- | --- | --- |
| 가용성 | health, status code, latency | 기존 읽기 전용 Batch |
| 계약·입력 | 경계값, 잘못된 요청의 오류 계약 | 안전한 non-mutating 계약이 확인된 경우 |
| workflow | 등록 후 조회, 취소 후 상태 확인 | 데이터 lifecycle capability 필요 |
| 재시도·복구 | timeout 뒤 retry, 중복 delivery | Harness capability 필요 |
| 멱등성 | 동일 idempotency key 반복 요청 | Harness와 oracle 필요 |
| 동시성 | 같은 재고/주문에 병렬 요청 | Harness와 격리 데이터 필요 |
| 정합성 | API 상태, 이벤트, read model의 일치 | 관측 capability와 명시된 invariant 필요 |

### 7.2 후보 계약

`TestCandidate`는 사용자에게 보여 주는 추천 단위이며, 새로운 실행 타입이나 별도 실행 엔진이 아니다. 실제 실행 의미는 기존 `ExperimentType` 또는 `TargetTestBatch`에만 둔다.

모든 `TestCandidate`는 아래 정보를 가진다.

- 제목, 설명, 분류, 위험도, 신뢰도
- source citation과 Knowledge Snapshot ID
- 검증하려는 불변식 또는 예상 결과
- 필요한 Target capability와 사전 조건
- 입력 parameter schema와 안전한 상한
- `ExecutionBinding` (저장하는 단일 진실원천):
  - `READ_ONLY_BATCH`: 기존 `TargetTestCandidate` ID 목록
  - `EXPERIMENT`: 기존 `ExperimentType`, 허용 parameter schema, 필요한 capability
  - `UNBOUND`: 아직 실행 바인딩을 확정할 수 없음. 이 경우 `unresolvedReason`(부족한 불변식·operation·테스트 데이터·관측 방법 등 7.3절의 부족 항목, 또는 "현재 Catalog가 지원하지 않는 테스트 유형")을 함께 저장한다.
- `ExperimentType` 또는 binding schema version. 자연어 HTTP 요청은 저장하지 않는다.
- 필요한 Evidence와 deterministic oracle
- 데이터 생성·cleanup 계획

`TestCandidate`는 실행 가능 상태를 DB 컬럼으로 저장하지 않는다. Profile 활성화, capability 등록, ExperimentType 변경으로 금방 stale해지기 때문이다. 대신 조회·선택·승인·dispatch 시점마다 `ExecutionBinding`과 현재 활성 Profile·capability 상태로 `readiness`를 계산한다.

```text
readiness (계산값, 저장하지 않음)

EXECUTABLE              = Binding이 있고 필요한 capability·환경 조건을 모두 충족
CAPABILITY_UNAVAILABLE  = Binding은 EXPERIMENT이지만 필요한 capability가 없음
                           (범용 Harness 부재, Target Package Plugin 부재,
                            환경 설정 또는 관측 capability 부재를 모두 포함)
NEEDS_USER_INPUT        = Binding이 UNBOUND이고 unresolvedReason이 사용자 입력으로 해소 가능
UNSUPPORTED             = Binding이 UNBOUND이고 unresolvedReason이 현재 Catalog로는
                           해소 불가능한 테스트 유형임을 나타냄
```

`CAPABILITY_UNAVAILABLE` 또는 `NEEDS_USER_INPUT` 후보는 사용자가 선택할 수 있지만, 필요한 조건을 만족하기 전에는 승인·실행할 수 없다.

LLM은 후보를 제안할 수 있지만 새로운 `ExperimentType`, capability, 실행 template를 생성할 수 없다. 새 deterministic 테스트 의미가 필요하면 기존 Experiment Catalog에 명시적으로 추가하고, 그 뒤에만 후보가 그 타입을 참조할 수 있다.

`가용성`과 `workflow` 분류는 보통 `READ_ONLY_BATCH`로, `동시성`·`멱등성`·`재시도·복구`·`정합성`은 `EXPERIMENT`로 바인딩된다. `계약·입력` 분류 중 `GET` 경계값 확인은 `READ_ONLY_BATCH`로 바인딩할 수 있지만, 잘못된 요청의 오류 계약처럼 상태를 바꿀 수 있는 호출(`POST`/`PUT`/`PATCH`/`DELETE`)을 검증하는 후보는 현재 안전한 실행 경로가 없으므로 `UNBOUND`로 두고 readiness를 `NEEDS_USER_INPUT` 또는 `UNSUPPORTED`로 계산한다. 이런 후보를 실행 가능한 것처럼 표시하지 않는다.

### 7.3 직접 요청

사용자는 “상품 재고 동시 차감 테스트”, “주문 생성 멱등성 테스트”처럼 원하는 테스트를 직접 요청할 수 있다. 이 요청은 기존 Knowledge Snapshot에 연결한 후보 초안으로 변환한다.

ARL은 요청에 필요한 불변식, operation, 테스트 데이터, 관측 방법이 부족하면 추측해 실행하지 않는다. 부족한 항목을 질문하거나 `NEEDS_USER_INPUT`으로 반환한다.

## 8. 선택, 테스트 계획, 승인

### 8.1 Test Plan

사용자가 하나 이상의 후보를 선택하면 ARL은 불변 `TestPlan`을 생성한다. TestPlan은 선택·입력·승인·감사를 위한 상위 aggregate이며 실제 workload의 상태를 소유하지 않는다.

Test Plan은 다음을 고정한다.

- Target Profile version과 Knowledge Snapshot ID
- 선택한 후보와 `ExecutionBinding` 또는 기존 `ExperimentType` version
- 사용자 입력 parameter와 상한 검증 결과
- test namespace, fixture, cleanup, 관측 계획
- 예상 Evidence와 oracle version
- 위험도와 필요한 승인 역할
- idempotency key, correlation ID, 생성 actor·시각

승인된 Plan을 dispatch하면 각 Plan item은 기존 실행 aggregate 하나에 idempotent하게 연결된다. 읽기 전용 item들은 하나의 기존 `TargetTestBatch`에 묶고, 상태 변경 item은 기존 `ExperimentRun`에 연결한다. 실행 상태·action journal·resource ledger·lease·cleanup·`RECOVERY_REQUIRED`는 이 연결된 aggregate만 소유한다.

dispatch는 Plan의 모든 item에 대한 실행 참조(`ExperimentRun`/`TargetTestBatch`)와 outbox job 생성을 하나의 DB 트랜잭션으로 처리한 뒤에만 Plan을 `DISPATCHED`로 전이한다. 일부 item만 실행 참조가 생성된 중간 상태로 Plan이 남지 않는다. dispatch 요청도 idempotency key로 재시도 가능해야 하며, 이미 `DISPATCHED`된 Plan에 같은 dispatch 요청이 재시도되면 기존 실행 참조를 그대로 반환하고 새로 생성하지 않는다.

TestPlan의 상태는 `DRAFT → PENDING_APPROVAL → APPROVED → DISPATCHED`와 `CANCELLED`/`SUPERSEDED`로 제한한다. `RUNNING`, `COMPLETED`, `FAILED`, `RECOVERY_REQUIRED`는 Plan 상태가 아니라 연결된 Batch 또는 ExperimentRun의 상태이며 UI에서는 그 요약을 파생해 표시한다.

같은 idempotency key로 다른 후보나 parameter를 보내면 conflict로 처리한다. Profile version, capability version 또는 parameter schema가 바뀌면 `DRAFT`·`PENDING_APPROVAL` Plan은 취소한다. 이미 `APPROVED`이지만 아직 `DISPATCHED`되지 않은 Plan도 dispatch 직전에 같은 조건을 다시 검사해 `SUPERSEDED`로 종료한다. 85.1절이 `TargetTestBatch`에 적용하는 규칙과 동일하다. 새 Profile·capability를 기준으로는 새 Plan을 만들어야 한다.

### 8.2 승인 정책

Plan에 필요한 승인 수준은 포함된 item 중 가장 높은 위험도를 따른다. 하나의 Plan에 읽기 전용 item과 상태 변경 item이 섞여 있으면 Plan 전체가 상태 변경 Plan의 승인 요건을 따른다.

- 읽기 전용 item만 있는 Plan: 기존 `EXECUTOR` 승인 규칙을 사용한다.
- 상태 변경 item이 하나라도 있는 Plan: `EXECUTOR`와 명시적인 write-test confirmation을 모두 요구한다.
- readiness가 `CAPABILITY_UNAVAILABLE`인 상태 변경 item이 포함된 Plan: 승인할 수 없다.
- 모든 승인에는 actor, Profile version, Knowledge Snapshot, Plan checksum, correlation ID를 append-only audit event로 기록한다.

LLM은 후보를 생성하거나 설명할 수 있지만, 승인하거나 실행 범위를 넓힐 수 없다.

## 9. 기존 ExperimentTargetAdapter를 사용하는 범용 Test Harness v1

### 9.1 목적

Test Harness는 Target마다 테스트 API를 계속 만드는 대신 한 번만 통합하는 Target 측 공통 제어면이다. Target이 구현한 내부 test module, sidecar 또는 별도 test runner일 수 있고 `LOCAL`/`TEST` 환경에서만 활성화한다.

Harness 자체는 ARL의 Kotlin interface를 구현하지 않는다. ARL의 `HttpTestHarnessExperimentTargetAdapter`가 기존 `ExperimentTargetAdapter`를 구현해 Harness HTTP 계약을 호출한다. 따라서 기존 Experiment Engine의 상태 전이, action journal, fencing token, resource ledger, cleanup 재시도, recovery를 그대로 재사용한다. Target의 DB·컨테이너·셸에 직접 접근하지 않는다.

```text
ExperimentRun
  → ExperimentTargetAdapter
    → HttpTestHarnessExperimentTargetAdapter
      → Target Test Harness HTTP 계약
```

### 9.2 최소 capability

Harness v1은 다음 lifecycle을 제공한다.

```text
capabilities 조회
→ 격리된 fixture 준비
→ 검증된 workload 실행
→ 상태·불변식 관측
→ cleanup 검증
```

각 capability는 다음을 명시한다.

- capability ID와 version
- 지원하는 기존 `ExperimentType`과 해당 version
- 허용 environment와 resource group
- 입력 schema, 최대 concurrency, 최대 요청 수, timeout
- fixture 생성 방식과 test namespace
- 관측 가능한 invariant ID와 결과 schema
- cleanup 방법과 cleanup 성공 판정

처음 제공할 capability는 하나로 제한한다. 예를 들어 `stock-concurrency-v1` capability는 기존 `STOCK_CONCURRENCY` ExperimentType을 지원한다. 준비된 테스트 상품 하나에 정해진 요청 수와 concurrency만 적용하고, `stock >= 0`, 총 성공 수, 최종 재고를 관측한다. 이는 새로운 ExperimentType이나 경쟁하는 template 체계가 아니다.

### 9.3 전송 계약

Harness API는 등록된 origin과 CIDR을 통과한 Target Profile에만 연결한다. 요청에는 ARL run ID, action ID, idempotency key, 기존 `experimentType`, 검증된 parameter, correlation ID만 포함한다.

Harness는 기존 `ExperimentTargetAdapter`가 기대하는 terminal result 또는 명시적 action status를 반환한다. 응답에는 fixture reference, 관측 결과, invariant 판정, 생성 resource 목록, cleanup 상태를 포함한다. 응답이 누락되거나 전송 결과가 불명확하면 기존 Experiment Engine이 자동 재실행하지 않고 `RECOVERY_REQUIRED`로 처리한다.

### 9.4 capability 없는 Target

Harness가 없는 Target은 다음만 가능하다.

- Profile에 선언한 읽기 전용 `GET` 점검
- 문서·명세 기반 테스트 후보 제안
- readiness가 `CAPABILITY_UNAVAILABLE`인 동시성·정합성 후보 확인

이는 제품의 제한이 아니라 안전 보장 조건이다. 데이터 생성, 병렬 쓰기, 최종 상태 검증, cleanup이 없는 범용 실행은 신뢰할 수 없고 Target을 손상시킬 수 있다.

### 9.5 표준 Harness와 Target Package Plugin의 선택

- Target이 표준 Harness HTTP 계약을 제공하면 공통 `HttpTestHarnessExperimentTargetAdapter`를 사용한다. ARL Core에 Target별 Adapter를 추가하지 않는다.
- Target의 독자적인 Docker, DB, 메시지 브로커 또는 사내 Test Harness가 표준 계약으로 표현되지 않으면 Target Package Plugin에서 기존 `ExperimentTargetAdapter`를 구현한다.
- 두 경로는 같은 `ExperimentRun`, `ExperimentAction`, `ExperimentResource`, Evidence, cleanup, recovery 규칙을 사용한다.

### 9.6 capability 신뢰 경계

Harness의 `capabilities` 응답은 ARL의 실행 범위를 새로 넓히지 않는다. Target Profile과 기존 Experiment Catalog가 이미 허용한 범위 안에 있는 capability만 인정하고, Profile이나 Catalog가 모르는 capability는 무시한다.

`TestPlan`은 승인 시점에 capability ID, version, parameter schema checksum을 고정한다. dispatch 직전에는 Plan checksum뿐 아니라 같은 capability가 여전히 같은 version·parameter schema로 존재하는지 다시 조회해 검증하고, 달라졌으면 실행하지 않고 `SUPERSEDED` 또는 `RECOVERY_REQUIRED`로 처리한다.

### 9.7 Harness lifecycle과 기존 Action의 매핑

Harness v1은 `HTTP_SCENARIO_V1`과 동일하게 하나의 동기 terminal action으로 제한한다.

```text
기존 ExperimentAction 호출
→ Harness 내부에서 fixture / workload / observation / cleanup 수행
→ terminal result 반환
```

Harness 내부의 비동기 진행 상태를 위한 별도 run 상태기계를 ARL에 두지 않는다. Harness 응답이 누락되거나 불명확하면 9.3절과 동일하게 `RECOVERY_REQUIRED`로 처리한다.

## 10. 실행, Evidence, oracle

### 10.1 실행 상태

기존 Durable Outbox와 lease worker를 재사용한다. TestPlan은 실행 상태를 새로 만들지 않고, 선택·승인·dispatch만 기록한다.

```text
TestPlan: DRAFT → PENDING_APPROVAL → APPROVED → DISPATCHED
                    ↓                    ↓
              CANCELLED / SUPERSEDED   기존 TargetTestBatch 또는 ExperimentRun
```

Plan dispatch는 Plan checksum, 현재 활성 Profile, environment, capability, execution-enabled를 검증한 뒤 기존 실행 aggregate와 outbox job을 생성·연결한다. 이후 worker는 `DESIGN.md` 13절의 ExperimentRun 상태기계 또는 기존 TargetTestBatch 상태기계만 실행한다. resource group lease, action fencing, cleanup과 `RECOVERY_REQUIRED`도 기존 실행 엔진이 단일하게 처리한다.

### 10.2 deterministic oracle 우선

Pass/Fail은 LLM이 결정하지 않는다. Harness의 자체 판정 문자열도 그대로 신뢰하지 않는다. Harness는 관측한 수치와 상태(재고, 성공/실패 count, checksum 등)만 반환하고, 최종 판정은 그 값을 기존 `ExperimentType`의 invariant 정의에 대입해 ARL이 계산한다.

- Harness가 반환한 관측값에 ARL이 계산한 invariant 판정
- 허용한 상태 코드
- 기대한 count, version, checksum
- cleanup 검증

같은 deterministic 규칙으로 먼저 판정한다. LLM은 그 결과와 Evidence를 해석하고 원인 가설·개선안을 제시한다.

### 10.3 Evidence

저장하는 Evidence는 최소화한다.

- 실행 계획과 `ExperimentType` 또는 ExecutionBinding version
- 상태 전이, 지연 시간, 집계 count, invariant 결과
- fixture/resource reference의 안전한 식별자
- 응답 본문의 크기와 해시
- cleanup 검증 결과

민감한 request/response body, credential, 원본 비즈니스 데이터는 Evidence와 모델 입력에서 제외한다. 분석 Dataset은 기존과 같은 immutable snapshot과 checksum 구조를 사용한다.

## 11. 보안·운영 규칙

- 모든 신규 API는 `SECURED` mode의 default-deny 정책과 역할 검사를 적용한다.
- Source intake, 후보 생성, Plan 생성, 승인, 실행, 결과 조회에 actor와 correlation ID를 남긴다.
- Source·문서 입력은 크기·개수·파싱 깊이를 제한하고 외부 fetch를 막는다.
- LLM prompt에는 입력 원문을 instruction이 아닌 비신뢰 데이터로 구획한다.
- 기존 `ExperimentType`, ExecutionBinding, capability ID allowlist 외의 action은 실행하지 않는다.
- `LOCAL`/`TEST` 이외 환경에서는 write-test와 Harness 호출을 거절한다.
- target resource group마다 기존 workload lease를 사용하여 읽기 Batch·Experiment·Harness 경유 Experiment workload의 위험한 동시 실행을 막는다.
- cleanup이 검증되지 않은 Target/namespace는 후속 상태 변경 테스트를 차단한다.

## 12. 코드 구조 초안

기존 도메인 구조를 유지하면서 아래 기능을 별도 모듈로 나눈다.

```text
targetintelligence/
  domain/              입력, Knowledge Snapshot, citation, 추출 경고
  application/         intake, 추출, 사용자 확인, 후보 생성 요청
  api/                 source intake와 knowledge 조회 DTO
  infrastructure/      Phase 10.6 parser 재사용, immutable 저장소, LLM extractor

testcatalog/
  domain/              TestCandidate, ExecutionBinding, capability requirement
  application/         규칙·LLM 후보 생성, 실행 가능성 검증
  api/                 후보 조회·직접 요청·다중 선택 DTO

testplan/
  domain/              TestPlan, PlanItem, approval, execution reference
  application/         plan 생성·승인·무효화·기존 실행 aggregate dispatch
  api/                 plan UI와 상태 조회 DTO

experiment/infrastructure/testharness/
  HttpTestHarnessExperimentTargetAdapter  기존 ExperimentTargetAdapter 구현
  TestHarnessHttpClient                    Harness capability·terminal result transport
  dto/                                     Harness HTTP 계약 DTO
```

새로운 Test Harness application port나 별도 TestHarnessRun domain은 만들지 않는다. 각 application service는 기존 port에만 의존한다. JDBC adapter와 SQL은 기존 규칙대로 `infrastructure/sql`에 분리한다. API 요청·응답 DTO는 클래스 안에 중첩하지 않고 파일별로 둔다.

## 13. 구현 Phase와 완료 조건

### Phase 11 — Target 정보 수집과 Knowledge Snapshot

**목표:** OpenAPI, README, Target Brief를 입력받아 불변 Knowledge Snapshot을 만들고 사용자가 검토할 수 있게 한다.

- Phase 10.6 OpenAPI/README bounded parser와 입력 검증 재사용, 소스 스냅샷의 추가 경계 구현
- OpenAPI operation과 README의 명시적 규칙 추출
- source citation, 추출 경고, 사용자 확인 API와 UI 구현
- Profile version에 연결하고 변경 시 실행 가능 상태를 무효화

**완료 기준:** 실제 Target에 HTTP 요청을 한 번도 보내지 않고, 사용자가 입력 근거와 가정을 확인할 수 있다.

### Phase 12 — 테스트 후보 생성과 다중 선택

**목표:** Knowledge Snapshot을 근거로 테스트 후보를 생성하고, 사용자가 여러 후보를 선택하거나 직접 요청할 수 있게 한다.

- 규칙 기반 후보와 구조화된 LLM 후보 생성
- category, risk, citation, precondition, `ExecutionBinding`, capability requirement 검증
- `EXECUTABLE`과 `CAPABILITY_UNAVAILABLE`을 포함한 readiness를 조회 시점에 계산해 명확히 구분
- 후보 다중 선택 UI와 idempotent candidate request 구현

**완료 기준:** 사용자는 “이 프로젝트에서 무엇을 테스트해야 하나?”에 근거·가정·실행 가능 상태가 포함된 후보 목록을 받는다.

### Phase 13 — Test Plan과 읽기 전용 실행 연결

**목표:** 선택한 읽기 전용 후보를 기존 안전한 HTTP Batch로 변환하고, 실행·결과·분석까지 한 화면에서 연결한다.

- Test Plan, 혼합 위험도 approval audit, `APPROVED` 상태를 포함한 Profile/capability 변경 무효화, 원자적 dispatch(실행 참조·outbox job 단일 트랜잭션), 기존 TargetTestBatch execution reference 구현
- 기존 candidate/batch API와 중복 없이 연결
- approval 후 선택한 후보만 실행
- 결과를 기존 AnalysisRun, comparison, root-cause 흐름에 연결

**완료 기준:** OpenAPI 또는 README 입력부터 읽기 전용 테스트 선택·승인·분석까지 끝단 흐름이 동작한다.

### Phase 14 — 범용 Test Harness v1

**목표:** 기존 Experiment Engine 위에서 첫 상태 변경 ExperimentType을 안전하게 실행할 공통 Harness 계약을 구현한다.

- capability discovery와 기존 ExperimentType/version/parameter validation
- fixture → workload → observation → cleanup lifecycle
- `HttpTestHarnessExperimentTargetAdapter`를 통한 기존 lease, timeout, idempotency, recovery 재사용
- capability ID·version·parameter schema checksum을 Plan에 고정하고 dispatch 직전 재검증
- 하나의 파일럿 Target에 `stock-concurrency-v1` capability와 기존 `STOCK_CONCURRENCY` 적용

**완료 기준:** Target별 ARL Adapter 추가 없이, 한 Harness capability로 격리 데이터 기반 동시성 테스트 한 종류를 안전하게 실행·정리할 수 있다.

### Phase 15 — 불변식 oracle과 분석 확장

**목표:** Harness 결과를 deterministic하게 판정하고, 실패 결과를 기존 AI 분석과 원인 가설에 연결한다.

- invariant/oracle version과 Evidence schema 구현
- cleanup 미검증·전송 불명확·관측 불가 상태의 안전한 처리
- analysis prompt에 plan·oracle·Evidence citation 연결
- 결과 화면에 판정, 근거, 가설, 개선안을 구분해 표시

**완료 기준:** “동시성 테스트가 실패했다”가 아니라 어떤 불변식이 어떤 근거로 깨졌는지와, 검증 가능한 개선 제안이 표시된다.

### Phase 16 — 파일럿, 운영 경계, 문서화

**목표:** 실제 LOCAL/TEST Target 하나로 전체 흐름을 검증하고, 다른 프로젝트가 재사용할 onboarding 자료를 완성한다.

- 입력 정보가 적은 Target과 Harness 제공 Target 각각 E2E 검증
- 재시작, 승인 중복, cleanup 실패, capability 변경, Profile 변경 회귀 테스트
- Docker, 권한, secret reference, Target Harness 통합 안내 문서화
- 독립 리뷰와 전체 보안·운영 점검

**완료 기준:** 새 프로젝트가 OpenAPI/설명만으로 후보를 받고, Harness가 있으면 선택한 상태 변경 테스트까지 실행·분석할 수 있다.

## 14. 구현 순서의 이유

Phase 11–12를 먼저 완료하면 Target 수정 없이도 사용자가 원하는 핵심 질문, 즉 “이 프로젝트에서 무엇을 테스트해야 하는가?”에 답할 수 있다.

Phase 13은 이미 구현한 Profile, Batch, 승인, Analysis 기능을 재사용해 가장 빠른 실제 가치인 읽기 전용 끝단 흐름을 만든다.

Phase 14–15는 쓰기·동시성·정합성 테스트의 안전 조건을 먼저 고정한 뒤, 기존 ExperimentType 하나를 Harness capability로 검증한다. 이 순서를 거치지 않고 임의 endpoint에 병렬 POST를 보내는 방식은 데이터 손상·오판·정리 실패 위험 때문에 채택하지 않는다.

## 15. 첫 파일럿에서 확인할 질문

Phase 14 착수 전에 아래 항목은 파일럿 Target마다 합의해야 한다.

1. 테스트할 환경은 `LOCAL` 또는 격리된 `TEST`인가?
2. 어떤 도메인 불변식을 테스트할 것인가?
3. Test Harness가 만들 test namespace와 fixture는 무엇인가?
4. 최종 상태를 어떤 capability로 관측할 것인가?
5. cleanup 성공을 어떻게 판정할 것인가?
6. 테스트 계정과 credential은 어떤 secret reference로 Runner에만 제공할 것인가?

이 여섯 항목이 확인되지 않은 후보는 제안할 수는 있어도 실행하면 안 된다.
