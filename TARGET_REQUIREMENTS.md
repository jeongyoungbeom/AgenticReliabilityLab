# Target이 갖춰야 할 것

ARL이 판정을 내리려면 Target 쪽에서 준비되어 있어야 하는 것들을 모았다.
기준은 코드다 — 문서에만 있고 구현이 없는 것은 아래 "아직 쓰이지 않는 것"으로 내렸다.

관통하는 원칙 하나: **소스 종류는 Target이 자기 몫을 했을 때 쓸 수 있다.**
Target이 준비하지 않은 관측은 조용히 통과하지 않고 판정 불가가 된다. 그래서 아래를 하나도 안 해도
ARL은 틀린 답을 내지 않는다. 다만 답을 거의 못 낸다.

---

## 1. 없으면 아무것도 안 되는 것

### 네트워크 도달성

Target 호스트가 Profile의 `allowed-network-cidrs` 안에서 해석되어야 한다.
ARL은 DNS 응답을 **전부** CIDR로 검사한 뒤 그 IP에 핀을 고정한다(`TargetNetworkPolicy`).
하나라도 밖에 있으면 요청이 나가지 않는다. 외부 Prometheus·Tempo 주소도 같은 검사를 통과해야 한다.

### 인증

Profile의 `auth-profiles`에 선언한 역할별 자격증명을 ARL이 환경변수로 받는다
(`EnvironmentSpecAuthProvider`). 명세 문서에는 자격증명이 들어가지 못한다 —
`Authorization` 같은 헤더를 명세가 직접 쓰면 검증에서 거부된다.

Target 쪽에서 필요한 것은 **역할이 분리되어 있을 것** 하나다.
읽기 전용 역할로 상태를 읽고 쓰기 역할로 워크로드를 돌릴 수 있어야 Profile이 최소 권한으로 좁혀진다.

### Runner가 관리하는 헤더를 건드리지 않기

ARL이 붙이는 헤더는 Target이 덮어쓰거나 재작성하면 안 된다.
프록시나 게이트웨이가 헤더를 지우는 환경이면 아래 `X-ARL-Trial`이 도착하지 않는다.

| 헤더 | 용도 |
|---|---|
| `X-ARL-Run-Id` | 모든 요청. 어느 실행에서 온 요청인지 |
| `X-ARL-Trial` | **워크로드 요청에만.** 트레이스 귀속 (아래 참조) |

---

## 2. 관측 소스별 — 쓰려는 것만

### HARNESS_STATE — 스냅샷 상태

읽기 전용 endpoint 하나. Profile의 `observation-sources[].endpoint`에 상대 경로로 선언한다.

```json
{
  "contractVersion": "HARNESS_STATE_V1",
  "fields": ["dbStock", "redisStock", "redisHoldCount", "orderCount"],
  "state": {
    "dbStock": 10,
    "redisStock": 10,
    "redisHoldCount": 0,
    "orderCount": 0
  }
}
```

- `contractVersion`이 정확히 `HARNESS_STATE_V1`이 아니면 그 소스의 관측이 전부 미관측이 된다.
- `fields`는 런타임 capability다. Profile 허용 목록과의 **교집합만** 실제로 읽힌다.
- 한 응답의 snapshot에서 여러 field를 함께 뽑는다. 이게 계약의 핵심이다 —
  두 field를 비교하는 불변식이 같은 순간을 보고 있어야 한다.
- 상태를 바꾸면 안 된다. 관측이 Target을 바꾸면 그 다음 관측이 무엇을 보는지 아무도 모른다.

### TRACE — 시간축

**두 가지가 필요하다.**

**(1) 트레이스 저장소.** Tempo의 `GET /api/search`를 호출한다. 주소는 Profile이 절대 URL로 소유하고,
Target의 CIDR allowlist를 통과해야 한다.

**(2) 시행 귀속 계측 — 이게 없으면 TRACE 소스는 아무것도 매칭하지 못한다.**

ARL은 판정 대상인 **워크로드 요청에만** `X-ARL-Trial: <runId>/<시행번호>` 헤더를 보낸다.
Target은 그 값을 **현재 스팬의 속성으로** 남겨야 한다. 속성 이름은 Profile의 TraceQL이 정하므로
자유지만, 샘플은 `arl.trial`을 쓴다.

