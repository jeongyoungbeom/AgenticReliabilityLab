# Autonomous Reliability Lab

> **문서 상태(2026-08-26):** 이 문서는 Phase 0–10.7의 기반 설계와 안전 경계를 보존하는
> 역사 문서다. 현재 파일럿의 고객 여정과 다음 구현 우선순위는
> [`DESIGN4.md`](DESIGN4.md)를 따른다. 이 문서의 `LOCAL`/`TEST` 제한, 승인·감사, 비밀값
> 비저장 원칙은 계속 유효하다.

> AI Agent가 외부 시스템에 신뢰성·동시성·장애·부하 실험을 수행하고, 수집된 Evidence를 분석하여 문제 가능성·원인 후보·추가 실험·개선 방향을 제안하는 자율 Reliability Testing Platform.

---

## 1. 문서 정보

* 문서 버전: `v0.4`
* 프로젝트명: `Autonomous Reliability Lab`
* 약칭: `ARL`
* 초기 예시 Target System: `Eventful Commerce`
* 초기 실행 환경: Local / Remote Test
* 초기 LLM Runtime: Ollama
* 초기 Agent Orchestration: 자체 구현
* LangGraph: 초기 범위에서 제외
* 최종 판단 및 코드 수정: 사용자

---

# 2. 프로젝트 배경

Eventful Commerce에는 다음과 같은 분산 시스템 요소가 이미 구현되어 있다.

* Kafka 기반 이벤트 통신
* Transactional Outbox
* Idempotent Consumer
* Redis Lua 기반 재고 예약
* Redis Cluster
* Redisson 분산락
* Choreography Saga
* Dead Letter Topic
* 서비스별 PostgreSQL
* Spring Batch
* API Gateway
* 복수의 독립 서비스

위 항목은 초기 설계 가정이다. 구현을 시작하기 전에 Eventful Commerce 저장소와 실제 실행 환경을 기준으로 다음 Capability Inventory를 확인하고 문서화한다.

```text
서비스별 실행·종료·Health 확인 방법

기존 Test Script와 입력 파라미터

공개 API와 Test 전용 API

Kafka Topic 접근 및 테스트 이벤트 주입 방법

Redis·PostgreSQL 상태를 읽는 안전한 방법

수집 가능한 Metric과 Log

실험 데이터 초기화·격리·정리 방법
```

확인되지 않은 기능이나 Metric은 구현된 사실로 간주하지 않고 `UNVERIFIED` Capability로 관리한다.

기존 테스트는 미리 정의된 시나리오가 정상 동작하는지를 확인하는 데 집중되어 있다.

Autonomous Reliability Lab은 여기서 한 단계 더 나아가 다음 질문에 답하는 것을 목표로 한다.

```text
현재 시스템은 어떤 조건까지 안전한가?

어떤 조건에서 성능이 급격하게 저하되는가?

어떤 장애 조합에서 데이터 정합성이 깨지는가?

현재 테스트가 놓치고 있는 취약 영역은 어디인가?

관측된 현상의 원인 후보는 무엇인가?

다음에는 어떤 실험을 해야 원인을 더 명확하게 확인할 수 있는가?

코드 변경 전후 실제로 개선되었는가?
```

단순히 테스트 스크립트를 자동 실행하는 것이 아니라,

```text
관찰
→ 가설
→ 실험
→ Evidence 수집
→ 분석
→ 추가 검증
→ Finding
→ 사용자 판단
→ 개선
→ 재검증
```

루프를 자동화한다.

---

# 3. 프로젝트 목표

## 3.1 핵심 목표

### 1. 외부 시스템에 대한 독립적인 Reliability 테스트

Autonomous Reliability Lab과 Target System은 서로 독립적인 프로젝트로 유지한다.

```text
Autonomous Reliability Lab
           │
           │ HTTP / Test API / Metrics
           ▼
      Target System
```

첫 Target System은 Eventful Commerce이지만 시스템 구조가 특정 프로젝트에 종속되지 않도록 한다.

---

### 2. 반복 가능한 Experiment 수행

AI가 임의의 테스트 코드를 생성해 실행하지 않는다.

모든 실제 테스트는 사전에 구현되고 검증된 `Experiment`를 통해 수행한다.

```text
AI Agent

"재고 동시성 테스트가 필요하다."
"동시 요청을 1,000 → 3,000으로 높여보자."

        ↓

ExperimentProposal

        ↓

ExperimentEngine

        ↓

StockConcurrencyExperiment

        ↓

Eventful Commerce
```

Agent는 **무엇을 검증할지** 판단한다.

Experiment Engine은 **어떻게 검증할지** 책임진다.

---

### 3. AI 기반 취약 영역 탐색

Agent는 과거 실험과 현재 Evidence를 조회하여 취약 가능성이 높은 영역을 선택한다.

Experiment Type에는 최소 `baseRisk`를 정의하지만 실제 실행 등급은 고정하지 않는다.

예:

```text
최근 OUTBOX_BACKLOG 실험에서

10,000 events
p95 = 830 ms

50,000 events
p95 = 7.4 sec

DB CPU = 34%
Kafka lag = 0

Agent 판단:

Outbox Publisher 처리량이 생성량을 따라가지 못하는
가능성이 있으므로 Publisher 처리량 증가 실험을 권장한다.
```

---

### 4. Single-Agent와 Multi-Agent 비교

동일한 Experiment Evidence를 이용하여 다음 구조를 비교한다.

```text
Single Agent

vs

Multi Agent
```

Multi-Agent 구조를 사용한다는 이유만으로 더 좋은 구조라고 가정하지 않는다.

정확도, 분석 품질, Tool Call, 추론 시간, Token 사용량 등의 데이터를 이용하여 비교한다.

---

### 5. 여러 LLM 비교

LLM을 특정 모델에 결합하지 않는다.

초기 비교 대상:

```text
GPT-OSS
Qwen3
```

향후:

```text
Claude
OpenAI
기타 Local Model
```

추가가 가능해야 한다.

---

### 6. 사람이 최종 판단

Agent는 다음까지만 수행한다.

```text
문제 후보 발견
원인 후보 분석
근거 제공
추가 실험 제안
개선 방향 제안
```

Agent가 직접 Production 코드 수정까지 수행하지 않는다.

```text
Agent
    ↓
Finding / Proposal
    ↓
사용자 검토
    ↓
사용자가 코드 수정
    ↓
동일 Experiment 재실행
    ↓
Before / After 비교
```

---

## 3.2 V1 범위

전체 로드맵을 한 번에 V1로 구현하지 않는다. V1은 하나의 Experiment를 끝까지 신뢰할 수 있게 실행하고 분석하는 데 집중한다.

```text
V1 Core

1. STOCK_CONCURRENCY Experiment
2. Campaign 1개와 재개 가능한 실행 상태
3. RunManifest와 구조화 Evidence
4. Deterministic Invariant 판정
5. 동일 조건 반복 실행과 baseline 변동성 확인
6. Single Agent + GPT-OSS 분석
7. 동일 Evidence를 이용한 GPT-OSS / Qwen 비교
```

다음 항목은 V1 Core 완료 후 확장한다.

```text
Multi-Agent
Automatic Investigation Loop
Failure Injection
Before / After Verification
여러 Target System
LangGraph
```

초기 Experiment 수보다 하나의 Experiment가 재현성·격리·정리·판정 계약을 완전히 만족하는 것을 우선한다.

로드맵 기준 V1 Core는 Phase 0부터 Phase 4까지이며, Phase 5 이후는 V1 이후 확장 범위다.

---

# 4. 비목표

초기 버전에서는 다음을 목표로 하지 않는다.

* Production 환경 테스트
* AI가 자유롭게 Shell 명령 생성 및 실행
* AI가 Target System 코드를 자동 수정
* AI가 DB 데이터를 임의로 변경
* AI가 새로운 Experiment 코드를 실시간으로 생성
* 완전 자율 장애 복구 시스템
* 실제 커머스 서비스 운영
* Kubernetes 기반 Chaos Engineering
* Chaos Mesh 도입
* LangGraph 도입
* 자동 Pull Request 생성
* Agent가 사용자 승인 없이 위험 작업 수행
* 모든 테스트를 LLM이 직접 판단
* 실시간 24시간 GPU 추론

---

# 5. 핵심 설계 원칙

## 5.1 AI와 Deterministic Logic 분리

AI는 판단과 제안을 담당한다.

Spring Backend는 실행, 수치 계산, 검증, 상태, 일관성을 담당한다.

```text
AI

가설
실험 선택
원인 후보
추가 실험
개선 제안

        │
        ▼

Backend

파라미터 검증
실험 실행
Metric 계산
p95/p99 계산
정합성 검사
상태 전이
실행 제한
결과 저장
```

---

## 5.2 LLM 결과를 사실로 간주하지 않는다

LLM이 다음과 같이 응답하더라도

```text
"Redis가 병목입니다."
```

시스템은 이것을 사실로 저장하지 않는다.

다음처럼 저장한다.

```text
Finding

type = ROOT_CAUSE_CANDIDATE
hypothesis = "Redis latency가 주요 병목일 가능성"
confidence = MEDIUM
evidenceIds = [...]
status = UNCONFIRMED
```

사용자 또는 추가 Experiment를 통해 검증한다.

---

## 5.3 Source of Truth

모든 실행 상태와 실험 결과의 Source of Truth는 PostgreSQL이다.

```text
PostgreSQL
= Campaign / CampaignStepRun
= PlannedRunSpec / RunManifest
= Experiment
= ExperimentRun
= ExperimentAction / ExperimentResource
= Evidence
= AnalysisDataset / Tool Replay
= AnalysisRun
= Finding
= Recommendation
= Evaluation
```

Redis나 LLM Memory를 시스템의 최종 상태 저장소로 사용하지 않는다.

---

## 5.4 Agent에게 직접 인프라 권한을 주지 않는다

금지:

```text
Agent
→ docker stop redis

Agent
→ DROP TABLE

Agent
→ shell command 생성
```

허용:

```text
Agent
→ REDIS_NODE_RESTART Experiment 요청

Backend
→ 해당 Experiment가 허용되었는지 확인
→ 환경이 LOCAL인지 확인
→ 실행 제한 확인
→ 미리 구현된 코드 실행
```

---

## 5.5 동일한 Evidence를 이용하여 비교한다

LLM 또는 Agent 구조 비교 시 Target System 테스트를 매번 다시 실행하지 않는 것을 기본으로 한다.

```text
Experiment
     ↓
Evidence Snapshot
     ↓
AnalysisDataset + Tool Replay
     ├─ Single + GPT-OSS
     ├─ Single + Qwen
     ├─ Multi + GPT-OSS
     └─ Multi + Qwen
```

이를 통해 Target System 실행의 랜덤성을 제거한다.

---

# 6. 시스템 전체 구조

```text
┌───────────────────────────────────────────────────────────┐
│                 Autonomous Reliability Lab                │
│                                                           │
│  ┌─────────────────────┐                                  │
│  │ Scheduler           │                                  │
│  └──────────┬──────────┘                                  │
│             ▼                                             │
│  ┌─────────────────────┐                                  │
│  │ Investigation       │                                  │
│  │ Coordinator         │                                  │
│  └──────────┬──────────┘                                  │
│             ▼                                             │
│  ┌──────────────────────────────┐                         │
│  │ ReliabilityAgent            │                         │
│  │                              │                         │
│  │ ┌────────────┐ ┌──────────┐ │                         │
│  │ │ Single     │ │ Multi    │ │                         │
│  │ │ Agent      │ │ Agent    │ │                         │
│  │ └────────────┘ └──────────┘ │                         │
│  └───────────────┬──────────────┘                         │
│                  ▼                                        │
│  ┌──────────────────────────────┐                         │
│  │ LlmClient                    │                         │
│  │                              │                         │
│  │ Ollama / Future Anthropic    │                         │
│  └───────────────┬──────────────┘                         │
│                  ▼                                        │
│  ┌──────────────────────────────┐                         │
│  │ LlmExecutionScheduler       │                         │
│  │ maxConcurrentInference = 1  │                         │
│  └───────────────┬──────────────┘                         │
│                  ▼                                        │
│               Ollama                                      │
│        GPT-OSS / Qwen3 / ...                              │
│                                                           │
│                                                           │
│  ┌─────────────────────┐                                  │
│  │ Experiment Engine   │                                  │
│  └──────────┬──────────┘                                  │
│             ▼                                             │
│  ┌─────────────────────┐                                  │
│  │ TargetSystem        │                                  │
│  │ Adapter             │                                  │
│  └──────────┬──────────┘                                  │
│             │                                             │
│  ┌──────────▼──────────┐                                  │
│  │ Evidence Collector │                                  │
│  └──────────┬──────────┘                                  │
│             ▼                                             │
│        PostgreSQL                                         │
└─────────────┬─────────────────────────────────────────────┘
              │
              │ HTTP / Metrics / Test API
              ▼
┌───────────────────────────────────────────────────────────┐
│                    Eventful Commerce                      │
│                                                           │
│ Gateway                                                   │
│ Order / Product / Payment / Shipping / Settlement         │
│ Kafka                                                     │
│ Redis Cluster                                             │
│ PostgreSQL                                                │
└───────────────────────────────────────────────────────────┘
```

---

# 7. 주요 모듈

초기에는 하나의 Spring Boot 애플리케이션 내부에서 모듈형 모놀리스로 구현한다.

```text
autonomous-reliability-lab/

├─ investigation
├─ experiment
├─ target
├─ evidence
├─ agent
├─ llm
├─ evaluation
├─ finding
├─ scheduler
└─ infrastructure
```

별도의 MSA로 분리하지 않는다.

---

# 8. Investigation

`Investigation`은 Agent가 특정 시스템 문제를 조사하는 전체 단위다.

예:

```text
Investigation

"Outbox 이벤트 발행 지연 원인 조사"
```

하나의 Investigation 안에서 여러 Experiment가 실행될 수 있다.

```text
Investigation

Experiment 1
↓
Evidence
↓
Agent 분석

증거 부족

Experiment 2
↓
Evidence
↓
Agent 분석

증거 충분

Finding
```

---

## 8.1 상태

```text
CREATED
↓
PLANNING
↓
READY
↓
ACTIVE
↓
COMPLETED
```

추가 상태:

```text
WAITING_APPROVAL
PAUSED
FAILED
CANCELED
```

`Investigation`은 여러 Experiment와 Analysis를 포함하는 장기 단위이므로 Experiment의 세부 상태를 복제하지 않는다. 현재 단계는 연결된 Run 상태에서 조회하며, 추가 Evidence가 필요하면 `ACTIVE` 상태에서 다음 Experiment를 생성한다.

