# Agentic Reliability Lab

여러 프로젝트에 붙여 **안전한 신뢰성 확인을 실행하고, 결과를 AI로 분석하는 로컬 워크벤치**입니다.

특정 서비스의 코드·DB·스크립트에 종속되지 않습니다. Target Profile에 허용한 범위 안에서만 동작하며, 현재 실제 실행 기능은 명시적으로 등록한 읽기 전용 `GET` 점검으로 제한됩니다.

## 먼저 알아둘 현재 범위

현재 구현 범위는 `DESIGN.md`의 Phase 0–10.7입니다.

| 할 수 있는 일 | 아직 할 수 없는 일 |
| --- | --- |
| Target의 health와 등록한 읽기 전용 HTTP endpoint를 여러 개 선택해 점검 | Target 저장소를 읽고 코드·도메인·DB 구조를 자동 이해 |
| 점검 결과를 단일/멀티 에이전트와 선택한 로컬 모델로 분석·비교 | 동시성, 정합성, 멱등성 테스트를 AI가 자동 설계·실행 |
| 근거 ID를 포함한 원인 가설과 개선 제안을 확인 | POST/PUT/PATCH/DELETE, DB, Docker, 셸 명령으로 Target 변경 |
| OpenAPI 또는 README 텍스트에서 읽기 전용 점검 후보 초안을 생성 | 테스트 데이터 생성·정리나 실제 장애 주입 실행 |

즉, 지금의 ARL은 **안전한 관측과 분석 도구**입니다. 동시성·정합성 테스트는 Target에 전용 테스트 계약 또는 Test Harness가 있어야 기존 고급 Experiment 경로로 실행할 수 있으며, 이를 Target 코드 없이 AI가 자동으로 만들어 실행하는 기능은 아직 개발하지 않았습니다.

## 주요 특징

- Target Profile 버전 관리: YAML을 검증·가져오기·활성화한 뒤, 활성 버전만 실행 후보에 사용합니다.
- 안전한 HTTP Batch: health와 선언한 `GET` endpoint만 후보가 되며, 여러 후보를 한 Batch로 묶어 사람이 승인한 뒤 실행합니다.
- 선택형 AI 분석: `SINGLE` 또는 `MULTI`, `GPT_OSS` 또는 `QWEN` 중 원하는 조합만 골라 분석합니다.
- 분석 비교: 선택한 2–4개 조합이 같은 불변 Dataset을 분석하므로 결과·토큰·시간을 비교할 수 있습니다.
- 원인 가설·개선 제안: 실행 증거를 근거로 제안만 만들며, 코드 수정·배포·승인·Target 변경을 자동으로 수행하지 않습니다.
- 안전 경계: 실행 결과에는 상태·지연 시간·본문 크기·해시만 저장하고, 응답 본문·인증정보·요청 본문은 저장하거나 모델에 전달하지 않습니다.

## 구성

```text
브라우저 (http://localhost:8090)
        │
        ├─ Target Profile 관리 / 안전한 테스트 / 분석 / Chat 안내
        │
ARL Backend + PostgreSQL ── 허용된 읽기 전용 GET ── Target 프로젝트
        │
        └─ Ollama (호스트의 로컬 모델) ── 분석·비교·원인 가설 생성
```

- ARL과 PostgreSQL은 Docker Compose로 실행합니다.
- Ollama는 Windows 호스트에서 실행하며, ARL 컨테이너가 `host.docker.internal:11434`로 연결합니다.
- Target 프로젝트는 ARL과 별도의 Compose 프로젝트, WSL, 로컬 프로세스 또는 원격 `TEST` 환경일 수 있습니다. 단, Profile의 URL·origin·CIDR 허용 목록을 모두 통과해야 합니다.

## 요구 사항

- Docker Desktop 실행 상태
- Ollama 실행 상태
- 분석에 쓸 모델 한 개 이상
  - `qwen3:4b`: 비교적 가벼운 시작용 모델
  - `gpt-oss:20b`: 더 큰 디스크·메모리가 필요한 선택지
- Windows PowerShell

모델 저장 공간은 모델마다 다릅니다. `ollama list`로 실제 크기를 확인하세요. 처음에는 `qwen3:4b` 하나만 받아도 분석 흐름을 시험할 수 있습니다.

## 가장 빠른 실행

### 1. Ollama와 모델 준비

PowerShell에서 다음을 실행합니다.

```powershell
ollama pull qwen3:4b
ollama list
Invoke-RestMethod http://localhost:11434/api/tags
```

`gpt-oss:20b`도 사용할 경우에만 추가로 받습니다.

