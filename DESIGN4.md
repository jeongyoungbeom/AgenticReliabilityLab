# DESIGN4 — YAML 기반 자동 신뢰성 테스트 파일럿

**상태:** 2026-08-26 합의 완료. 다음 구현 세션의 최우선 설계다.

`DESIGN.md`의 안전 경계, `DESIGN2.md`의 후보·승인 개념, `DESIGN3.md`의 선언형 실행 엔진을
버리지 않는다. 다만 고객이 실제로 겪는 앞단 흐름을 하나로 묶고, SideProject를 그 첫 파일럿으로
완주한다. 사용자 흐름이 충돌하면 이 문서가 우선한다.

---

## 1. 문제와 목표

현재 구현은 다음 기능을 각각 갖고 있어도, 사용자가 실제로는 아래 중간 단계를 모두 알아야 했다.

```text
Profile 등록 → Target 이해 문서 붙여넣기 → Snapshot 생성 → 후보 생성
→ 별도 Test Plan → 별도 선언형 명세 등록·승인·실행
```

이는 "고객 프로젝트에 YAML과 Test Harness를 붙이면, AI가 후보를 제안하고 사용자가 고른
테스트를 자동 실행한다"는 제품 약속과 다르다. 파일럿 목표는 **한 화면의 연속된 실제 사이클**이다.

```text
YAML 등록
  → 허용된 Swagger 자동 읽기
  → 안전한 기본 후보 표시
  → 사용자가 선택·실행 승인
  → reset / fixture / workload / observation / cleanup
  → PASS / VIOLATED / NOT_EVALUATED 결과
```

### 완료 조건

SideProject에서 사용자가 UI로 다음을 직접 완료한다.

1. Target Profile YAML을 등록·활성화한다. 재기동은 필요 없다.
2. ARL이 `openapi-path`의 Swagger를 읽고, 허용한 API만으로 기본 후보를 보여 준다.
3. 판매자·구매자·Harness 테스트 자격증명을 역할별로 입력하거나 배포 환경의 secret reference를
   사용한다.
4. 하나 이상을 선택하여 실행한다.
5. ARL이 실제 Target에 순차 테스트를 보내고, 근거와 함께 결과를 화면에 표시한다.

이 다섯 가지가 되는 것이 첫 번째 목표다. LLM/Ollama는 이 경로의 선행조건이 아니다.

---

## 2. 제품 계약

### 2.1 고객이 준비하는 것

고객은 테스트할 `LOCAL` 또는 격리된 `TEST` Target에 다음만 준비한다.

- Target Profile YAML
- Target의 OpenAPI 경로
- 실행을 허용할 business API 목록과 역할 매핑
- 테스트 계정/토큰 또는 Runner가 해석할 secret reference
- Harness 제어 API 네 개: 상태 조회, reset, 장애 주입, 장애 해제

YAML은 "무슨 API라도 실행해도 된다"는 문서가 아니다. **실행 권한의 allowlist**다. Swagger는
요청·응답의 모양을 알려 주고, YAML은 그중 ARL이 실행할 수 있는 좁은 범위를 정한다.

### 2.2 YAML에 추가·유지할 정보

구체적인 YAML schema 이름은 기존 Profile 모델 관례를 따르되, 아래 의미는 필수다.

| 정보 | 의미 |
|---|---|
| `base-url`, `allowed-origin`, `allowed-cidrs`, `environment` | Target 네트워크 경계. 쓰기 테스트는 `LOCAL`/`TEST`만 허용 |
| `openapi-path` / `openapi-paths` | 등록된 Target origin에 대한 **상대 경로** 또는 그 명시 목록. 예: `/v3/api-docs`. 절대 URL, query, fragment 금지이며 Swagger UI 탐색·redirect·외부 `$ref`로 경로를 늘리지 않는다 |
| 허용 operation | OpenAPI의 `operationId` 또는 정확한 method/path. public GET, 상품 생성, 주문 생성, 결제 webhook처럼 최소 권한으로 선언 |
| operation 역할 | seller/buyer/harness 중 어떤 auth profile로 호출하는지 |
| Harness | `/api/harness/state`, `/reset`, `/fault`, `/fault/release` 및 허용 fault type |
| 실행 상한 | request timeout, 최대 batch/trial/concurrency, fault TTL. 기본값은 제품이 제공 |