```text
ACTIVE
↓
ExperimentRun
↓
AnalysisRun
↓
추가 Evidence 필요
↓
ExperimentRun
↓
AnalysisRun
↓
Finding 또는 종료 조건 충족
↓
COMPLETED
```

---

# 9. Target System 추상화

Autonomous Reliability Lab은 Eventful Commerce에 직접 결합하지 않는다.

```kotlin
interface TargetSystem {

    val id: TargetSystemId

    fun identity(): TargetIdentity

    fun capabilities(): Set<TargetCapability>

    fun health(): TargetSystemHealth
}
```

`TargetSystem`은 대상 식별·환경 검증·Capability 조회만 담당한다. Experiment 실행 lifecycle은 `ExperimentRunner`가 소유하고, 실제 작업은 목적별 Adapter를 사용한다.

```text
OrderCommandAdapter
StockObservationAdapter
KafkaTestAdapter
OutboxObservationAdapter
FailureControlAdapter
```

이를 통해 `TargetSystem`과 `Experiment`가 동시에 prepare/execute/collect/cleanup을 소유하는 책임 중복을 피한다.

`TargetSystem`은 가능한 한 공통 HTTP Target 구현을 사용한다. Eventful Commerce라는 이름, 저장소 경로, Script 경로와 비즈니스 규칙을 ARL Core의 클래스에 넣지 않는다.

```text
TargetSystem
    ↑
HttpTargetSystem
```

Target별 지식은 다음 두 종류로 분리한다.

```text
Target Profile
    - Target ID, Environment, allowed origin, Capability
    - Scenario ID, parameter limit, cleanup/evidence contract
    - 사용할 Experiment Target Adapter ID

Experiment Target Adapter
    - 표준 HTTP Scenario 계약을 호출하는 공통 Adapter
    - 또는 특정 Target Package가 제공하는 Plugin Adapter
```

```kotlin
interface ExperimentTargetAdapter {

    val adapterId: String

    fun supports(
        profile: TargetProfile,
        experimentType: ExperimentType
    ): Boolean

    fun execute(
        request: TargetExecutionRequest
    ): TargetExecutionResult
}
```

새 Target을 추가할 때 항상 ARL Core를 수정하지 않는다. Target이 표준 HTTP Scenario 계약을 제공하면 Target Profile만 등록한다. 독자적인 Docker, DB, 메시지 브로커 또는 사내 Test Harness가 필요할 때만 별도 Target Package Plugin을 한 번 작성한다.

## 9.1 Target Profile

Target Profile은 사람이 승인하고 ARL이 검증하는 실행 계약이다. 단순 README가 아니라 실행 가능한 범위와 정리 검증을 포함해야 한다.

```yaml
target:
  id: commerce-test
  environment: TEST
  baseUrl: https://commerce-test.internal
  allowedOrigin: https://commerce-test.internal
  allowedCidrs:
    - 10.0.0.0/8

execution:
  adapterId: HTTP_SCENARIO_V1
  runnerGroup: test-seoul

scenarios:
  STOCK_CONCURRENCY:
    endpoint: /reliability/v1/scenarios/STOCK_CONCURRENCY/executions
    limits:
      maxStock: 10000
      maxRequests: 100000
      maxConcurrency: 1000
    cleanupVerification: REQUIRED
```

Agent나 API 사용자는 Scenario ID와 검증된 parameter만 전달한다. URL, Shell 명령, Script 경로, DB 접속 정보는 요청 body에 넣지 않는다.

### HTTP_SCENARIO_V1 결과 계약

공통 HTTP Adapter는 Profile에 등록된 같은 origin의 endpoint만 호출한다. 호출 직전에 DNS를 다시 해석하고, 모든 응답 주소가 Target Profile의 `allowedCidrs` 안에 있을 때만 연결한다. Phase 1 전송 계층은 검증된 IP에 socket을 고정하고 원래 hostname으로 Host/SNI 및 TLS hostname 검증을 수행하므로, HTTP client의 재해석으로 다른 IP에 연결하지 않는다. `executionTimeout`은 DNS, TCP/TLS 연결, write와 모든 response read를 합친 전체 deadline이며, deadline watchdog이 socket을 닫는다. 응답은 streaming reader로 읽고 body는 1 MiB, headers와 chunk trailers는 각각 총 32 KiB·64개로 제한한다. 어느 한도를 넘으면 결과 미확정으로 처리한다. 현재 Phase 1은 동기 terminal response만 지원한다.

```text
POST /reliability/v1/scenarios/STOCK_CONCURRENCY/executions

Request
- contractVersion
- experimentType
- runId
- actionId
- idempotencyKey = runId + actionId
- validated parameters

Response (2xx)
- operationId
- status = COMPLETED | FAILED
- result.successCount / failureCount / oversellCount
- result.finalRedisStock / finalDbStock
- result.durationSeconds
- resources[]
- cleanup.status = VERIFIED | FAILED
- artifact.reference / checksum (optional)
```

`COMPLETED`만 정합성 통과를 의미하지 않는다. ARL은 반환된 Evidence로 invariant를 다시 계산한다. 네트워크 오류, non-2xx 또는 읽을 수 없는 결과는 외부 작업 결과가 불명확한 경우로 처리해 자동 재실행하지 않는다. 생성 Resource가 없거나 `cleanup.status`가 `VERIFIED`가 아니면 다음 Experiment를 차단한다.

## 9.2 원격 Target과 Runner

ARL은 Control Plane이고, Target 실행은 Target에 네트워크적으로 가까운 Runner가 담당할 수 있다.

```text
ARL Control Plane
  └─ Experiment 상태, 정책, Evidence, 분석

Target Environment (Local / Test VPC / 사내망 / Kubernetes)
  └─ Runner
       └─ 등록된 Target Profile과 Adapter로만 Target 실행
```

Phase 1의 공통 HTTP Adapter는 ARL 또는 Runner가 Target allowed origin에 도달할 수 있고, 연결 직전 DNS 결과가 등록된 `allowedCidrs`에 모두 포함될 때 사용한다. 전송은 검증된 IP에 고정되며 HTTPS는 원래 hostname의 SNI와 certificate hostname 검증을 유지한다. 향후 Remote Runner는 같은 Adapter 계약을 유지하며, Target을 인터넷에 공개하거나 임의 inbound 권한을 추가하지 않는다. Credential은 값이 아니라 Target 환경의 secret reference로 전달한다.

환경 정책 기본값:

```text
LOCAL: 자동 실행 가능
TEST: 명시적으로 enabled인 Profile만 실행 가능
STAGING: 승인·시간창 정책 구현 전 실행 비활성
PRODUCTION: 관찰 전용, 부하·장애 주입 기본 차단
```

---

# 10. Experiment 모델

Agent는 Experiment를 직접 구현하지 않는다.

모든 Experiment는 코드로 사전에 등록한다.

```kotlin
interface Experiment {

    val type: ExperimentType

    val definitionVersion: String

    fun validate(
        parameters: ExperimentParameters
    ): ValidationResult

    fun prepare(
        context: ExperimentContext
    )

    fun execute(
        context: ExperimentContext
    ): ExperimentExecutionResult

    fun collect(
        context: ExperimentContext
    ): EvidenceBundle

    fun cleanup(
        context: ExperimentContext
    ): CleanupResult
}
```

`cleanup`은 부분 실행 이후에도 반복 호출할 수 있도록 멱등하게 구현한다. `ExperimentRunner`는 prepare가 시작된 이후 성공·실패·취소·timeout과 무관하게 cleanup을 시도한다.

DB lease와 fencing token은 Lab 내부의 중복 저장만 방지한다. 이미 Target 또는 Load Generator에 전달된 외부 작업의 중복 실행은 별도로 제어한다.

모든 외부 작업은 durable action journal을 먼저 기록한 뒤 실행한다.

```text
ExperimentAction

id
experimentRunId
actionId
actionType
requestHash
status = PLANNED | DISPATCHED | CONFIRMED | FAILED | UNKNOWN_OUTCOME
targetOperationId
fencingToken
attempt
dispatchedAt
confirmedAt
lastError
```

```text
ExperimentResource

id
experimentRunId
actionId
resourceType
resourceId
namespace
cleanupStatus
cleanupAttempt
lastCleanupError
```

HTTP 요청, Load Generator 시작, 장애 주입과 fixture 생성에는 `experimentRunId + actionId`를 전달한다. Target 또는 실행 도구가 idempotency key나 operation status 조회를 지원하면 이를 이용해 reconcile한다.

Worker가 `DISPATCHED` 이후 결과 확인 전에 종료되어 외부 부작용을 판별할 수 없으면 자동으로 같은 작업을 재실행하지 않는다.

```text
UNKNOWN_OUTCOME
→ Target / Load Generator 상태 조회
→ CONFIRMED 또는 FAILED로 reconcile
→ 판별 불가 시 ExperimentRun = RECOVERY_REQUIRED
```

Target 측 idempotency나 상태 조회가 불가능한 작업은 “중복 없이 자동 재개” 대상으로 간주하지 않는다. 사용자가 상태를 확인하거나 안전한 cleanup을 완료한 뒤 새 Run으로 다시 실행한다.

Cleanup은 action journal과 resource ledger를 기준으로 수행한다. 각 cleanup action에는 timeout, 최대 재시도 횟수와 backoff를 적용한다. 상한을 초과하면 `cleanupStatus = FAILED`, `runStatus = RECOVERY_REQUIRED`로 저장하고 수동 복구 절차와 남은 resource 목록을 제공한다.

모든 Experiment Definition은 다음 계약을 제공한다.

```text
definitionVersion
requiredTargetCapabilities
parameterSchema
preconditions
fixtureStrategy
loadProfile
warmupPolicy
measurementWindow
invariants
settlingCondition
timeout
abortConditions
cleanupPolicy
cleanupVerification
```

`invariants`와 수치 계산은 Backend가 수행한다. LLM은 판정 결과와 Evidence를 해석하지만 pass/fail을 임의로 계산하지 않는다.

성능·부하 Experiment의 `loadProfile`은 다음 필드를 명시한다.

```text
workloadModel = OPEN_LOOP | CLOSED_LOOP
targetRps 또는 concurrency
arrivalDistribution
rampUpDuration
warmupDuration
measurementDuration
cooldownDuration
requestCountLimit
clientConnectionPoolSize
requestTimeout
retryPolicy
rateLimitPolicy
percentileRecorder
coordinatedOmissionCorrection
```

값이 없는 항목은 구현체가 임의로 추측하지 않고 Experiment Definition의 명시적 default를 사용하며, 실제 적용된 effective Load Profile을 `planned_run_spec`과 Evidence에 저장한다. 성공, 비즈니스 거절, timeout, transport 오류 latency는 분리 집계한다.

---

# 11. 초기 Experiment Catalog

## 11.1 STOCK_CONCURRENCY

재고보다 많은 동시 주문을 발생시킨다.

입력:

```text
stock
requestCount
concurrency
quantityPerRequest
```

측정:

```text
successCount
failureCount
oversellCount

p50
p95
p99

Redis stock
DB stock

Redis latency
API latency
```

초기 판정 계약:

```text
사전 조건:
- Experiment 전용 상품과 고유 runId 사용
- 초기 Redis/DB 재고가 요청값과 일치
- Target health와 필수 Capability 확인

정합성 Invariant:
- 확정된 총 판매 수량 <= 초기 재고
- 하나의 요청은 최대 한 번만 확정
- settling timeout 안에 Redis/DB 재고가 정책상 기대값으로 수렴
- 결과에 포함되지 않은 주문 상태가 없어야 함

성능 판정:
- 성공/비즈니스 거절/시스템 오류 latency를 분리
- 사전 정의된 warm-up 이후 측정
- raw sample 또는 histogram에서 percentile 계산

정리:
- 실험용 데이터 namespace 정리
- Redis/DB/서비스 상태가 다음 실험의 사전 조건을 만족하는지 검증
```

`oversellCount`, `failureCount`, `DB stock`과 같은 값은 Experiment Definition 버전별 계산식을 문서화한다. Eventual Consistency가 있는 값은 즉시 비교하지 않고 `settlingCondition`과 timeout을 적용한다.

---

## 11.2 ORDER_CANCEL_RACE

동일 주문에 여러 취소 요청을 동시에 보낸다.

입력:

```text
concurrency
requestCount
```

검증:

```text
실제 취소 처리 횟수
환불 생성 횟수
재고 반환 횟수
정산 변경 횟수
```

---

## 11.3 KAFKA_DUPLICATE_EVENT

동일 Event ID를 반복 전달한다.

입력:

```text
duplicateCount
concurrency
eventType
```

측정:

```text
consumeCount
processedCount
duplicateRejectedCount
businessExecutionCount
consumerLag
DB conflictCount
```

---

## 11.4 CONSUMER_RESTART

Kafka Consumer 처리 중 Consumer를 재시작한다.

확인:

```text
중복 소비
누락
재처리
DLT
최종 상태
```

---

## 11.5 OUTBOX_BACKLOG

Outbox Event를 대량 생성한다.

입력:

```text
eventCount
creationRate
```

측정:

```text
backlogSize
publishThroughput
publishLatencyP95
DB CPU
DB Query latency
Kafka producer latency
Kafka lag
```

---

## 11.6 REDIS_FAILURE

Redis 일부 또는 전체 장애를 발생시킨다.

초기에는 명시적인 Local Test 환경에서만 허용한다.

확인:

```text
서비스 실패 방식
DB 상태
Redis 복구 후 상태
재고 정합성
오류 응답
재처리 가능 여부
```

---

## 11.7 SHIPPING_SAGA_FAILURE

Shipping 처리 실패를 발생시킨다.

확인:

```text
Order
Payment
Inventory
Shipping
Settlement
```

각 서비스의 최종 상태가 Saga 정책과 일치하는지 검사한다.

각 Catalog 항목은 구현 전에 `STOCK_CONCURRENCY`와 동일한 수준으로 사전 조건, 판정식, 수렴 조건, 중단 조건과 cleanup 검증을 작성한다. 측정 항목만 정의된 Experiment는 자동 실행 Catalog에 등록하지 않는다.

---

# 12. Experiment Parameter Safety

Agent가 제안한 파라미터를 그대로 실행하지 않는다.

예:

```text
Agent Proposal

scenario = STOCK_CONCURRENCY
concurrency = 10000000
```

Backend:

```text
MAX_CONCURRENCY = 10000

→ reject
```