```powershell
ollama pull gpt-oss:20b
```

Compose에서 기본으로 기대하는 모델 이름은 다음과 같습니다.

```text
GPT_OSS = gpt-oss:20b
QWEN    = qwen3:4b
```

다른 태그를 사용하려면 ARL을 시작하기 전에 환경 변수를 지정합니다.

```powershell
$env:ARL_GPT_OSS_MODEL = '사용할-gpt-oss-태그'
$env:ARL_QWEN_MODEL = '사용할-qwen-태그'
```

### 2. Target 프로젝트를 먼저 실행

테스트할 프로젝트를 Docker, WSL 또는 로컬 프로세스로 실행합니다. 예를 들어 Docker Desktop에 노출된 Target은 ARL 컨테이너에서 보통 `http://host.docker.internal`로 접근합니다.

Target의 health endpoint와 점검할 공개 `GET` endpoint가 실제로 응답하는지 먼저 확인하세요.

### 3. ARL 실행

프로젝트 루트에서 다음을 실행합니다.

```powershell
cd "C:\Users\jybeo\OneDrive\Desktop\study\AgenticReliabilityLab"
.\start.ps1
```

스크립트는 Docker Engine과 Ollama 연결을 확인하고 PostgreSQL과 ARL을 기동한 뒤, health가 `UP`이 될 때까지 기다립니다.

준비가 끝나면 아래 주소를 엽니다.

```text
워크벤치: http://localhost:8090
Health:   http://localhost:8090/actuator/health
```

중지는 다음 명령을 사용합니다. PostgreSQL 데이터 볼륨은 유지됩니다.

```powershell
docker compose --profile arl down
```

## 화면에서 사용하는 순서

### 1. Target Profile 등록 및 활성화

1. 워크벤치의 **1. Target Profile** 탭을 엽니다.
2. YAML을 붙여넣고 검증합니다.
3. Draft로 가져온 뒤 내용을 검토합니다.
4. **활성화**합니다.

Profile은 실행 권한의 기준입니다. 새 버전을 활성화하면 이전 버전은 대체되며, 이전 버전을 기준으로 만든 미승인 Batch는 실행할 수 없습니다.

### 2. 안전한 테스트 후보 선택 및 승인

1. **2. 안전 테스트** 탭에서 Target을 선택합니다.
2. health와 Profile에 선언한 읽기 전용 endpoint 후보 중 하나 이상을 선택합니다.
3. Batch를 만들고 후보·URL·기대 상태를 다시 확인합니다.
4. 확인 문구를 입력해 Batch 실행을 승인합니다.
5. 실행이 끝나면 각 endpoint의 통과/실패, 상태 코드, 지연 시간, 본문 크기, SHA-256 해시를 확인합니다.

선택하지 않은 후보는 실행하지 않습니다. 승인 전에는 Target에 HTTP 요청을 보내지 않습니다.

### 3. 원하는 AI 분석 조합만 실행

1. **3. 분석** 탭에서 분석할 Batch를 선택합니다.
2. 원하는 조합만 체크합니다. 예를 들어 다음처럼 선택할 수 있습니다.
   - Single agent + GPT_OSS
   - Multi agent + GPT_OSS
   - Single agent + QWEN
3. 분석 또는 비교를 시작합니다.
4. `RUNNING`은 Ollama의 로컬 모델 응답을 기다리는 상태입니다. 첫 요청은 모델 메모리 적재 때문에 더 오래 걸릴 수 있습니다.
5. 완료되면 Findings, Recommendations, 사용 토큰과 걸린 시간을 확인합니다.

Multi agent는 `SUPERVISOR → PLANNER → ANALYST → REVIEWER` 순서로 같은 불변 Dataset을 분석합니다. 이 역할들은 Target, HTTP, DB, 셸, 파일 도구를 갖지 않습니다.

### 4. 원인 가설과 개선 제안 확인

분석 결과에서 **원인 가설·개선 제안 보기**를 누르면 별도의 비동기 보고서를 만듭니다.

- 모든 가설과 제안은 Evidence ID를 근거로 가집니다.
- 제안은 사람이 검토하기 위한 내용입니다.
- ARL은 Target 코드·설정·데이터를 변경하거나 PR·배포·승인을 실행하지 않습니다.

### 5. Chat 탭의 역할

**4. Chat**은 화면 사용 흐름과 다음 행동을 안내합니다. Chat 메시지만으로 Batch 승인, Target 호출, 장애 주입, 코드 변경은 일어나지 않습니다.

## Target Profile 작성법

### Compose용 파일과 화면 입력은 다릅니다