```
서블릿 필터 / 인터셉터 하나:
  X-ARL-Trial 헤더가 있으면 → Span.current().setAttribute("arl.trial", 값)
  없으면 아무것도 하지 않는다
```

그리고 Profile의 TraceQL이 `${trial}` 자리 표시자를 포함해야 한다. 엔진이 질의 직전에 채운다.

```yaml
queries:
  reserveSpans: '{name="inventory.reserve" && span.arl.trial="${trial}"}'
```

**왜 이렇게까지 하는가.** 시간 창만으로는 부족하다. 스팬 이름만 맞추는 쿼리는 다른 개발자의 요청,
이전 시행, 그리고 **이 시행 자신의 준비 단계**에도 걸린다. 준비의 `POST /products`가
`{name="db.query" && span.db.table="products"}`에 걸리는 것은 가정이 아니라 샘플 Profile에 실제로
있던 일이다. 그렇게 들어온 트레이스는 짝의 한쪽만 가지고 있어서, **아무도 저지르지 않은 위반**으로
보고된다.

준비 단계에 헤더를 붙이지 않는 이유도 같다. 준비도 같은 run·같은 시행이므로, run 단위로만 좁히면
fixture 생성이 "시작만 하고 끝내지 않은 워크로드 요청"처럼 보인다.

계측이 없으면 TRACE 관측은 빈 목록이 되고, 시간축 불변식은 판정 불가로 내려간다.
**조용히 통과하지는 않는다.** 안전한 실패이지 정상 동작은 아니다.

### PROMETHEUS — 메트릭

Profile이 절대 URL과 PromQL을 소유한다. 쿼리 결과가 **정확히 하나의 series**여야 한다 —
비었거나 여러 개면 그 관측은 미관측이다. Target 쪽에서 필요한 것은 메트릭이 실제로 노출되어 있고
CIDR allowlist를 통과하는 것뿐이다.

---

## 3. 환경 리셋

`state-changing-allowed: true`인 Profile은 **검증된 리셋**이 있어야 한다. 없으면 Profile 검증에서 거부된다.

- 리셋 hook 호출 하나 (`ProfileResetDefinition.hook`)
- 리셋이 실제로 됐는지 확인하는 검증 호출들 (`verifications`) — 각각 식과 조건을 가진다
- 예상 소요 시간 (`expectedDuration`)

sideProject 기준 리셋 1회가 약 120초다. `시행: 20`에 `정리시점: 시행마다`면 40분이 된다.

---

## 4. 아직 쓰이지 않는 것 (Phase 21 이후)

아래는 `TEST_SPEC.md`에 설계가 있고 검증기도 통과시키지만, **실행기가 아직 수행하지 않는다**
(`SpecWorkloadExecutor`가 `CALL`과 `WAIT` 외의 단계 종류를 거부한다).
지금 Target에 만들 필요는 없다.

### 결함 주입 (`INFRA_ACTION` / 주입)

```yaml
- 이름: 결제장애
  주입:
    종류: PAYMENT_FAILURE
    범위: 다음1건
    TTL: 60s
  저장: faultId
```

Target 쪽 요구사항이 될 것:

- Profile의 `supported-faults`에 선언한 종류를 주입하는 API
- 주입마다 `faultId`를 돌려줄 것
- **TTL 자동 만료** — 엔진이 죽어도 결함이 풀려야 한다. 이게 이 설계에서 가장 중요한 부분이다.
- 해제 API (선택 — 안 써도 엔진이 실행 끝에 전부 해제한다)

### 인프라 제어 (`INFRA_ACTION` / `INFRA_RESTORE`)

Profile의 `infrastructure-targets`에 선언한 대상을 멈추고 되살리는 제어면.

---

## 5. 요약 — sideProject에 지금 추가할 것

| 항목 | 필요한 이유 | 없으면 |
|---|---|---|
| `X-ARL-Trial` → 스팬 속성 필터 | 트레이스 귀속 | 시간축 불변식 전부 판정 불가 |

나머지(`/harness/state`, 인증 역할 분리, 리셋)는 이미 있는 것으로 안다.
확인이 필요하면 Profile을 등록해 보면 된다 — 검증기가 빠진 것을 이름으로 지적한다.