설정 예:

```yaml
experiment:
  limits:
    max-concurrency: 10000
    max-requests: 100000
    max-duration-seconds: 600
    destructive-experiments-enabled: false
```

---

# 13. Experiment 실행 상태

```text
CREATED
↓
VALIDATING
↓
READY
↓
PREPARING
↓
RUNNING
↓
COLLECTING
↓
CLEANING
↓
COMPLETED
```

실패:

```text
VALIDATION_FAILED
FAILED
TIMED_OUT
ABORTED
CANCELED
RECOVERY_REQUIRED
```

`COMPLETED`는 cleanup과 cleanup verification까지 끝난 최종 상태다. `PREPARING` 이후 오류·취소·timeout이 발생하면 가능한 Evidence를 저장하고 `CLEANING`을 거쳐 최종 상태로 전이한다.

실패 원인은 상태 하나에 합치지 않고 다음 필드로 구분한다.

```text
execution_failure_phase = PREPARATION | EXECUTION | COLLECTION
execution_failure_owner = LAB | TARGET | ENVIRONMENT | USER
execution_failure_code
execution_failure_message
cleanup_status = NOT_REQUIRED | PENDING | SUCCEEDED | FAILED
cleanup_failure_code
cleanup_failure_message
cleanup_attempt
```

`RECOVERY_REQUIRED`는 외부 작업 결과 또는 cleanup 완료 여부를 자동 판별할 수 없는 상태다. 이 상태에서는 같은 Target의 다음 Campaign Step을 시작하지 않는다.

Experiment 실패는 Target의 취약성일 수 있지만 Lab 자체 오류는 신뢰성 Finding으로 해석하지 않는다.

---

# 14. Evidence

Agent에게 Raw Log 전체를 직접 전달하지 않는다.

먼저 Backend에서 Evidence를 구조화한다.

Raw Log와 원본 Metric을 버리는 것은 아니다. 재분석과 수집 오류 검증을 위해 크기 제한·보존 기간·민감정보 제거 정책을 적용한 원본 Artifact를 보존하고, 구조화 Evidence에서 immutable reference와 checksum으로 연결한다. Agent에는 필요한 요약과 제한된 원문 구간만 제공한다.

모든 Evidence는 payload 외에 다음 provenance를 가진다.

```text
source
collectorVersion
observedAt 또는 windowStart/windowEnd
unit
aggregationMethod
sampleCount
completeness = COMPLETE | PARTIAL | UNAVAILABLE
artifactRefs
checksum
```

예:

```json
{
  "experimentType": "STOCK_CONCURRENCY",
  "parameters": {
    "stock": 100,
    "concurrency": 5000
  },
  "result": {
    "requests": 5000,
    "success": 100,
    "failure": 4900,
    "oversell": 0
  },
  "latency": {
    "p50Ms": 54,
    "p95Ms": 482,
    "p99Ms": 811
  },
  "redis": {
    "stock": 0,
    "p95LatencyMs": 11
  },
  "database": {
    "stock": 0,
    "poolWaitP95Ms": 417
  },
  "kafka": {
    "maxLag": 0
  }
}
```

Agent는 이 Evidence를 해석한다.

---

# 15. Evidence 유형

```text
EXPERIMENT_RESULT
PERFORMANCE_METRIC
CONSISTENCY_CHECK
KAFKA_METRIC
REDIS_METRIC
DATABASE_METRIC
SERVICE_HEALTH
ERROR_SUMMARY
LOG_SUMMARY
STATE_SNAPSHOT
```

---

# 16. Evidence Snapshot과 Analysis Dataset

Agent 및 모델 비교가 가능하도록 특정 분석 시점의 Evidence를 Snapshot으로 저장한다.

```text
EvidenceSnapshot

id
experimentRunId
schemaVersion
createdAt
payload
```

한 번 저장된 Snapshot은 LLM 비교 중 변경하지 않는다.

하나의 분석이 여러 Experiment, baseline, 과거 추세를 사용하므로 단일 `experimentRunId`만으로 분석 입력 전체를 표현하지 않는다.

```text
AnalysisDataset

id
schemaVersion
purpose
createdAt
datasetManifest
evidenceSnapshotIds
toolReplayEntryIds
toolReplayChecksum
checksum
```

`AnalysisRun`은 `AnalysisDataset`을 참조한다. Dataset manifest에는 포함된 Run, Evidence 순서, 허용된 Tool Replay, 제외된 필드와 Ground Truth 비공개 여부를 기록한다. 생성된 Dataset은 평가가 끝날 때까지 변경하지 않는다.

---

# 17. Agent 추상화

상위 Application은 Single/Multi 여부를 알 필요가 없다.

```kotlin
interface ReliabilityAgent {

    fun investigate(
        context: InvestigationContext
    ): AgentAnalysisResult
}
```

구현:

```text
ReliabilityAgent
    ↑
├─ SingleReliabilityAgent
└─ MultiReliabilityAgent
```

---

# 18. Single Agent

Single Agent는 하나의 Agent가 전체 분석을 수행한다.

```text
Evidence 조회
↓
이상 해석
↓
가설
↓
추가 Experiment 제안
↓
Finding 생성
```

초기 구현은 Single Agent부터 완성한다.

---

# 19. Multi-Agent

Multi-Agent는 초기 Single-Agent가 정상적으로 동작한 이후 구현한다.

초기 구성:

```text
Supervisor
Experiment Planner
Evidence Analyst
Reviewer
```

불필요하게 Agent 개수를 늘리지 않는다.

---

# 20. Supervisor Agent

책임:

```text
현재 Investigation 상태 파악

현재 Evidence 파악

어떤 Agent를 호출할지 결정

최종 결과 조율
```

세부 실험 설계나 원인 분석은 직접 수행하지 않는 것을 원칙으로 한다.

---

# 21. Experiment Planner Agent

책임:

```text
검증할 가설 정의

사용할 Experiment 선택

Experiment Parameters 제안

측정해야 할 Evidence 정의
```

예:

```json
{
  "hypothesis": "Outbox publisher throughput이 event creation rate를 따라가지 못한다.",
  "experiment": {
    "type": "OUTBOX_BACKLOG",
    "parameters": {
      "eventCount": 50000
    }
  },
  "requiredEvidence": [
    "OUTBOX_BACKLOG",
    "PUBLISH_THROUGHPUT",
    "DATABASE_CPU",
    "KAFKA_LAG"
  ]
}
```

---

# 22. Evidence Analyst Agent

책임:

```text
Experiment 결과 분석

여러 Evidence 사이의 상관관계 탐색

원인 후보 작성

반증 Evidence 확인

추가 Experiment 필요 여부 판단
```

---

# 23. Reviewer Agent

Analyst의 결론을 검증한다.

주요 질문:

```text
결론을 Evidence가 실제로 뒷받침하는가?

상관관계를 인과관계로 잘못 해석하지 않았는가?

반대 Evidence가 존재하지 않는가?

추가 실험 없이 확정하기 어려운 것은 아닌가?

Agent가 존재하지 않는 데이터를 만들어내지 않았는가?
```

출력:

```text
APPROVE
NEED_MORE_EVIDENCE
REJECT
```

---

# 24. Multi-Agent 실행 예

```text
Supervisor

↓ 최근 Evidence 분석 필요

Planner

↓
OUTBOX_BACKLOG
eventCount = 50000

↓
ExperimentEngine

↓
Evidence

↓
Analyst

"Outbox Publisher throughput 한계가 주요 원인 후보"

↓
Reviewer

"현재 Evidence만으로 Polling Query와 Publisher 처리량을 구분할 수 없음"

↓
Planner

publisher count 변경 Experiment 제안

↓
Experiment

↓
Analyst

↓
Reviewer

↓
Finding
```

---

# 25. LLM 추상화

Provider와 Model을 분리한다.

```text
Provider
├─ OLLAMA
├─ ANTHROPIC
└─ OPENAI


Model
├─ gpt-oss:20b
├─ qwen3:14b
├─ qwen3:30b
└─ ...
```

---

# 26. LlmClient

```kotlin
interface LlmClient {

    fun generate(
        request: LlmRequest
    ): LlmResponse

    fun capabilities(): LlmCapabilities
}
```

초기 구현:

```text
LlmClient
    ↑
OllamaLlmClient
```

향후:

```text
LlmClient
    ↑
├─ OllamaLlmClient
├─ AnthropicLlmClient
└─ OpenAiLlmClient
```

---

# 27. LLM Capability

모델마다 지원 능력이 다를 수 있으므로 Capability를 표현한다.

```kotlin
data class LlmCapabilities(
    val toolCalling: Boolean,
    val structuredOutput: Boolean,
    val reasoning: Boolean,
    val maxContextTokens: Int?
)
```

Agent는 Capability가 부족한 모델에 지원하지 않는 기능을 요구하지 않는다.

---

# 28. 초기 LLM

초기 후보 모델:

```text
GPT-OSS 20B
Qwen3 14B 또는 30B
```

실제 model tag, quantization과 context size는 로컬 환경에서 실행 가능 여부를 확인한 뒤 고정한다.

기본 개발 모델:

```text
GPT-OSS 20B
```

비교 모델:

```text
Qwen3
```

Qwen3 Coder는 추후 코드 분석 Agent를 추가할 때 검토한다.

---

# 29. 로컬 LLM 동시 실행 제한

초기 실행 환경에서는 여러 LLM inference를 동시에 수행하지 않는다.

```text
maxConcurrentInference = 1
```

---

# 30. LlmExecutionScheduler

모든 로컬 모델 요청은 Scheduler를 통과한다.

```text
Agent A ─┐
Agent B ─┼→ LlmExecutionScheduler → Ollama
Agent C ─┘
```

큐:

```text
Request A
Request B
Request C
```

실행:

```text
A
↓
완료
↓
B
↓
완료
↓
C
```

---

# 31. Multi-Agent와 병렬 실행의 분리

Multi-Agent는 여러 역할을 가진다는 의미이지 병렬 GPU 실행을 의미하지 않는다.

초기 Multi-Agent는 순차 실행한다.

```text
Planner
↓
Experiment
↓
Analyst
↓
Reviewer
```

---

# 32. 모델 비교 시 실행 정책

GPT-OSS와 Qwen을 동시에 추론시키지 않는다.

```text
AnalysisDataset + Tool Replay

↓
GPT-OSS Analysis
↓
Result 저장

↓
Qwen Analysis
↓
Result 저장

↓
Comparison
```

성능 비교에서 GPU contention 영향을 제거한다.

---

# 33. 모델 Batch 실행

모델 변경 비용이 큰 경우 다음 실행도 허용한다.

```text
Evidence 1
Evidence 2
Evidence 3
Evidence 4

        ↓

GPT-OSS 전부 분석

        ↓

Qwen 전부 분석
```

모델 batch 실행은 Experiment Campaign과 시간적으로 분리한다. 초기 Local 환경에서는 Target 부하 측정 중 Ollama inference를 실행하지 않는다.

```text
Experiment Window
→ Target + Load Generator + Evidence Collector만 실행

Analysis Window
→ Experiment 종료와 Target 안정화 확인
→ Ollama 분석 실행
```

같은 PC에서 Target, Load Generator, Lab을 실행하는 경우 CPU·Memory·Disk I/O·Network·Load Generator saturation을 Evidence에 포함한다. 가능하면 Load Generator를 별도 실행 위치로 분리하고, 불가능하면 container resource limit과 Host background workload를 고정한다.

---

# 34. Agent Tool

Agent에게 제공되는 Tool은 제한한다.

초기 Tool:

```text
getRecentExperiments

getExperimentResult

getEvidenceSnapshot

getLatencyTrend

getKafkaMetrics

getRedisMetrics

getDatabaseMetrics

checkConsistency

proposeExperiment

requestExperimentExecution

saveFinding
```

`requestExperimentExecution`은 인프라 명령을 직접 수행하는 Tool이 아니다. Catalog에 등록된 Experiment 실행 요청을 생성하며, Backend의 Capability·환경 Identity·위험도·승인·예산·동시 실행 정책을 모두 통과한 경우에만 실제 Run이 생성된다. 정책을 통과하지 못하면 거절 사유를 Evidence로 반환한다.

## 34.1 Agent Tool 실행 모드

Tool은 실행 목적에 따라 두 모드로 분리한다.

```text
LIVE_INVESTIGATION

현재 PostgreSQL과 등록된 Collector 조회 가능
정책을 통과한 Experiment Proposal 생성 가능
Finding은 사용자 검토 전 DRAFT로 저장
```

```text
EVALUATION_REPLAY

AnalysisDataset에 포함된 불변 Tool Replay만 조회
현재 DB, 최신 Metric, 외부 시스템 조회 금지
requestExperimentExecution은 dry-run Proposal로 변환
saveFinding은 evaluation_run 내부 Draft로만 저장
운영 Finding, Campaign, Experiment 상태 변경 금지
```

Evaluation Dataset을 만들 때 허용된 Tool별 요청과 응답을 정규화하여 저장한다.

```text
ToolReplayEntry

analysisDatasetId
toolName
normalizedArguments
responsePayload
responseChecksum
sequencePolicy
```

모든 실제 Tool Call은 다음 transcript를 저장한다.

```text
AgentToolCall

analysisRunId
agentStepRunId
sequence
mode
toolName
argumentsHash
responseChecksum
startedAt
completedAt
status
```

동일 Dataset 비교에서는 Tool 응답의 정렬 순서와 checksum이 모든 모델 실행에서 일치해야 한다. 일치하지 않으면 해당 Evaluation 비교는 `INVALID_INPUT_DRIFT`로 처리한다.

---

# 35. Agent에게 제공하지 않는 Tool

초기 금지:

```text
executeShell

executeArbitrarySql

writeSourceCode

deleteFile

gitCommit

gitPush

dockerCommand

killProcess
```

---

# 36. Structured Output

Agent 결과를 일반 문자열로만 받지 않는다.

예:

```json
{
  "summary": "동시 요청 증가에 따라 DB connection pool wait가 급격히 증가했다.",
  "hypotheses": [
    {
      "description": "DB connection pool 대기가 주요 latency 원인일 가능성이 높다.",
      "confidence": "HIGH",
      "evidenceIds": [
        "ev-1",
        "ev-3"
      ]
    }
  ],
  "recommendedExperiment": {
    "type": "STOCK_CONCURRENCY",
    "parameters": {
      "concurrency": 7000
    },
    "reason": "현재 latency 증가 경계를 더 명확히 확인하기 위해 필요하다."
  }
}
```