OpenAPI fetch는 `base-url`/`allowed-origin`/허용 CIDR을 이미 강제하는 Target HTTP transport로만
수행한다. redirect, 다른 host, 외부 `$ref`, Swagger 안의 URL 자동 fetch는 거부한다. 문서 크기·깊이
제한도 기존 bounded parser를 그대로 적용한다. 즉 기존의 "임의 네트워크 문서를 읽지 않는다"는
원칙을 버리는 것이 아니라, **등록된 Target 안의 한 경로만 읽는 좁은 예외**를 추가하는 것이다.

### 2.3 자격증명

ARL 자체 접근 권한(Viewer/Profile editor/Executor)과 Target 테스트 자격증명은 서로 다른 입력이다.
같은 입력칸이나 같은 토큰으로 보이면 안 된다.

- 배포 환경: 기존 auth profile의 secret reference를 Runner가 해석한다.
- UI 파일럿: seller/buyer/harness 토큰은 ARL의 짧은 수명 런타임 세션에만 둔다. DB, Profile YAML,
  브라우저 영구 저장소, Evidence, 로그, LLM prompt에는 저장하지 않는다.
- 실행 직전에 역할별 preflight 호출을 한다. 인증 실패는 테스트 실패가 아니라
  `TARGET_CREDENTIAL_EXPIRED` 또는 명확한 설정 오류로 표시한다.
- 토큰 갱신·로그인은 Target의 책임이다. ARL은 갱신에 실패하면 새 토큰 입력을 요구한다.

### 2.4 사용자가 보게 될 기본 UI

기본 화면은 다음 네 단계뿐이다.

1. **Target 설정** — YAML 붙여넣기/파일 불러오기, 검증·활성화, Swagger 발견 결과
2. **접근 설정** — Target 역할별 테스트 자격증명 입력 및 preflight 결과
3. **테스트 선택** — 자동 생성 후보 카드의 다중 선택과 실행 승인
4. **결과** — 각 테스트의 단계·HTTP 결과·관측값·판정·복구 상태

기존 `Target 이해 모델`, 수동 JSON 명세 등록, 내부 Test Plan 화면은 삭제하지 않는다. 고급·개발자
경로로 남기되 파일럿의 기본 화면에서는 다음 행동을 막는 별도 관문으로 두지 않는다. 후보 선택은
내부적으로 기존 Test Plan/선언형 명세로 변환해 기존 승인·감사·실행 엔진을 재사용한다.

---

## 3. 후보와 명세를 만드는 방식

### 3.1 후보의 원천

Profile 활성화가 성공하면 ARL은 Swagger snapshot을 만들고, YAML allowlist와 교집합인
operation만 남긴다. 이 snapshot으로 아래의 **결정적 기본 후보**를 즉시 만든다.

| 후보 | SideProject 파일럿의 의미 |
|---|---|
| 가용성 | health와 public product catalog가 2xx를 반환한다 |
| 상품 생성 | 판매자 역할로 새 테스트 상품을 생성하고 생성 응답을 확인한다 |
| 주문 workflow | 새 상품 생성 → 응답의 상품 ID capture → 구매자 역할로 주문을 생성한다 |
| 결제 성공 | 주문에 대한 정상 결제 callback/workflow와 최종 상태를 확인한다 |
| 멱등성·중복 저장 | 정확히 같은 canonical body와 같은 idempotency/event key를 두 번 보낸 뒤 business 결과가 하나인지 확인한다 |
| 동시성 | 같은 의도 요청을 기본 20개 병렬 × 3 trial로 실행해 응답·최종 상태를 판정한다 |
| 결제 장애·복구 | 허용된 payment fault를 주입하고 실패 지점·상태를 관측한 뒤 release·retry·복구를 확인한다 |

첫 수직 슬라이스는 앞의 네 후보로 충분하다. 멱등성·동시성·장애 후보는 뒤 단계에서 추가하되,
나중에 다른 UI 흐름으로 만들지 않는다.

LLM은 이후에 기본 목록에 없는 후보를 추천하거나 결과를 해석할 수 있다. LLM이 없거나 실패해도
기본 후보 생성·실행·판정은 중단되지 않는다. LLM은 request body, 실행 범위, 합격/불합격을 결정하지
않는다.