프로젝트 루트의 `target-profile.yaml`은 Docker Compose가 시작할 때 읽는 **환경 설정 파일**입니다. 이 파일에서는 환경별 값 치환을 위해 Spring placeholder를 쓸 수 있습니다.

```yaml
base-url: ${ARL_SIDE_PROJECT_BASE_URL:http://host.docker.internal}
```

반면 워크벤치에 붙여넣어 import하는 Profile은 DB에 남는 **불변 버전**입니다. 재현성과 비밀값 보관 금지를 위해 `${...}` placeholder를 허용하지 않습니다.

따라서 화면 import에서는 아래처럼 실제 값을 넣어야 합니다.

```yaml
base-url: http://host.docker.internal
```

`INVALID_REQUEST: Target Profile document must not contain environment placeholders`는 위 두 용도를 섞었을 때 나오는 정상적인 검증 오류입니다.

### 최소 예시

아래는 화면에 붙여넣을 수 있는 읽기 전용 Profile 예시입니다. `base-url`, `allowed-origin`, `allowed-cidrs`, endpoint 경로는 자신의 Docker/네트워크 환경에 맞춰 바꾸어야 합니다.

```yaml
arl:
  targets:
    registrations:
      - id: my-target-local
        name: 나의 로컬 Target
        adapter-type: HTTP_TARGET
        environment: LOCAL
        base-url: http://host.docker.internal
        allowed-origin: http://host.docker.internal
        allowed-cidrs:
          - 192.168.65.0/24
        health-path: /actuator/health
        source-repository: my-target
        identity-verification: CONFIGURATION_ONLY
        capabilities:
          - HEALTH
          - HTTP_API

  target-specs:
    registrations:
      - target-system-id: my-target-local
        execution-enabled: true
        host-resource-group: my-target-public-api
        max-batch-size: 5
        request-timeout: 5s
        read-only-operations:
          - id: product-catalog
            title: 상품 목록 조회 가능 여부
            description: 공개 상품 목록 GET endpoint가 200을 반환해야 합니다.
            path: /api/products
            expected-status-codes:
              - 200
```

### Profile 규칙

- `LOCAL` 또는 `TEST` 환경만 실행할 수 있습니다. `STAGING`, `PRODUCTION`은 실행을 차단합니다.
- `read-only-operations`에는 고정된 상대 경로만 씁니다. query string, fragment, user-info, 변수 치환은 허용하지 않습니다.
- 현재 실행 가능한 HTTP method는 `GET`뿐입니다.
- `execution-enabled: true`여야 승인과 worker 실행이 가능합니다.
- 인증 토큰, 비밀번호, DB 접속 문자열, 요청 본문, 셸 명령을 Profile에 넣지 마세요.
- `allowed-origin`과 `allowed-cidrs`는 SSRF 방지를 위한 이중 제한입니다. Docker Desktop의 host gateway 대역은 설치 환경마다 다를 수 있으므로 실제 환경에 맞춰 확인해야 합니다.

`target-profile.sample.yaml`은 안전한 시작용 예시입니다. 실제 Target을 점검하려면 복사본인 `target-profile.yaml`을 만들고 필요한 읽기 전용 operation을 명시하세요.

## OpenAPI·README 초안 기능의 범위

Target Profile 탭의 초안 기능에 OpenAPI 문서나 README 내용을 붙여넣을 수 있습니다. ARL은 외부 URL이나 `$ref`를 가져오지 않고, 입력 텍스트 안에서 읽기 전용 endpoint 후보만 추려서 **검토용 YAML 초안**을 만듭니다.

초안은 실행되지 않습니다. 사용자가 endpoint, 기대 상태, CIDR, 실행 허용 여부를 직접 검토하고 import·활성화해야 후보가 됩니다. 이 기능은 코드 분석기가 아니며, 저장소 파일·DB 스키마·비즈니스 로직을 읽지 않습니다.

## 고급 Experiment API의 현재 위치

기존 Phase 1에는 `STOCK_CONCURRENCY`와 `HTTP_SCENARIO_V1` 같은 전용 Experiment 계약이 있습니다. 이는 Target이 다음과 같은 전용 endpoint를 이미 구현한 경우에만 사용합니다.

```text
POST {등록한 origin}/reliability/v1/scenarios/STOCK_CONCURRENCY/executions
```

이 경로는 Target이 멱등성, 결과 구조, cleanup 검증을 보장해야 합니다. 따라서 일반 프로젝트에 자동으로 동시성 테스트를 붙이는 기능이 아닙니다.