---

# 37. Finding

Agent가 발견한 문제 후보.

```text
Finding

id
targetSystemId
investigationId
severity
type
title
description
confidence
status
createdAt
```

---

# 38. Finding 상태

```text
UNCONFIRMED
↓
CONFIRMED
↓
RESOLVED
```

기타:

```text
REJECTED
FALSE_POSITIVE
IGNORED
```

Agent가 자동으로 `CONFIRMED`로 만들지 않는다.

---

# 39. Recommendation

Finding에 대한 개선 후보.

```text
Recommendation

id
findingId
description
reason
expectedEffect
risk
status
implementationReference
implementedTargetRevision
verificationGroupId
```

상태:

```text
PROPOSED
ACCEPTED
REJECTED
IMPLEMENTED
VERIFIED
```

---

# 40. Before / After 검증

사용자가 개선한 이후 동일 Experiment를 다시 실행할 수 있어야 한다.

```text
Experiment #100

Before

p95 = 820 ms
error = 3.2%

        ↓
사용자 코드 개선

        ↓

Experiment #101
baselineExperimentId = #100

After

p95 = 310 ms
error = 0.1%
```

Agent는 이를 비교한다.

```text
Latency 개선
정합성 회귀 없음
새로운 Error 발생 없음
```

단일 Before Run과 단일 After Run만으로 개선을 확정하지 않는다. `VerificationGroup`은 동일한 Experiment Definition, Parameter, fixture와 비교 가능한 RunManifest를 사용하는 반복 Run을 묶는다.

```text
Before Group: 최소 3회

사용자 개선 + code revision 기록

After Group: 최소 3회

median / dispersion / error rate / invariant violation 비교
```

Host 부하, Target revision 외 설정, 서비스 수 또는 데이터 fixture가 달라 비교할 수 없으면 `NOT_COMPARABLE`로 표시한다. 성능 개선과 정합성 회귀 여부는 미리 정의된 threshold로 Backend가 계산하며 Agent가 임의로 개선 확정을 만들지 않는다.

```text
VerificationGroup

id
findingId
recommendationId
experimentDefinitionVersion
normalizedParametersHash
fixtureVersion
beforeTargetRevision
afterTargetRevision
comparisonThresholds
minimumRunsPerSide
status
verdict
createdAt
completedAt
```

```text
VerificationGroupRun

verificationGroupId
experimentRunId
side = BEFORE | AFTER
repetitionIndex
comparabilityStatus
comparabilityReason
```

Before / After Verification은 V1 Core 이후 Phase 9 범위이며 V1 Core 완료 조건에 포함하지 않는다.

---

# 41. Agent / LLM 비교

프로젝트의 핵심 기능 중 하나다.

비교 축:

```text
Agent Architecture

SINGLE
MULTI
```

```text
LLM

GPT_OSS
QWEN
```

조합:

```text
SINGLE + GPT_OSS

SINGLE + QWEN

MULTI + GPT_OSS

MULTI + QWEN
```

위 네 조합은 모든 역할이 같은 모델을 사용하는 homogeneous baseline이다. Multi-Agent에서 역할별로 다른 모델을 사용할 수 있으며 이 경우 단일 모델명이 아니라 전체 역할 구성을 식별한다.

```text
MULTI(
  SUPERVISOR = GPT_OSS,
  PLANNER = GPT_OSS,
  ANALYST = GPT_OSS,
  REVIEWER = QWEN
)
```

---

# 42. AnalysisRun

모든 Agent 분석 실행을 저장한다.

```text
AnalysisRun

id

analysisDatasetId

agentArchitecture
agentVersion
analysisConfigurationHash
promptBundleVersion
toolSchemaVersion

startedAt
completedAt

durationMs

inputTokens
outputTokens

toolCallCount

result

status
```

각 Agent 역할 실행과 LLM 호출은 자식 Run으로 저장한다.

```text
AgentStepRun

id
analysisRunId
role
sequence
agentVersion
promptVersion
inputHash
outputHash
status
startedAt
completedAt
```

```text
LlmInvocation

id
analysisRunId
agentStepRunId
sequence
llmProvider
llmModel
llmModelDigest
quantization
llmRuntimeVersion
samplingParameters
randomSeed
inputHash
outputHash
inputTokens
outputTokens
queueWaitMs
modelLoadMs
inferenceMs
retryCount
repairCount
status
```

`AnalysisRun`의 token, Tool Call과 duration은 자식 실행의 합계다. 서로 다른 tokenizer의 token 수는 직접적인 비용 단위로 단정하지 않고 모델별 효율 지표로 함께 해석한다. 역할별 모델을 사용한 Multi-Agent도 전체 `analysisConfigurationHash`와 자식 Invocation으로 재현한다.

---

# 43. 비교 평가 항목

초기에는 다음을 측정한다.

```text
Root Cause Top-1 Accuracy

Root Cause Top-3 Accuracy

False Conclusion Rate

Useful Experiment Recommendation Rate

Unnecessary Experiment Rate

Tool Call Count

Input Tokens

Output Tokens

Analysis Duration

Reviewer Rejection Rate
```

평가는 단일 실행 결과로 결론 내리지 않는다. 각 고정 Dataset·구성 조합을 여러 번 실행하고 평균뿐 아니라 분산과 실패율을 저장한다.

초기 Evaluation Protocol:

```text
1. Dataset과 Ground Truth를 평가 전에 동결
2. Ground Truth 필드를 LLM 입력에서 제거했는지 checksum으로 확인
3. 모델 digest, quantization, runtime, sampling 설정 고정
4. 구성별 최소 3회 반복
5. 출력 순서를 섞어 Blind Human Evaluation 수행
6. 정확도, hallucination, 근거성, 추천 유용성을 rubric으로 채점
7. 품질과 비용·시간을 분리하여 보고
```

Single/Multi 비교는 두 관점을 모두 제공한다.

```text
동일 예산 비교:
동일한 최대 Tool Call, Token, Timeout 안에서 품질 비교

실사용 구성 비교:
각 구조의 기본 설정에서 품질·비용·시간을 함께 비교
```

---

# 44. Ground Truth

LLM 평가를 위해 일부 Experiment에는 알려진 Ground Truth를 정의할 수 있다.

예:

```text
scenario = DB_POOL_EXHAUSTION

expectedRootCause = DB_CONNECTION_POOL

acceptableCandidates = [
    DB_CONNECTION_POOL,
    DATABASE_CONNECTION_SATURATION
]
```

실제 장애 주입 실험은 Ground Truth 생성에 유용하다.

Ground Truth는 단일 문자열이 아니라 다음 정보를 가진다.

```text
rootCauseTaxonomyVersion
primaryCause
acceptableCandidates
requiredEvidence
contradictingEvidence
severity
labelSource = INJECTED | VERIFIED_BY_TEST | HUMAN_REVIEWED
reviewerIds
```

실제 원인이 모호한 Dataset은 Accuracy 분모에 억지로 포함하지 않고 정성 평가용으로 분리한다.

---

# 45. Blind Evaluation

모델 비교 시 LLM에게 정답을 알려주지 않는다.

동일 Evidence만 제공한다.

```text
Evidence
↓
Model A

Evidence
↓
Model B
```

평가기는 두 결과를 별도로 평가한다.

---

# 46. Prompt Version 관리

Prompt 변경은 결과에 영향을 준다.

따라서 반드시 기록한다.

```text
promptVersion = planner-v1
promptVersion = analyst-v2
promptVersion = reviewer-v1
```

모델 비교에서는 원칙적으로 동일 역할과 동일 Prompt Bundle을 사용한다. 다만 모델 Capability 차이 때문에 문법 Adapter가 필요한 경우 의미는 유지하고 Adapter 버전과 차이를 기록한다.

Multi-Agent는 Planner, Analyst, Reviewer의 개별 Prompt Version과 전체 조합인 `promptBundleVersion`을 함께 기록한다.

---

# 47. PostgreSQL 주요 테이블

초기 예상 모델:

```text
target_system

experiment_definition

campaign_definition

campaign_run

campaign_step_run

planned_run_spec

run_manifest

experiment_run

experiment_action

experiment_resource

experiment_evidence

evidence_artifact

evidence_snapshot

analysis_dataset

investigation

investigation_experiment_run

analysis_request

analysis_run

agent_step_run

llm_invocation

agent_tool_call

tool_replay_entry

finding

recommendation

evaluation_run

verification_group

verification_group_run

experiment_approval

risk_assessment

workload_lease
```

---

# 48. experiment_run

예상 필드:

```text
id

target_system_id

campaign_run_id

experiment_type

experiment_definition_version

parameters_json

planned_run_spec_id
pre_run_manifest_id
post_run_manifest_id

idempotency_key

run_status

system_outcome
invariant_result_json
outcome_reason
evaluated_definition_version

execution_failure_phase
execution_failure_owner
execution_failure_code
execution_failure_message

cleanup_status
cleanup_failure_code
cleanup_failure_message
cleanup_attempt

queued_at
started_at
completed_at

lease_owner
lease_expires_at
last_heartbeat_at

baseline_experiment_id
```

`run_status`는 Lab lifecycle의 성공 여부, `system_outcome`은 Target invariant 판정 결과를 나타낸다.

```text
system_outcome =
  NOT_EVALUATED
  | INVARIANTS_PASSED
  | VULNERABILITY_OBSERVED
  | INCONCLUSIVE
```

실행 오류 뒤 cleanup도 실패할 수 있으므로 실행 실패와 cleanup 실패 원인을 서로 다른 필드에 보존한다.

---

# 49. experiment_evidence

```text
id

experiment_run_id

evidence_type

schema_version

source
collector_version
observed_at
window_start
window_end
unit
aggregation_method
sample_count
completeness

payload_json

artifact_refs_json
checksum

created_at
```

---

# 50. analysis_run

```text
id

analysis_dataset_id

agent_architecture
agent_version
analysis_configuration_hash
prompt_bundle_version
tool_schema_version

input_tokens
output_tokens

tool_call_count

duration_ms
queue_wait_ms
model_load_ms
inference_ms
tool_execution_ms

result_json

status

created_at
```

`agent_step_run`과 `llm_invocation`은 42절의 역할별 실행·호출 필드를 저장한다. `analysis_run`의 token과 시간 필드는 집계값이며 모델별 세부 수치는 `llm_invocation`을 Source of Truth로 사용한다.

---

# 51. investigation

```text
id

target_system_id

title

status

initial_reason

created_at
completed_at
```

`campaign_run`은 하루 또는 사용자가 시작한 하나의 Experiment 묶음을 나타낸다.

`planned_run_spec`은 승인과 실행 전에 고정 가능한 기대값을 저장한다.

```text
targetSystemId
experimentDefinitionVersion
normalizedParameters
loadProfile
fixturePlan
expectedTargetRevision
expectedServiceCount
hostResourceGroup
riskAssessmentId
specHash
```

`run_manifest`는 실행 직전과 직후 실제로 관측한 환경을 저장한다.

```text
phase = PRE_RUN | POST_RUN
targetRevision
containerImageDigests
effectiveConfigHashes
serviceCount
fixtureVersion
Lab / Collector version
Host resource snapshot
runtime endpoints hash
observedAt
manifestHash
```

승인은 `planned_run_spec.specHash`에 묶는다. 실행 직전 Backend는 Planned Spec과 PRE_RUN Manifest의 차이를 정책으로 판정하고 허용되지 않은 drift가 있으면 실행하지 않는다. POST_RUN Manifest는 실행 중 환경 변화와 비교 가능성을 판단하는 데 사용한다.

---

# 52. Concurrency

Autonomous Reliability Lab 자체에서도 동시성 문제를 고려한다.

---

## 52.1 동일 Experiment 중복 시작

다음과 같은 상황을 막는다.

```text
Scheduler
    ↓
Experiment #100

User
    ↓
Experiment #100
```

중복 실행 방지 정책이 필요하다. API 요청에는 사용자가 재시도해도 동일한 Run을 반환하는 `idempotencyKey`를 사용한다.

동시에 실행하면 서로 오염시키는 Experiment는 `resourceScope`를 선언한다.

```text
targetSystemId
+
resourceScope = GLOBAL | PRODUCT:{id} | TOPIC:{name} | SERVICE:{name}
```

V1에서는 단순성과 격리를 위해 하나의 Target에서 `GLOBAL` Experiment 하나만 실행한다. 부분 Scope 병렬 실행은 V1 이후 기능이다.

부분 Scope를 도입할 때는 Experiment가 하나 이상의 Scope 집합을 prepare 이전에 확정하고 다음 규칙을 적용한다.

```text
GLOBAL은 같은 Target의 모든 Scope와 충돌
동일 PRODUCT / TOPIC / SERVICE ID는 충돌
서로 다른 ID는 Definition의 conflict matrix가 허용할 때만 병렬 실행
복수 Scope는 정렬된 canonical key 순서로 획득
필요한 Scope를 모두 원자적으로 획득하지 못하면 실행하지 않음
```

fixture 생성 뒤에야 ID를 알 수 있는 Experiment는 prepare 전 reservation key를 발급하거나 `GLOBAL` Scope를 사용한다.

초기에는 PostgreSQL 기반 lease, heartbeat와 활성 Run 제약을 우선한다. lease 만료 후에는 이전 작업자의 결과 저장을 막는 fencing token을 사용한다.

분산락은 실제 필요성이 확인된 경우 도입한다.

---

## 52.2 동일 Analysis 중복 실행

동일 조합:

```text
Analysis Dataset
+
Agent Architecture
+
Agent / Prompt / Tool Version
+
Analysis Configuration Hash
+
Evaluation Repetition Index
```

에 대해서 요청 재시도로 동일 분석이 불필요하게 중복 생성되지 않도록 한다. LLM은 비결정적일 수 있으므로 서로 다른 평가 반복 실행은 정상적인 별도 Run으로 허용한다.

예:

```text
UNIQUE(
  analysis_request_id,
  idempotency_key
)
```

실패 재시도는 `attempt`, 평가 반복은 `repetition_index`로 구분한다.

---

## 52.3 Experiment와 Local LLM 상호 배제

같은 Host resource group을 사용하는 Experiment와 Local LLM inference는 동시에 실행하지 않는다. 선언이나 Scheduler 순서만으로 보장하지 않고 PostgreSQL의 공용 `WorkloadLease`를 모든 실행 경로가 획득한다.

```text
WorkloadLease

hostResourceGroup
mode = EXPERIMENT_WINDOW | LOCAL_LLM_WINDOW
ownerType
ownerId
leaseOwner
leaseExpiresAt
fencingToken
lastHeartbeatAt
```