### 3.2 Swagger의 역할과 한계

Swagger의 request/response schema와 example은 template의 입력을 만드는 데 쓴다.

- example이 있으면 우선 사용하고, 없으면 schema의 필수 필드에 맞는 안전한 테스트 값을 생성한다.
- 상품 생성 응답의 ID처럼 다음 호출에 필요한 scalar는 response capture로 내부 변수에 저장한다.
- 이후 body/path/header에는 명세가 허용한 capture 변수만 참조할 수 있다.
- Swagger만으로 도메인 규칙을 지어내지 않는다. 상품→주문, 중복 금지, 결제 복구처럼 의미 있는
  후보는 파일럿의 검증된 template와 Harness 관측 계약으로만 실행한다.

사용자는 매 테스트마다 request body, Test Plan, JSON 명세를 손으로 만들지 않는다. Swagger가
모호하거나 필요한 operation/response field가 없으면 해당 후보만 `NOT_READY`로 표시하고, 나머지
후보는 계속 실행할 수 있다.

---

## 4. 결정적 실행 계약

### 4.1 실행 순서와 데이터

- Target별 활성 쓰기 실행 슬롯은 하나다. 선택한 테스트는 **순차 실행**한다.
- 각 테스트 전 reset → state 확인을 하고, `finally`에서 reset/해제·state 확인을 다시 수행한다.
- 이 순서가 보장되므로 파일럿 Harness의 전역 count(`orderCount` 등)는 충분하다. 첫 파일럿에
  run-scoped state를 강제하지 않는다.
- fixture는 기존 사업 데이터를 찾지 않고 매 테스트마다 새로 만든다. 응답 capture로 얻은 ID를
  다음 주문/결제 요청에 연결한다.
- ARL이 `runId`, `trialId`, correlation ID, idempotency key를 내부 생성한다. 사용자가 매번 입력할
  설정이 아니며, 결과와 로그의 상관관계에만 쓴다.

### 4.2 기본값과 고급값

| 항목 | 기본값 | 변경 위치 |
|---|---:|---|
| 비동기 수렴 대기 | 최대 5초, 200ms polling | Profile 고급 설정 |
| 동시성 | 20 parallel requests × 3 trials | Profile 고급 설정, YAML 상한 이내 |
| 테스트 실행 | 한 Target에서 순차 | 사용자 변경 불가 |
| reset/state 확인 | 각 테스트 전과 종료 정리 | 사용자 변경 불가 |

일반 사용자는 이 값을 묻지 않는다. Target 특성상 필요할 때만 Profile 편집자가 상한 안에서 조정한다.

### 4.3 판정과 결과

결과는 반드시 구분한다.

| 결과 | 뜻 |
|---|---|
| `PASSED` | 필요한 관측이 모두 있고, 결정적 조건을 만족 |
| `VIOLATED` | 필요한 관측이 있고, 조건이 깨짐 |
| `NOT_EVALUATED` / `INCONCLUSIVE` | 관측·수렴·사전조건이 부족해 판정하지 못함. 통과가 아님 |
| `TARGET_CREDENTIAL_EXPIRED` | Target 자격증명 문제. 테스트 결함이 아님 |
| `RECOVERY_REQUIRED` | reset·fault release 또는 상태 확인이 끝나지 않아 다음 쓰기 테스트를 차단 |

Evidence에는 민감한 본문·토큰을 저장하지 않는다. 결과 화면에는 테스트 이름, 각 단계의 상태·지연,
HTTP status, 안전한 resource reference, Harness state 변화, 실패·장애 주입 위치만 보인다.

### 4.4 멱등성·동시성·장애의 최소 계약

- **멱등성:** canonical body와 idempotency/event key를 정확히 동일하게 두 번 요청한다. 응답뿐 아니라
  Harness state와 명시한 business count가 하나인지 판정한다. outbox row ID 같은 내부 생성 ID가 다르다는
  사실만으로 중복이라고 판정하지 않는다.
- **동시성:** 표준 workload는 20×3이다. 결과는 trial별로 보이며, 한 trial의 실패/판정 불가를 전체
  통과로 합치지 않는다.