일반 프로젝트에 대해 지금 바로 사용할 수 있는 경로는 **Target Profile + 읽기 전용 HTTP Batch + AI 분석**입니다.

## 안전성과 권한

### LOCAL Compose 모드

기본 `compose.yaml`은 API 포트를 `127.0.0.1`에만 노출하고 `LOCAL` access mode로 실행합니다. 개인 로컬 실험용 설정입니다.

### SECURED 모드

외부에 API를 노출할 때는 반드시 `SECURED` 모드와 역할별 Bearer token을 사용해야 합니다.

- `VIEWER`: 조회
- `PROFILE_EDITOR`: Profile import, 활성화, 초안 생성
- `EXECUTOR`: Batch·Experiment·분석·승인 같은 상태 변경 요청

SECURED 모드는 `/api/**`를 기본 거부합니다. UI의 확인 모달은 편의 기능일 뿐이므로, 실제 권한 검사는 서버가 수행합니다. 승인 기록에는 actor, 시각, Profile version, correlation ID가 남습니다.

## 운영과 장애 확인

### 상태 확인

```powershell
Invoke-RestMethod http://localhost:8090/actuator/health
docker compose --profile arl ps
docker compose --profile arl logs --tail 100 arl
```

### 분석이 오래 `RUNNING`일 때

분석은 Ollama가 로컬 모델을 생성하는 비동기 작업입니다. 특히 첫 호출은 모델 적재 때문에 시간이 걸릴 수 있습니다.

1. 화면의 **상태 새로고침**을 누릅니다.
2. `ollama list`에서 선택한 모델이 있는지 확인합니다.
3. `Invoke-RestMethod http://localhost:11434/api/tags`로 Ollama API가 응답하는지 확인합니다.
4. ARL 로그와 Ollama 로그에서 모델 로딩·메모리 부족 오류를 확인합니다.

Ollama를 사용할 수 없으면 분석은 `MODEL_UNAVAILABLE`로 안전하게 실패하며 Target 호출은 추가로 발생하지 않습니다.

### Target 호출이 실패할 때

- Target 컨테이너 또는 프로세스가 먼저 정상 기동됐는지 확인합니다.
- ARL 컨테이너에서 접근 가능한 URL인지 확인합니다. Docker Desktop에서는 보통 `host.docker.internal`을 사용합니다.
- Profile의 `allowed-origin`, `allowed-cidrs`, `health-path`, operation 경로와 기대 상태 코드를 확인합니다.
- Profile을 바꿨다면 새 버전을 import하고 활성화한 뒤, 이전 Batch가 아니라 새 Batch를 만듭니다.

### 기본 포트

```text
ARL Workbench/API : http://localhost:8090
PostgreSQL        : localhost:5433
Ollama            : http://localhost:11434
```

## 개발·검증

전체 검증은 프로젝트 루트에서 실행합니다.

```powershell
.\gradlew.bat clean check bootJar
```

프론트엔드만 확인하려면 다음을 실행합니다.

```powershell
Set-Location frontend
npm run build
```

주요 구조는 다음과 같습니다.

```text
src/main/kotlin   Backend 도메인·애플리케이션·어댑터·API
src/main/resources Flyway migration과 애플리케이션 설정
frontend/         React Workbench
config/           Detekt 설정
DESIGN.md         Phase별 설계 계약과 안전 경계
compose.yaml      로컬 Docker Compose 구성
start.ps1         로컬 통합 기동 스크립트
```

비동기 요청은 PostgreSQL Durable Outbox와 lease worker가 처리합니다. 프로세스 재시작 후에도 실행 상태와 재시도 예산을 복구하며, 불명확한 외부 결과는 자동 재실행하지 않고 `RECOVERY_REQUIRED`로 남깁니다.

## 다음 핵심 개발 방향

ARL이 목표로 하는 다음 단계는 아래 세 가지입니다.

1. 사용자가 제공한 저장소 설명·API 명세·코드 구조를 안전하게 읽어 Target 이해 모델을 만듭니다.
2. 그 이해 모델을 바탕으로 동시성, 정합성, 멱등성, 재시도, 장애 복구 테스트 후보를 제안합니다.
3. 사용자가 승인한 후보만 격리된 Test Harness와 테스트 데이터 lifecycle 안에서 실행하고, 결과를 현재 분석·원인 가설 흐름으로 연결합니다.

이 단계는 현재 구현되어 있지 않습니다. 특히 쓰기 요청과 테스트 데이터 생성은 기존의 읽기 전용 안전 경계를 넓히므로, Target 계약·권한·격리·cleanup·감사 규칙을 별도로 설계한 뒤 추가해야 합니다.