충돌 규칙:

```text
EXPERIMENT_WINDOW ↔ LOCAL_LLM_WINDOW = 충돌
EXPERIMENT_WINDOW ↔ EXPERIMENT_WINDOW = V1에서 충돌
LOCAL_LLM_WINDOW ↔ LOCAL_LLM_WINDOW = maxConcurrentInference 정책 적용
```

Campaign worker, 수동 Experiment API, Analysis worker와 재시도 worker가 모두 같은 gate를 통과해야 한다. lease가 만료되어도 Target이 안정화되었는지 확인하기 전에는 반대 모드 lease를 발급하지 않는다.

Process crash 후에는 Host와 Target health, 실행 중 Load Generator, 미확정 `ExperimentAction`을 확인한다. 불명확한 상태가 있으면 `RECOVERY_REQUIRED`로 두고 자동 전환하지 않는다.

---

# 53. LLM Queue 동시성

로컬 LLM 추론은 동시에 하나만 허용한다.

```text
maxConcurrentInference = 1
```

실행 순서는 애플리케이션 내부 worker가 관리할 수 있지만 대기 요청 자체는 PostgreSQL에 저장한다. worker는 lease와 heartbeat로 요청을 가져가며 프로세스 종료 후에도 다시 실행할 수 있어야 한다.

여러 인스턴스로 확장되는 시점에는 DB/Redis 기반 lease 등을 검토한다.

초기에는 분산 실행을 고려하지 않는다.

---

# 54. 실패 처리

## 54.1 LLM 실패

```text
Timeout

Invalid Structured Output

Ollama unavailable

Model load failure
```

정책:

```text
재시도 제한

AnalysisRun FAILED 저장

Experiment 결과는 보존

다음 실행에서 재분석 가능
```

---

## 54.2 Experiment 실패

Experiment 실패 자체도 Evidence다.

예:

```text
OUTBOX_BACKLOG

50,000 events 생성 중

order-service crash
```

이 경우 무조건 테스트 실패로 폐기하지 않는다.

```text
ExperimentExecutionFailure Evidence
```

로 저장한다.

Report에서는 다음을 분리한다.

```text
runStatus
= Lab이 Experiment lifecycle을 정상 수행했는가

systemOutcome
= Target이 Invariant와 기대 복구 조건을 만족했는가
```

예상된 Target crash가 관측되어 Evidence 수집과 cleanup까지 정상 완료되면 `runStatus = COMPLETED`, `systemOutcome = VULNERABILITY_OBSERVED`가 될 수 있다.

---

## 54.3 Evidence 수집 실패

가능한 Evidence만 저장하고 누락 상태를 명시한다.

Agent가 누락 데이터를 존재하는 것처럼 추론하지 못하도록 한다.

예:

```json
{
  "redisMetrics": {
    "status": "UNAVAILABLE"
  }
}
```

---

# 55. Scheduler

초기에는 간단한 Spring Scheduler로 시작한다.

Scheduler는 직접 개별 Experiment를 나열해서 실행하지 않고 `CampaignDefinition`을 시작한다.

```text
CampaignDefinition

id
version
timezone
experimentSteps
stepDependencies
cooldownPolicy
dailyRequestBudget
dailyDurationBudget
failureBudget
abortPolicy
resumePolicy
reportingWindow
```

각 Campaign Definition의 논리 Step은 실행 시점에 `CampaignStepRun`으로 영속화한다.

```text
CampaignStepRun

id
campaignRunId
stepKey
stepDefinitionVersion
sequence
logicalAttempt
status
experimentRunId
dependencyResult
cooldownUntil
leaseOwner
leaseExpiresAt
fencingToken
queuedAt
startedAt
completedAt
failureCode
failureMessage
```

상태:

```text
PENDING
→ BLOCKED_BY_DEPENDENCY | READY
→ CLAIMED
→ RUNNING
→ COOLING_DOWN
→ COMPLETED

실패·중단:
SKIPPED | FAILED | CANCELED | RECOVERY_REQUIRED
```

Campaign 생성 트랜잭션에서 Definition version과 Step 목록을 함께 고정한다. Step claim과 `ExperimentRun` 생성은 같은 DB 트랜잭션에서 수행한다. Claim은 `campaign_run` row lock과 `RUNNING` Step 부재 조건을 함께 사용하므로 하나의 Campaign에서는 한 Step만 `RUNNING`일 수 있다. Step lease/fencing token은 완료 기록과 takeover에도 비교되어 만료된 이전 worker가 이후 Step을 완료하거나 다음 Step을 열지 못하게 한다. 다음 고유 제약으로 논리 Step 중복 생성을 막는다.

```text
UNIQUE(
  campaign_run_id,
  step_key,
  logical_attempt
)
```

의존성 판정, failure budget 차감, cooldown 종료와 resume 위치는 메모리가 아니라 `CampaignStepRun`을 기준으로 계산한다.

예:

```text
Campaign 시작

09:00

↓
사전 Health / 환경 Identity / Capability 확인

↓

Experiment Step 실행

↓

Cleanup / 안정화 / cooldown 확인

↓

다음 Step 또는 중단 정책 적용

↓
Evidence 저장

↓
분석

↓
Daily Report
```

24시간 서버 실행이 필수 조건은 아니지만, 사용자가 정의한 하루 실행 구간과 Report 경계는 명시한다.

프로그램이 다시 시작될 경우 PostgreSQL의 Campaign Step, Run lease와 cleanup 상태를 기준으로 이어서 진행할 수 있어야 한다. 이전 Step의 cleanup과 Target health가 확인되지 않으면 다음 Experiment를 자동 시작하지 않는다.

---

# 56. 자동 Experiment 선택 정책

초기에는 완전 자유 Agent 방식보다 Hybrid 방식으로 시작한다.

```text
Experiment Catalog
+
최근 결과
+
Agent 추천
```

Agent가 Catalog 밖의 Experiment를 생성할 수 없다.

---

# 57. Experiment Intensity

각 Experiment에는 단계별 강도를 정의할 수 있다.

Intensity Level은 request 수 하나의 별칭이 아니라 versioned Load Profile preset이다. Level마다 workload model, RPS 또는 concurrency, ramp, duration, timeout과 retry 정책을 함께 고정한다.

예:

```text
STOCK_CONCURRENCY

LEVEL_1
100 requests

LEVEL_2
500

LEVEL_3
1000

LEVEL_4
3000

LEVEL_5
5000
```

Agent는 이전 결과를 보고 다음 Level을 선택할 수 있다.

---

# 58. Failure Boundary 탐색

Agent의 목표는 실패를 억지로 만드는 것이 아니다.

목표:

> 시스템의 가정을 깨뜨릴 가능성이 높은 조건을 탐색하여 안전 경계와 실패 경계를 확인한다.

예:

```text
Concurrency

100
✓

500
✓

1000
✓

3000
p95 급증

5000
timeout 증가
```

결과:

```text
정합성 경계:
5000에서도 정상

성능 경계:
3000 이후 급격한 latency 증가
```

---

# 59. Daily Report

하루 실행 결과를 종합한다.

예:

```markdown
# Reliability Report

## Summary

실행 Experiment: 38
Lab 정상 완료: 36
Lab 실행 실패: 2

Target Invariant 통과: 31
Target 취약성 관측: 5

신규 Finding: 3

## Critical Finding

OUTBOX_BACKLOG

50K backlog에서
p95 publish latency가 1.2s → 7.8s로 증가.

Kafka lag는 정상이며 DB CPU도 40% 미만.

Outbox publisher throughput 한계 가능성.

Confidence: MEDIUM

## Recommended Next Experiment

Publisher concurrency 변경 후
동일 backlog 조건 재실행.
```

---

# 60. 대시보드

초기 UI는 필수가 아니다.

API 또는 Markdown Report부터 시작한다.

향후 Dashboard:

```text
Experiment History

Finding

Before / After

Model Comparison

Agent Comparison

Latency Trend

Failure Boundary
```

---

# 61. Eventful Commerce 연동 방식

초기에는 기존 공개 API 및 별도 테스트용 접근 경로를 이용한다.

가능하면 Eventful Commerce의 Production 코드에 Reliability Lab 전용 로직을 추가하지 않는다.

필요한 경우 별도의 `test profile` 또는 제한된 Test API만 고려한다.

---

# 62. Target System 변경 최소화

기본 원칙:

```text
Reliability Lab Core는 Target System의 비즈니스 로직을 알 필요가 없다.

Target은 표준 HTTP Scenario endpoint 또는 외부 Target Package를 통해 제한된 테스트 계약만 제공할 수 있다.
```

Eventful Commerce의 핵심 비즈니스 로직을 Reliability Lab 때문에 변경하지 않는다. 단, 격리된 Test Harness 또는 기존 검증 Script가 표준 계약을 제공하는 것은 허용한다.

---

# 63. 기존 Test Script 활용

초기에는 Target Package가 Target 저장소의 기존 자동 테스트 Script를 Adapter 구현으로 활용할 수 있다.

예:

```text
Experiment

↓ Target Package Adapter

등록된 Scenario ID

↓

scripts/test-02-...
scripts/test-04-...
scripts/verify.sh
```

ARL Core는 Script 경로·Shell 명령을 알거나 생성하지 않는다. Target Package가 허용된 Scenario ID를 고정된 Script/HTTP 호출로 매핑하고, 구조화된 결과와 cleanup 검증을 반환한다. 표준 HTTP Scenario 계약을 제공하는 Target은 Plugin 없이 공통 Adapter만 사용한다.

---

# 64. Observability

초기 측정 대상:

```text
HTTP latency

HTTP error rate

Kafka consumer lag

Kafka processing count

DLT count

Outbox pending count

Outbox publish latency

Redis latency

Redis stock

DB stock

DB connection pool

DB query latency

Container health

Service health
```

실제 수집 가능한 범위부터 시작한다.

모든 요청·테스트 이벤트·실험 데이터에는 가능한 범위에서 `experimentRunId` 또는 Test namespace를 전달하여 Evidence를 다른 트래픽과 구분한다. 수집 시각은 UTC 기준으로 저장하고 각 Source의 clock skew를 확인한다.

Prometheus 도입은 실제 Metric 수집 필요성이 확인된 시점에 진행한다. 단, DB CPU, connection pool, Kafka lag처럼 현재 수집 경로가 없는 Metric을 요구하는 Experiment는 해당 Collector가 준비되기 전까지 Catalog에서 `NOT_EXECUTABLE`로 표시한다. 누락 Metric을 추정값으로 대체하지 않는다.

---

# 65. 비용 정책

초기 모든 LLM은 Ollama Local Model을 사용한다.

```text
GPT-OSS
Qwen3
```

외부 API 비용을 발생시키지 않는 것을 기본으로 한다.

향후 Claude/OpenAI 비교는 선택적으로 추가한다.

---

# 66. LLM 호출 최적화

모든 테스트마다 LLM을 호출하지 않는다.

```text
Experiment

↓
Backend Rule Evaluation

정상이고 기존 범위 내
→ 저장만

이상 발생
→ Agent 호출
```

또는 Experiment Set 종료 후 한 번에 분석한다.

---

# 67. Deterministic Anomaly Detection

LLM 호출 전 단순 이상 여부는 Backend가 판단한다.

예:

```text
p95 > baseline * 1.5

errorRate > threshold

consumerLag > threshold

settlingCondition 충족 또는 timeout 이후
CONSISTENCY_INVARIANT_VIOLATED

businessExecutionCount > expected

DLT count 증가
```

이 작업을 LLM에게 맡기지 않는다.

수렴 대기 중 Redis/DB 값의 일시적 불일치는 원시 Evidence로 저장하되 anomaly로 판정하지 않는다. Experiment Definition의 settling policy가 종료된 뒤 계산한 invariant verdict만 Agent 호출 조건으로 사용한다.

---

# 68. Agent 호출 기준

Agent가 필요한 경우:

```text
여러 지표의 관계를 분석해야 함

원인 후보를 판단해야 함

다음 실험을 설계해야 함

과거 Experiment와 비교해야 함

충분한 Evidence인지 판단해야 함
```

---

# 69. Security / Safety

다음 환경에서만 장애 Experiment 실행을 허용한다.

```text
LOCAL
TEST
```

Production hostname 또는 profile에서는 destructive Experiment 실행을 차단한다.

profile과 hostname만 신뢰하지 않는다. Experiment 실행 전 다음 검증을 모두 통과해야 한다.

```text
Target base URL이 설정된 allowlist에 포함

Target identity endpoint의 environmentId가 LOCAL 또는 TEST

Target instanceId와 예상 deployment revision 일치

테스트 전용 최소 권한 Credential 사용

Test data namespace 또는 전용 tenant 확인

운영망으로의 network route 차단 확인

전역 kill switch 비활성 상태 확인
```

Target base URL은 Agent 입력으로 받지 않으며 관리자가 등록한 Target ID로만 선택한다. Test API에는 인증·권한·rate limit을 적용한다.

HTTP 외 모든 Adapter 대상도 `TargetRegistration`에 등록된 값만 사용한다.

```text
TargetRegistration

targetSystemId
environmentId
allowedHttpOrigins
allowedResolvedNetworks
kafkaBootstrapServers
allowedKafkaTopics
redisEndpoints
databaseReadEndpoints
metricsEndpoints
allowedContainerIds
allowedServiceIds
allowedFailureOperations
credentialReferences
registrationVersion
```

보안 규칙:

```text
HTTP redirect는 기본 금지하고 허용 시 최종 origin을 다시 검증
DNS resolve 결과가 등록된 network 범위인지 연결 직전 확인
Kafka / Redis / DB / Metrics endpoint를 Agent parameter로 받지 않음
DB·Redis 관측 계정은 read-only 최소 권한 사용
Kafka test topic과 publish 가능한 event type allowlist 적용
Failure Controller는 등록된 container/service와 명령 조합만 실행
Credential은 값이 아닌 secret reference로 보관
```

환경 Identity 검증은 HTTP Target뿐 아니라 Failure Controller가 가리키는 Docker/Compose project와 Kafka·Redis·DB resource identity에도 적용한다.

## 69.1 Evidence와 Prompt Injection 방어

Target Log, 오류 메시지, 이벤트 payload, DB 문자열과 외부 Metric label은 신뢰할 수 없는 데이터다. 그 안의 문장을 Agent 지시로 해석하지 않는다.