- **장애:** fault inject 응답과 결과에는 `faultId`, `injectionPoint`, 설명, TTL/해제 결과를 남긴다.
  따라서 사용자는 장애가 persistence 전·후 어느 지점에서 발생했는지와 그에 따른 상태 변화를
  구분해 볼 수 있다. TTL은 Target의 마지막 안전망이고, ARL은 `finally`에서도 release를 시도한다.

---

## 5. 구현 단계와 검증

### Phase P1 — 등록부터 후보 표시까지

**바꿀 영역:** Target Profile schema/validator, 안전한 OpenAPI fetch, snapshot/candidate service,
기본 UI의 Target 설정·역할별 runtime credential 화면.

**완료 기준:** SideProject YAML을 UI에서 활성화하면 재기동 없이 Swagger가 발견되고, allowlist 밖의
operation은 후보에 없으며, health/product/order/payment 기본 후보가 보인다. Swagger fetch가 다른
host·redirect·외부 `$ref`로 나가려 하면 거부된다.

### Phase P2 — 첫 실제 한 사이클

**바꿀 영역:** 후보 선택→내부 Plan/명세 변환, response capture/template resolver, 순차 실행 orchestration,
결과 UI.

**완료 기준:** 사용자가 UI에서 후보를 고르고 실행해 SideProject에서 health, 상품 생성,
상품→주문, 결제 성공 중 하나 이상을 실제로 `PASSED` 또는 근거 있는 실패로 본다. 이 단계가 끝나기
전에는 다음 기능으로 넓히지 않는다.

### Phase P3 — 일반 신뢰성 기본 세트

**바꿀 영역:** idempotency/duplicate template, concurrency template, global Harness count observation,
trial별 결과 표시.

**완료 기준:** 정확히 동일한 재요청과 20×3 동시성 후보가 UI에서 선택·실행되며, trial별
`PASSED`/`VIOLATED`/`NOT_EVALUATED`가 보인다.

### Phase P4 — 결제 장애와 복구

**바꿀 영역:** fault 결과 계약의 injection-point 정보, payment failure/release/retry template,
복구·cleanup 화면.

**완료 기준:** 결제 장애 후보 한 건이 실제 주입·관측·release·retry를 거치고, 남은 fault가 없음을
확인한다. 어느 단계에서 실패했는지도 결과에서 보인다.

### Phase P5 — AI·회귀와 제품 마감

**바꿀 영역:** 기본 후보와 LLM 후보 비교, 선택한/승인된 명세의 회귀 실행, 오류 문구와 마스킹,
기존 고급 화면의 정리.

**완료 기준:** Ollama 없이도 P1–P4가 유지되고, Ollama가 있을 때만 추가 후보·결과 해석이 더해진다.
승인된 테스트는 동일 Profile 버전에서 회귀 실행할 수 있다.

---

## 6. 범위와 한계

- ARL은 Target이 격리한 `LOCAL`/`TEST` 환경만 실행한다. 외부 부작용 차단은 Target 환경의 책임이며,
  ARL은 환경 gate와 allowlist를 강제한다.
- API가 Swagger에 있다고 자동 실행하지 않는다. YAML allowlist, template, 역할, Harness capability가
  모두 맞아야 한다.
- Swagger에 없는 도메인 규칙, 내부에만 존재하고 `/state`에 드러나지 않는 상태, 결정적 thread
  interleaving은 이 파일럿의 보장 범위가 아니다.
- Target의 회원가입·토큰 갱신을 ARL이 대신 구현하지 않는다. 단, 만료를 명확히 진단한다.
- SideProject에서 이미 만든 Harness 네 API는 이 파일럿의 제어면이다. 일반 고객도 같은 개념의 네
  endpoint(또는 동등한 인증된 control plane)를 제공해야 P3/P4까지 할 수 있다.

## 7. 다음 세션의 시작 규칙

다음 구현 세션은 `AGENTS.md`, `.agents/skills/SKILL.md`, 이 문서, `HANDOFF.md`를 먼저 읽는다.
P1만 먼저 조사·구현·검증하고, P2의 실제 UI 사이클이 확인되기 전에는 구조를 넓히거나 새 일반화 계층을
추가하지 않는다. 구현 중 새 사실이 이 계약을 바꾸면 코드보다 먼저 이 문서와 handoff를 갱신한다.