```text
Evidence는 명시적인 JSON schema와 data delimiter 안에 배치
System / Developer instruction과 Evidence channel을 분리
허용 필드, 문자열 길이, 배열 크기와 전체 token budget 제한
제어 문자, 비정상 중첩, 실행 지시처럼 보이는 문자열을 정규화·표시
민감정보와 Credential 패턴 제거
원문 Artifact는 직접 Prompt에 전체 삽입하지 않음
```

Prompt에는 Evidence가 untrusted data이며 내부의 명령을 따르지 말아야 함을 명시한다. 그러나 Prompt 규칙만 보안 경계로 신뢰하지 않는다. Tool 이름·arguments·권한·예산·Target은 모두 Backend가 독립적으로 검증한다.

Evaluation에서는 34.1절에 따라 side-effect Tool을 차단한다. Live Investigation에서도 Agent 출력은 Proposal 또는 DRAFT이며 Backend 정책을 우회할 수 없다.

---

# 70. 위험 Experiment 승인

초기에는 위험도를 정의한다.

```text
SAFE

MODERATE

DESTRUCTIVE
```

예:

```text
STOCK_CONCURRENCY
baseRisk = SAFE

CONSUMER_RESTART
MODERATE

REDIS_FAILURE
baseRisk = DESTRUCTIVE
```

실행 전 `RiskAssessment`가 다음을 이용해 `effectiveRisk`를 계산한다. 계산 결과는 baseRisk보다 낮출 수 없으며 조건에 따라 상향한다.

```text
experimentType
normalizedParameters
concurrency / RPS / duration
Target별 승인 threshold와 현재 capacity
데이터 namespace와 삭제·변경 범위
Failure Control 범위
현재 Target health
동시 Campaign과 Host resource 상태
```

```text
RiskAssessment

id
plannedRunSpecId
policyVersion
baseRisk
effectiveRisk
matchedRules
capacitySnapshot
decision
createdAt
```

예를 들어 `STOCK_CONCURRENCY`라도 Target별 자동 실행 threshold를 초과하거나 공유 fixture를 사용하면 `MODERATE` 또는 `DESTRUCTIVE`로 승격한다.

`DESTRUCTIVE`는 기본 비활성화한다.

자동 실행 정책:

```text
SAFE
→ Campaign 예산과 사전 조건 안에서 자동 실행 가능

MODERATE
→ Target별 정책에 따라 사전 승인 또는 매 실행 승인

DESTRUCTIVE
→ 구체적인 Target, Experiment, Parameters, 실행 기한에 대한 매 실행 승인 필수
```

---

# 71. Human Approval

초기에는 간단한 상태 기반 승인 방식을 구현할 수 있다.

```text
Agent Proposal
↓
WAITING_APPROVAL
↓
사용자 승인
↓
RUNNING
```

LangGraph 없이 PostgreSQL 상태로 구현한다.

승인은 승인 당시의 `plannedRunSpecHash`, `effectiveRisk`, 만료 시각에만 유효하다. 파라미터, Target, Load Profile 또는 위험도 평가가 바뀌면 다시 승인받는다. 승인 후에도 Planned Spec과 PRE_RUN Manifest drift, health, identity, budget 또는 kill switch 검증이 실패하면 실행하지 않는다.

---

# 72. LangGraph 도입 기준

초기에는 사용하지 않는다.

다음 문제가 실제로 발생하면 검토한다.

```text
Agent workflow 분기가 크게 증가

여러 단계의 중단/재개 필요

Agent sub-workflow가 복잡해짐

Human-in-the-loop 관리가 어려워짐

Checkpoint 직접 구현이 복잡해짐

Agent 실행 상태 관리 코드가 과도하게 증가
```

도입 이유는 반드시 현재 자체 Orchestration의 한계로 설명할 수 있어야 한다.

---

# 73. 코드 구조 예시

```text
src/main/kotlin/.../

├─ investigation/
│  ├─ domain/
│  ├─ application/
│  └─ infrastructure/
│
├─ experiment/
│  ├─ domain/
│  ├─ application/
│  ├─ stock/
│  ├─ kafka/
│  ├─ redis/
│  ├─ outbox/
│  └─ saga/
│
├─ target/
│  ├─ domain/
│  └─ eventfulcommerce/
│
├─ evidence/
│  ├─ domain/
│  └─ application/
│
├─ agent/
│  ├─ domain/
│  ├─ single/
│  ├─ multi/
│  └─ tools/
│
├─ llm/
│  ├─ domain/
│  ├─ ollama/
│  └─ scheduler/
│
├─ evaluation/
│
├─ finding/
│
└─ scheduler/
```

패키지는 실제 구현 과정에서 책임이 명확한 단위까지만 분리한다.

---

# 74. 초기 기술 스택

```text
Language
Kotlin

Framework
Spring Boot

AI Integration
Spring AI

LLM Runtime
Ollama

LLM
GPT-OSS 20B
Qwen3

Database
PostgreSQL

HTTP
Spring WebClient 또는 RestClient

Testing
JUnit
Testcontainers

Container
Docker / Docker Compose
```

Redis와 Kafka는 Reliability Lab 자체에 처음부터 필요하지 않으면 추가하지 않는다.

Target System의 Redis/Kafka를 테스트한다고 해서 Reliability Lab 내부에도 Redis/Kafka를 반드시 사용해야 하는 것은 아니다.

---

# 75. 구현 Phase

## Phase 0 — Project Foundation

목표:

프로젝트 실행 기반 구성.

구현:

```text
Spring Boot
PostgreSQL
Docker Compose
기본 모듈
TargetSystem
Experiment
Evidence
Campaign
CampaignStepRun
PlannedRunSpec
RunManifest
WorkloadLease
```

완료 조건:

```text
애플리케이션 실행 가능

등록된 HTTP Target health 확인 가능

등록된 Target Capability Inventory 확인

Target environment identity 검증 가능

DB Migration 정상
```

---

# 76. Phase 1 — Deterministic Experiment Engine

AI 없이 먼저 Experiment Engine을 완성한다.

초기 Experiment:

```text
STOCK_CONCURRENCY
```

`STOCK_CONCURRENCY`가 RunManifest, 판정 계약, cleanup, 재개와 반복 검증을 모두 만족한 뒤 다음 순서로 Catalog를 확장한다. ARL Core는 `HTTP_SCENARIO_V1` 공통 Adapter와 `ExperimentTargetAdapter` Plugin SPI만 제공하며, Eventful Commerce 같은 Target별 실행 지식은 Target Package에 둔다.

```text
ORDER_CANCEL_RACE
KAFKA_DUPLICATE_EVENT
OUTBOX_BACKLOG
```

완료 조건:

```text
사용자가 API로 Experiment 실행

Evidence 자동 수집

결과 DB 저장

동일 Experiment 재현 가능

Campaign 중단 후 영속된 Step과 Action journal을 기준으로 재개 가능

외부 작업 결과가 불명확하면 자동 재실행하지 않고 RECOVERY_REQUIRED 처리

cleanup 실패 시 다음 Experiment 자동 차단

ExperimentAction과 ExperimentResource ledger 저장

runStatus와 systemOutcome 분리 저장

Target Profile의 adapterId·Scenario endpoint allowlist·DNS network allowlist 검증

등록되지 않은 Target Package 또는 Scenario는 실행 거절

LOCAL/TEST Target만 실행하고 STAGING/PRODUCTION은 정책 구현 전 차단
```

---

# 77. Phase 2 — Single Reliability Agent

Phase 2는 하나의 모델로 Evidence를 해석하는 read-only 단일 Agent를 추가한다. 기본 구현은 Spring AI 2.0과 Ollama의 `gpt-oss` 모델이며, 실제 호출은 `ReliabilityAnalysisModel` port 뒤에 둔다. 따라서 테스트는 fake model로 실행하고, Phase 3에서 Qwen을 같은 port의 선택지로 추가할 수 있다.

```text
COMPLETED Experiment + VERIFIED cleanup
        ↓
immutable bounded Evidence bundle
        ↓
SingleReliabilityAgent (no target/shell/db/http tools)
        ↓
AnalysisRun
 ├─ Finding (severity, rationale, evidenceIds)
 └─ Recommendation (priority, action, rationale, evidenceIds)
```

`POST /api/experiments/{experimentRunId}/analyses`는 `Idempotency-Key`를 요구하고 `202 Accepted`를 반환한다. `GET /api/analysis-runs/{analysisRunId}`로 상태와 구조화된 결과를 조회한다. 같은 Experiment와 key 조합은 하나의 AnalysisRun만 만든다.

Agent에게 전달하는 입력은 생성 시점의 Evidence id·type·checksum·payload와 Experiment의 deterministic outcome을 정렬한 JSON이다. 최대 50개 Evidence와 128 KiB 입력으로 제한하고 checksum을 AnalysisRun에 저장한다. Evidence의 모든 문자열은 비신뢰 데이터이며, system instruction은 Evidence 안의 지시를 따르지 않도록 명시한다. Phase 2는 Tool Calling을 등록하지 않으므로 모델이 Target, Shell, DB, HTTP side effect를 실행할 수 없다.

모델은 다음 JSON만 반환해야 한다.

```json
{
  "summary": "evidence-grounded conclusion",
  "findings": [
    {"severity": "INFO|LOW|MEDIUM|HIGH|CRITICAL", "title": "...", "rationale": "...", "evidenceIds": ["evidence-id"]}
  ],
  "recommendations": [
    {"priority": "P0|P1|P2|P3", "title": "...", "recommendedAction": "...", "rationale": "...", "evidenceIds": ["evidence-id"]}
  ]
}
```

각 Finding·Recommendation은 입력 묶음에 있는 Evidence ID를 하나 이상 인용해야 한다. 미지의 ID, 중복 reference, JSON 형식 오류, 출력 크기 초과는 `FAILED/MODEL_OUTPUT_INVALID`로 저장한다. 모델 연결 실패는 `FAILED/MODEL_UNAVAILABLE`로 끝나며 Experiment 상태나 Target에는 영향을 주지 않는다.

---

# 78. Phase 3 — Qwen Model

Phase 3은 Qwen을 별도 Agent나 Target Adapter로 만들지 않는다. 동일한 `ReliabilityAnalysisModel` port를 사용하고, 서버 설정에 등록된 모델 key만 선택 가능하게 한다.

```text
GPT_OSS -> gpt-oss:20b
QWEN    -> qwen3:4b
```

`POST /api/experiments/{runId}/analyses` body의 선택 값은 `modelKey`다. 호출자가 Ollama model 이름이나 URL을 직접 보낼 수 없으며, 등록되지 않은 key는 거부한다. 저장되는 `AnalysisRun`에는 model key와 실제 model id를 모두 남긴다.

모델 작업을 queue에 넣기 전에 completed + cleanup verified Experiment의 bounded Evidence JSON을 `AnalysisDataset`으로 영속화한다. Dataset에는 contract version, Evidence ID 목록, checksum, count가 들어가며 이후 live Evidence를 다시 읽지 않는다. 모델에는 이 Dataset JSON만 전달한다.

모델 출력 계약은 다음처럼 verdict를 포함한 read-only JSON이다.

```json
{
  "summary": "evidence-grounded conclusion",
  "verdict": "PASSED|FAILED|INCONCLUSIVE",
  "findings": [],
  "recommendations": []
}
```

Target·Shell·DB·HTTP side-effect tool은 어느 모델에도 등록하지 않는다. Qwen은 GPT-OSS와 같은 Evidence/Prompt/output validation 경계를 공유한다.

---

# 79. Phase 4 — LLM Evaluation

Phase 4의 비교 요청은 하나의 새 `AnalysisDataset`을 먼저 만들고, 선택된 2~4개 등록 모델의 `AnalysisRun` 모두가 그 Dataset ID를 참조하게 한다. 따라서 GPT-OSS와 Qwen의 차이는 동일 checksum·동일 Evidence count·동일 Prompt version에서 비교한다.

```text
POST /api/experiments/{runId}/analysis-comparisons
  -> AnalysisComparison
       -> AnalysisDataset (immutable snapshot)
       -> GPT_OSS AnalysisRun
       -> QWEN AnalysisRun
```

각 AnalysisRun에는 model key/id, prompt version, input checksum/count, verdict, prompt token count, completion token count, duration millis, structured finding/recommendation을 저장한다.

사람 또는 injection test는 Dataset에 versioned Ground Truth를 추가할 수 있다. Ground Truth는 expected verdict와 Dataset 안에 있는 required Evidence ID만 가진다. 다른 Dataset의 Evidence ID는 허용하지 않는다.

평가는 완료된 AnalysisRun과 같은 Dataset의 Ground Truth에서만 실행한다.

```text
verdictMatch      = analysis verdict == expected verdict
citationRecall    = cited required Evidence / required Evidence
score             = (verdictMatch ? 0.7 : 0.0) + 0.3 * citationRecall
```

평가 결과는 versioned `AnalysisEvaluation`으로 저장되며, 같은 AnalysisRun·Ground Truth·평가 버전 조합은 idempotent하다. 비교 평가 요청은 모든 비교 AnalysisRun이 완료된 뒤에만 실행한다.

---

# 79.5. Phase 4.5 — 범용 Target Spec과 안전한 HTTP Batch

새 Target은 ARL Core adapter나 Target 전용 테스트 endpoint 없이 `Target Spec`으로 등록한다. Phase 4.5의 Spec은 Target ID, 실행 허용 여부, 공용 resource group, request timeout, 읽기 전용 HTTP operation과 기대 status code를 가진다.

```text
Target Spec
↓
Health + 선언된 GET operation 후보 생성
↓
여러 후보 선택
↓
PENDING_APPROVAL Batch
↓
명시 승인
↓
순차 HTTP 실행
↓
status · latency · response hash Evidence 저장
```

안전 경계:

- `LOCAL`·`TEST` Target과 `HTTP_API` capability에서만 실행한다.
- Phase 4.5의 실행 가능한 operation은 `GET`뿐이다. POST/PUT/PATCH/DELETE, 인증 secret, fixture 생성·정리, OpenAPI 원격 수집은 범위 밖이다.
- Batch 승인 시점과 worker 실행 직전에 execution-enabled를 다시 확인한다. 설정을 비활성화한 뒤 재시작되거나 보류 Batch가 재개되어도 Target 호출은 시작하지 않는다.
- Batch는 Target Spec의 host-resource-group에 공용 workload lease를 획득한 뒤에만 HTTP 요청을 보낸다. 같은 Target resource group을 쓰는 Experiment와 다른 Batch는 병렬로 실행되지 않는다.
- 모든 item이 확정된 완료 Batch는 기존 AnalysisDataset contract로 변환한다. 분석 Evidence에는 HTTP status, latency, body byte count, SHA-256만 포함하고 응답 본문은 포함하지 않는다. 단일 모델 분석과 선택 모델 비교는 각각 `/api/test-batches/{batchId}/analyses`, `/api/test-batches/{batchId}/analysis-comparisons`에서 시작한다.
- Target Spec은 `executionEnabled=false`가 기본이며, Batch 생성과 실행 승인 전에는 Target에 HTTP 요청을 보내지 않는다.
- 각 Batch는 단일 worker가 순차 실행한다. CIDR allowlist·고정 DNS 연결·allowed origin·전체 HTTP deadline·응답 1 MiB 제한을 기존 HTTP transport와 동일하게 적용한다.
- response body는 저장하거나 분석 모델에 전달하지 않는다. status, latency, byte count, SHA-256만 Evidence로 저장한다.
- HTTP 결과를 확정할 수 없거나 ARL이 실행 중 재시작하면 `RECOVERY_REQUIRED`로 끝내고 남은 항목을 실행하지 않는다.

# 80. Phase 5 — Multi-Agent

구현 결정:

- `SUPERVISOR → PLANNER → ANALYST → REVIEWER`는 하나의 immutable AnalysisDataset만 순차적으로 읽는다.
- 모든 역할은 Target, HTTP, database, shell, file, side-effect tool을 갖지 않으며 `NO_TOOLS` 정책을 기록한다.
- Reviewer만 기존 final analysis JSON contract를 출력하고 결과는 AnalysisRun·finding·recommendation에 저장한다.
- 단일 modelKey는 homogeneous 실행이고, mixed-model 실행은 네 role의 modelKey를 모두 명시한다.
- AgentStepRun/LlmInvocation에는 model key/id, prompt version, input/output checksum, token count, duration, failure가 저장된다. ARL 재시작 중 RUNNING multi analysis는 자동 재실행하지 않고 FAILED로 남긴다.
- `arl.agent.enabled=false`는 모든 LLM 분석 구조의 공통 비활성화 스위치다. `arl.multi-agent.enabled`는 이 공통 스위치가 true일 때 Multi-Agent 구조만 별도로 비활성화한다.

추가:

```text
Supervisor
Planner
Analyst
Reviewer

AgentStepRun
LlmInvocation
```

동일 `ReliabilityAgent` 인터페이스 구현.

역할별 Model, Prompt, Tool Call, Token과 duration을 자식 Run으로 저장하며 homogeneous와 mixed-model 구성을 모두 재현할 수 있어야 한다.

---

# 81. Phase 6 — Single vs Multi Evaluation

사용자가 선택한 2~4개 조합만 동일 immutable `AnalysisDataset`에서 비교한다. 네 조합 전체를 자동 실행하지 않는다.

```json
{
  "configurations": [
    { "architecture": "SINGLE", "modelKey": "GPT_OSS" },
    { "architecture": "MULTI", "modelKey": "GPT_OSS" }
  ]
}
```

- 각 선택은 homogeneous `SINGLE|MULTI + modelKey` 하나다. 역할별 mixed-model Multi 실행은 Phase 5의 단독 분석 API로 남기고, Phase 6 비교의 기본 단위에는 넣지 않는다.
- 같은 comparison 안의 모든 선택은 checksum·Evidence count가 같은 한 Dataset을 읽는다.
- 응답과 저장소는 선택 key, architecture, model key, AnalysisRun, token, duration을 함께 기록한다. Multi AnalysisRun의 token·duration은 네 role invocation의 합계이며, 어떤 role이 해당 metric을 제공하지 않으면 집계값도 null이다.
- 응답의 기존 `modelKeys`는 사용한 고유 모델 목록이고, 실제 선택 조합은 `selectedConfigurations`가 기준이다.
- 기존 `modelKeys: ["GPT_OSS", "QWEN"]` 요청은 호환성을 위해 `SINGLE + GPT_OSS`, `SINGLE + QWEN` 선택으로 해석한다.
- Idempotency-Key를 재사용하면서 architecture/model 선택을 바꾸면 409 conflict로 거절한다.

비교 후보:

```text
SINGLE + GPT-OSS

SINGLE + QWEN

MULTI + GPT-OSS

MULTI + QWEN
```

동일 AnalysisDataset과 Tool Replay 사용.

---

# 82. Phase 7 — Follow-up Test Suggestions

Phase 7의 초기 구현은 자동 Investigation Loop가 아니라, 완료된 Target Test Batch 분석을 바탕으로 한 **후속 안전 테스트 후보 제안**이다. Agent는 Target Spec에 이미 등록된 read-only candidate 중 0~5개만 제안하며, Evidence ID와 이유를 함께 저장한다.

```text
Evidence
↓
Analysis
↓
Follow-up candidate suggestion (stored)
↓
사용자가 후보를 선택
↓
기존 Test Batch 생성 및 별도 승인
```

- Phase 7 Agent에는 Target HTTP, Batch 생성·승인·실행, shell, database write, file, 외부 link tool이 없다.
- 모델 출력은 현재 Target Spec의 새 endpoint를 만들 수 없고, input snapshot 안의 candidate ID만 선택할 수 있다.
- 제안 Run은 별도 idempotency·입력 checksum·model/prompt/token/duration·실패 상태를 저장한다. 재시작 중 RUNNING Run은 재실행하지 않고 FAILED로 남긴다.
- COMPLETED 상태 전환과 모든 suggestion row 저장은 하나의 DB transaction으로 확정한다. 중간 오류는 COMPLETED 상태로 남지 않는다.
- candidate catalog와 전체 input snapshot은 각각 개수·바이트 상한을 적용한다. 상한을 넘는 Profile은 임의로 자르지 않고 제안 요청을 거절한다.
- 자동 승인·자동 실행·자동 반복은 구현하지 않는다. 따라서 `maxInvestigationIterations`는 향후 사용자 승인 기반 반복 기능이 생길 때만 적용한다.

---

# 83. Phase 8 — Failure Injection

Phase 8 초기 구현은 실제 장애 실행이 아닌 **명시적 승인형 Failure Injection Plan**이다. Target Spec이 LOCAL/TEST Target에 대해 미리 등록한 candidate의 위험·복구 기대치를 snapshot으로 저장하고, 사람의 승인을 기록한다.

```text
CONSUMER_RESTART

REDIS_FAILURE

SERVICE_RESTART

SHIPPING_SAGA_FAILURE
```

- Profile은 failure-injection planning을 명시적으로 켜야 하며, candidate에는 type·risk·title·설명·recovery expectation만 있다. 실행 command, endpoint, credential, Docker/container 이름은 저장하거나 받지 않는다.
- Plan은 1~5개 등록 candidate만 선택할 수 있고 idempotency request hash와 함께 `PENDING_APPROVAL`로 저장된다.
- `APPROVE_FAILURE_INJECTION_PLAN_ONLY` 확인 뒤에도 Plan은 `APPROVED` 기록일 뿐이다. Phase 8에는 실행 API, worker, Target HTTP client, shell, Docker, credential이 없고 응답의 `executionAvailable`은 항상 false다.
- STAGING/PRODUCTION Target에는 Plan 조회·생성·승인을 허용하지 않는다.

---

# 84. Phase 9 — 원인 가설 및 개선안 제시

Phase 9는 Experiment, Evidence, Analysis Finding을 근거로 원인 가설과 개선안을 제시하는 읽기 전용 단계다.

```text
Finding + Evidence
↓
원인 가설
  - 근거 Evidence ID
  - 신뢰도와 반증 가능성
↓
개선안
  - 해결하려는 가설
  - 기대 효과와 위험
```

ARL은 Target의 코드·설정·인프라를 수정하지 않으며, PR 생성, 배포, 개선안 선택·승인 관리, 개선 전후 Verification Experiment도 수행하지 않는다. 개선안의 채택과 구현은 전적으로 사용자의 역할이다.

---

# Phase 9 implementation contract

```text
POST /api/analysis-runs/{analysisRunId}/root-cause-reports
Idempotency-Key: {client-generated-key}
Body: { "modelKey": "GPT_OSS" }  // optional; uses the registered default when omitted

GET /api/root-cause-reports/{reportId}
```

- Source `AnalysisRun` must be `COMPLETED`; Phase 9 snapshots that run's Finding/Recommendation records and its immutable `AnalysisDataset` Evidence bundle before invoking the model.
- Output is strict JSON with `hypotheses` and `improvementProposals` only. Every item must cite one or more IDs from the snapshotted Dataset. A hypothesis includes `LOW|MEDIUM|HIGH` confidence and a falsifiability condition. A proposal references a one-based returned hypothesis ordinal and carries proposed change, expected effect, and risk.
- `root_cause_report_run`, `root_cause_hypothesis`, and `improvement_proposal` preserve the input/output checksum, model/prompt, metrics, errors, and evidence citations. An incomplete request can recover only while not started; a running request is failed on restart and is never replayed automatically.
- There is deliberately no proposal-selection, approval, Target control-plane, code/configuration modification, PR/deployment, or before/after verification endpoint. `implementationAvailable` is always false.

# 85. Phase 10 — 사용자 워크벤치

Phase 10은 기존 안전한 Backend API를 대체하지 않고, 사용자가 브라우저에서 Target 등록, 후보 선택, 명시 승인, 결과 확인과 분석 요청을 수행하도록 연결한다. 초기 구현은 React + TypeScript SPA를 기존 Spring Boot 애플리케이션에 정적 번들로 포함한다. 따라서 Docker Compose에서 별도 Frontend 컨테이너나 CORS 정책 없이 `http://localhost:8090` 하나만 사용한다.

## 85.1 사용자 흐름

```text
Target Profile YAML 붙여넣기 또는 업로드
→ 문법·안전 정책 검증 결과 확인
→ 사용자가 Profile Version 활성화
→ Target과 등록된 GET 후보 선택
→ Batch 생성 (PENDING_APPROVAL)
→ 실행 범위가 표시된 확인 모달에서 명시 승인
→ 기존 Worker가 안전한 HTTP Batch 실행
→ 결과·Evidence·분석 선택 화면 표시
```

YAML을 한 번 활성화한 Target은 다시 등록할 필요 없이 목록에서 선택한다. 새 Version을 활성화한 뒤의 새 Batch만 그 Version의 정책을 사용한다. Batch는 생성 당시의 `profileVersionId`와 candidate snapshot을 저장하지만, Profile Version이 더 이상 active가 아니거나 Target이 disabled이면 이전 Version의 `PENDING_APPROVAL` Batch는 승인할 수 없다. 이미 승인됐지만 아직 Target 요청을 시작하지 않은 Batch도 dispatch 직전에 같은 조건을 다시 검사하고 `CANCELLED`와 `PROFILE_VERSION_INACTIVE` 사유로 끝낸다. 새 Version에서 다시 후보를 선택해 새 Batch를 만들어야 한다. Evidence와 완료된 Batch는 생성 당시의 immutable snapshot을 계속 표시한다.

### 85.1.1 Profile 단일 진실원천

UI가 등록한 Profile은 Target별 Versioned aggregate로 저장한다. aggregate에는 Target registration, generic Target Spec, 선택적인 Experiment Profile을 함께 넣고 하나의 DB transaction에서만 active pointer를 바꾼다. 실행 경로의 Target catalog, generic test catalog, experiment profile catalog은 모두 active `profileVersionId`만 조회한다. 화면에 보이는 활성 Profile과 worker가 사용하는 설정이 달라지는 merge는 허용하지 않는다.

기존 `target-profile.yaml` bootstrap은 하위 호환을 위해 처음 한 번만 Version으로 seed한다. 같은 Target에 DB active Version이 있으면 bootstrap 설정을 merge하거나 재활성화하지 않는다. bootstrap 내용이 바뀌어도 사용자가 UI 또는 명시적 import API로 검증·활성화하기 전에는 실행 정책이 바뀌지 않는다.

## 85.2 권한과 안전 경계

- 화면과 Chat은 등록된 Target ID, candidate ID, model key, architecture만 Backend API에 전달한다. 임의 URL, HTTP method, Header, Shell 명령, Docker 명령, DB 연결 정보, credential 또는 Target 코드 변경 요청을 실행 요청에 포함하지 않는다.
- Profile import의 기본 동작은 검증과 Draft 생성뿐이며 Target에 네트워크 요청을 보내지 않는다. Target reachability 확인은 Profile을 활성화한 후에도 기존의 명시 승인형 HTTP Batch로만 수행한다.
- Profile 활성화, HTTP Batch 승인, Failure Injection Plan 승인은 각각 독립된 명시적 UI 확인이 필요하다. Chat 메시지 자체는 승인으로 간주하지 않는다.
- Profile import·activation·HTTP Batch approval·Failure Injection Plan approval은 서버가 인증한 actor, 시각, request correlation ID, Profile Version과 함께 append-only audit event로 저장한다. 화면의 확인 모달만으로 권한을 판단하지 않는다.
- 초기 Docker 사용은 `LOCAL` access mode로만 제공하며 ARL HTTP 포트는 loopback에만 publish하고 actor는 `LOCAL_OPERATOR`로 기록한다. loopback 밖으로 노출하는 `SECURED` mode는 Bearer token 기반의 `VIEWER`, `PROFILE_EDITOR`, `EXECUTOR` 역할을 반드시 설정한다. 서버는 `/api/**` 전체를 default-deny로 처리하며, 안전한 조회는 `VIEWER`, Target 호출·실행·상태 변경은 `EXECUTOR`, Profile import·activation·Draft 생성은 `PROFILE_EDITOR`를 요구한다. Batch와 Plan 승인에는 `EXECUTOR`가 필요하다. Browser는 token을 server session, URL, log 또는 local storage에 저장하지 않고 현재 tab memory에서만 Authorization header로 보낸다.
- 분석은 사용자가 선택한 `SINGLE|MULTI + modelKey` 조합만 요청한다. UI와 Chat 모두 선택하지 않은 GPT/Qwen 조합을 자동 실행하지 않는다.
- 결과 화면은 기존 Evidence의 status, latency, byte count, checksum과 구조화된 분석 결과만 표시한다. Target HTTP 응답 body나 등록 과정의 secret을 저장·표시하지 않는다.
- ARL은 계속해서 Target 코드·설정·인프라·Docker·DB를 변경하거나 PR·배포·개선안 승인을 수행하지 않는다.

### 85.2.1 비신뢰 입력 경계

UI Profile import는 단일 Target Profile 문서만 받으며 64 KiB를 넘지 않는다. 문서는 literal value만 허용하고 environment placeholder, YAML tag, anchor/alias, unknown field, 중복 key, 20단계 초과 nesting을 거절한다. target ID·origin·CIDR·candidate 수·path·timeout은 기존 Backend와 같은 validation을 통과해야 하며 validation 결과는 외부 fetch 없이 계산한다. health path와 모든 generic operation path는 query, fragment, user-info 없이 slash로 시작하는 고정 relative path만 허용한다. 따라서 Profile, audit event, Batch snapshot과 Target HTTP request에 token이나 다른 secret이 query로 들어갈 수 없다.

10.6의 OpenAPI와 README 입력은 각각 1 MiB와 256 KiB로 제한한다. OpenAPI의 외부 URL, remote `$ref`, callback과 webhook은 읽지 않으며 document 내부 JSON Pointer만 해석한다. README와 OpenAPI의 모든 텍스트는 instruction이 아닌 비신뢰 data로 구획해 모델에 전달하고, 모델 출력은 등록되지 않은 endpoint를 실행하거나 Profile을 활성화할 수 없다.

## 85.3 Chat은 안전한 UI 제어 계층이다

Chat은 자유로운 Tool 실행 Agent가 아니다. 다음의 구조화된 의도만 해석해 화면 상태를 바꾼다.

```text
TARGET_PROFILE_DRAFT
SELECT_TARGET
SELECT_CANDIDATES
OPEN_BATCH_APPROVAL
SELECT_ANALYSIS_CONFIGURATIONS
OPEN_ANALYSIS_RESULT
```

실제 상태 변경은 같은 화면의 명시적 버튼 동작과 기존 Backend validation을 모두 통과해야 한다. Chat은 등록되지 않은 endpoint를 candidate로 만들거나, 승인 모달을 우회하거나, Target HTTP 요청을 직접 보내지 않는다.

### 85.3.1 재시도와 복구 UX

UI는 Batch, analysis, comparison, root-cause 요청마다 payload에 결합된 client-generated idempotency key를 생성해 브라우저 refresh와 network retry 동안 재사용한다. 제출 중인 버튼은 중복 클릭을 막고, `202`, `PENDING_APPROVAL`, `APPROVED`, `RUNNING`, `RECOVERY_REQUIRED` 상태는 polling과 재연결 후 GET 조회로 복원한다. 새 요청을 의도한 경우에만 사용자가 새 key를 생성한다.

## 85.4 구현 순서와 완료 조건

1. **10.0** UX·안전 계약을 이 문서에 고정한다.
2. **10.1** Profile Version의 검증·저장·활성화 Backend를 구현한다. 정적 YAML bootstrap은 하위 호환 입력으로 유지한다.
3. **10.2** Target 등록 화면과 SPA 기반을 구현한다.
4. **10.3** 후보 다중 선택, Batch 생성·승인·실행 결과 화면을 구현한다.
5. **10.4** 사용자가 고른 분석 조합과 comparison/root-cause 결과 화면을 구현한다.
6. **10.5** 위 화면만 제어하는 안전한 Chat 워크벤치를 구현한다.
7. **10.6** OpenAPI 또는 README 입력에서 Profile과 GET 후보 Draft를 제안하되 사용자의 검증·활성화 전에는 실행하지 않는다.
8. **10.7** Docker의 단일 URL 실행과 전체 E2E 시나리오를 검증한다.

---

# 85.5 후속 고려 — LangGraph 검토

자체 Agent Orchestration의 복잡도를 평가한다.

필요성이 실제로 확인된 경우에만 LangGraph 도입.

비교 기록:

```text
Before LangGraph

State management complexity
Orchestration code size
Failure recovery
Human approval
Checkpoint

After LangGraph
...
```

---

# 86. 초기 API 예시

## Target

```text
GET /api/targets

GET /api/targets/{id}/health
```

---

## Experiment

```text
POST /api/experiments

Idempotency-Key: {client-generated-key}

GET /api/experiments/{id}

GET /api/experiments/{id}/evidence
```

요청:

```json
{
  "targetSystem": "EVENTFUL_COMMERCE",
  "type": "STOCK_CONCURRENCY",
  "parameters": {
    "stock": 100,
    "requestCount": 1000,
    "concurrency": 100,
    "quantityPerRequest": 1,
    "loadProfile": {
      "workloadModel": "CLOSED_LOOP",
      "warmupDurationSeconds": 10,
      "measurementDurationSeconds": 60,
      "requestTimeoutMs": 3000,
      "retryPolicy": "NONE",
      "clientConnectionPoolSize": 100,
      "percentileRecorder": "HDR_HISTOGRAM",
      "coordinatedOmissionCorrection": true
    }
  }
}
```

Backend는 body의 `targetSystem` 문자열을 임의 URL로 변환하지 않고 등록된 Target ID로 조회한다. `Idempotency-Key`는 API 재시도 중 Run 중복 생성만 방지하며 Target action의 멱등성은 `ExperimentAction.actionId`로 별도 보장한다.

---

## Analysis

```text
POST /api/analysis-datasets/{datasetId}/analysis
```

요청:

```json
{
  "agentArchitecture": "SINGLE",
  "promptBundleVersion": "single-analyst-v1",
  "llm": {
    "provider": "OLLAMA",
    "model": "gpt-oss:20b"
  }
}
```

---

## Comparison

```text
POST /api/analysis-datasets/{datasetId}/compare
```

예:

```json
{
  "configurations": [
    {
      "agentArchitecture": "SINGLE",
      "model": "gpt-oss:20b"
    },
    {
      "agentArchitecture": "SINGLE",
      "model": "qwen3:14b"
    },
    {
      "agentArchitecture": "MULTI",
      "roleModels": {
        "SUPERVISOR": "gpt-oss:20b",
        "PLANNER": "gpt-oss:20b",
        "ANALYST": "gpt-oss:20b",
        "REVIEWER": "qwen3:14b"
      }
    }
  ]
}
```

실제 실행은 순차적으로 처리한다.

---

# 87. 성공 기준

V1 Core는 다음 조건을 만족하면 성공으로 본다.

### Reliability

```text
Eventful Commerce를 외부에서 테스트할 수 있다.

Experiment가 재현 가능하다.

Evidence가 구조화되어 저장된다.

RunManifest와 원본 Artifact provenance가 저장된다.

동시성 또는 장애 시나리오에서
정합성 결과를 검증할 수 있다.

Campaign 중단 후 논리 Step을 중복 생성하지 않고 재개할 수 있다.

외부 부작용의 결과가 불명확하면 이를 감지하여 reconcile하거나 RECOVERY_REQUIRED로 중단할 수 있다.

실패·취소·timeout 후 cleanup 상태를 확인할 수 있다.
```

### Agent

```text
Single Agent가 Evidence를 분석할 수 있다.

Agent가 추가 Experiment를 제안할 수 있다.

허용되지 않은 Experiment는 실행되지 않는다.

Agent 결과가 구조화되어 저장된다.
```

### LLM

```text
GPT-OSS와 Qwen을 설정으로 변경할 수 있다.

동일 Evidence를 두 모델에게 전달할 수 있다.

두 모델은 동시에 실행되지 않는다.

각 모델의 Token/Latency/결과가 저장된다.

고정 Dataset과 반복 실행으로 평가 결과의 분산을 확인할 수 있다.
```

### Safety

```text
등록되지 않은 Target URL에는 실행할 수 없다.

환경 Identity와 Capability가 일치하지 않으면 실행되지 않는다.

위험 Experiment는 유효한 승인 없이 실행되지 않는다.

Target 부하 측정 중 Local LLM inference가 실행되지 않는다.
```

### V1 이후 Multi-Agent

```text
Single/Multi가 동일한 ReliabilityAgent 계약을 구현한다.

동일 Evidence를 대상으로 비교 가능하다.

Multi-Agent가 실제 품질을 개선했는지 데이터로 판단 가능하다.
```

Multi-Agent 조건은 V1 Core 완료 조건에 포함하지 않는다.

---

# 88. 최종 학습 목표

이 프로젝트를 통해 다음을 직접 학습한다.

## Backend

```text
Concurrency

Idempotency

Consistency

Optimistic Lock

Unique Constraint

State Machine

Transaction Boundary

Failure Recovery
```

## Distributed System

```text
Kafka

At-Least-Once

Consumer Retry

DLT

Outbox

Saga

Redis

Distributed Lock

Eventual Consistency
```

## Reliability

```text
Load Testing

Failure Injection

Chaos Testing 기초

Failure Boundary

Observability

Metric Analysis

Before / After Verification
```

## AI Agent

```text
Tool Calling

Structured Output

Agent State

Agent Safety

Single Agent

Multi Agent

Supervisor Pattern

Planner / Analyst / Reviewer

Human-in-the-loop
```

## LLM

```text
Local LLM

Ollama

GPT-OSS

Qwen

Model Abstraction

Provider Abstraction

Prompt Versioning

Model Evaluation

Token / Latency Measurement
```

---

# 89. 프로젝트 핵심 한 문장

> Autonomous Reliability Lab은 AI Agent가 외부 분산 시스템에 재현 가능한 신뢰성 실험을 수행하고, 수집된 Evidence를 기반으로 실패 경계와 원인 후보를 탐색하며, 서로 다른 Agent 구조와 LLM의 분석 품질까지 비교할 수 있도록 설계한 자율 Reliability Testing Platform이다.

---

# 90. 가장 중요한 설계 규칙

이 프로젝트를 구현하면서 다음 규칙을 유지한다.

```text
AI가 테스트 코드를 마음대로 생성하지 않는다.

AI가 시스템 상태를 직접 변경하지 않는다.

실험은 항상 사전에 정의된 Experiment를 통해 실행한다.

수치 계산은 Backend가 한다.

Agent는 수치를 해석한다.

LLM 결과는 사실이 아니라 가설이다.

Finding의 최종 판단은 사용자가 한다.

Single-Agent부터 구현한다.

Multi-Agent의 장점을 가정하지 않는다.

GPT-OSS와 Qwen의 우열을 가정하지 않는다.

동일 Evidence를 이용하여 공정하게 비교한다.

Local LLM inference는 동시에 하나만 실행한다.

LangGraph는 현재 필요성이 확인되기 전에는 추가하지 않는다.

새 기술은 현재 문제를 해결할 명확한 이유가 있을 때만 도입한다.

Experiment는 사전 조건·Invariant·수렴 조건·cleanup 검증 없이는 자동 실행하지 않는다.

비교 가능한 모든 Run에는 RunManifest를 저장한다.

Target 부하 측정과 Local LLM inference를 동시에 실행하지 않는다.

단일 실행 결과만으로 성능 개선이나 LLM 우열을 확정하지 않는다.

외부 작업 결과가 불명확하면 같은 작업을 자동 재실행하지 않는다.

평가 모드의 Tool은 동결된 Dataset Replay만 사용하고 외부 상태를 변경하지 않는다.

승인은 PlannedRunSpec에 묶고 실제 환경은 PRE/POST RunManifest로 검증한다.

Experiment와 Local LLM은 같은 Host resource group에서 공용 lease 없이 실행하지 않는다.
```

---

# 91. 구현 전 확인이 필요한 사용자 입력

다음 정보는 추측해서 고정하지 않는다. 값이 제공되기 전에는 안전한 기본값으로 기능을 비활성화하거나 `UNVERIFIED` 상태로 둔다.

## 91.1 Target Onboarding 필수

```text
1. Target ID, Environment, Base URL, allowed origin, allowed CIDR와 Health endpoint

2. Target Profile과 사용할 Adapter ID (`HTTP_SCENARIO_V1` 또는 Plugin)

3. Scenario별 parameter schema, 최대 부하와 실행 허용 환경

4. 표준 HTTP Scenario endpoint 또는 Target Package의 등록된 Scenario ID

5. STOCK_CONCURRENCY에 사용할 fixture, 결과, Evidence 계약

6. 실험용 상품·사용자·주문 데이터를 만드는 방법

7. 실험 후 데이터를 초기화하거나 격리하는 방법

8. Redis stock과 DB stock을 안전하게 조회하는 방법

9. Target environment identity를 확인할 방법

10. Credential을 환경변수 또는 로컬 secret으로 전달하는 방법

11. 주문 API의 idempotency key, correlation ID, status query 지원 여부

12. 예약·확정·취소·timeout 상태별 정확한 재고 business invariant

13. Redis/DB 정합성이 수렴해야 하는 조건과 허용 시간

14. Lab 또는 Load Generator crash 후 Target 작업 상태를 조회·복구하는 방법

15. Remote Target이면 Runner group, 네트워크 경로, secret reference와 실행 시간창
```

Credential 값 자체는 이 문서나 Git에 기록하지 않는다.

## 91.2 실행 정책 결정

```text
하루 Campaign 시작·종료 시각과 실행 요일

최대 concurrency, request 수, duration

동시에 다른 개발 작업이 Target을 사용할 수 있는지

실험 실패 시 즉시 중단할 조건

실험 데이터 삭제 허용 범위

MODERATE / DESTRUCTIVE Experiment 승인 방식

Load Profile의 open/closed model, 목표 RPS, ramp, timeout, retry

Failure Control이 허용할 container/service/operation 목록

실험 로그·Artifact의 보존 기간, 삭제 범위와 PII 처리 정책
```

값이 정해지기 전 기본값:

```text
timezone = Asia/Seoul
manualCampaignStart = true
maxConcurrency = 100
maxRequests = 1000
maxDurationSeconds = 60
moderateExperimentsEnabled = false
destructiveExperimentsEnabled = false
stopOnCleanupFailure = true
```

## 91.3 Observability 확인

```text
Prometheus 또는 Actuator Metric 제공 여부

Kafka consumer lag 조회 방법

Outbox pending/publish latency 조회 방법

DB connection pool과 query latency 조회 방법

Redis latency 조회 방법

Log 위치와 correlation ID 지원 여부
```

수집할 수 없는 Metric을 요구하는 Experiment는 구현 순서를 뒤로 미룬다.

## 91.4 LLM 평가 전 필수

```text
Ollama 설치 여부와 endpoint

GPT-OSS / Qwen의 정확한 model tag와 quantization

사용 가능한 RAM / VRAM과 동시에 유지 가능한 모델 수

허용할 context size와 최대 분석 시간

Blind Human Evaluation을 사용자가 직접 채점할지 여부

Ground Truth 작성자와 검토 방법

Blind reviewer 수와 평가 rubric
```

모델 정보가 확정되기 전에는 특정 모델 크기를 필수 계약으로 두지 않는다.
