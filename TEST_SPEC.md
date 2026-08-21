# 선언형 테스트 명세 — v1.0

**LLM이 생성하고, 사람이 승인하고, 엔진이 결정적으로 실행하는** 테스트 명세의 형식이다.
사람이 직접 쓰기 위한 설정 파일이 아니다. 화면에서는 자연어로 풀어 보여주고, 이 형식은 내부 표현으로만 쓴다.

**이 문서의 예시가 곧 LLM에게 주는 본보기다.** 예시에 모순이 있으면 LLM이 그 모순을 베낀다.

**정식 형식은 JSON이다.** 이 문서의 예시는 읽기 쉬우라고 YAML로 적었을 뿐이고,
저장·전송·LLM 생성은 모두 JSON Schema로 강제되는 JSON으로 한다. 이유는 1.1절에 있다.

대상 예시는 `eventful-commerce`(sideProject)이며 실제 API 계약에 맞췄다.
**아직 다른 프로젝트로 검증하지 않았다.** 그 전까지 이 형식은 잠정이다.

---

## 0. 설계 기준

| 기준 | 이유 |
|---|---|
| 관측은 소스에 독립적 | 같은 값을 API로 읽든 `/state`로 읽든 트레이스로 읽든 불변식은 안 바뀐다 |
| 워크로드는 실행 방식에 독립적 | 외부 API로 돌리든 Harness로 돌리든 명세는 같다 |
| 프로젝트 고유값은 Profile 참조로 | 명세에 역할명·필드명·리셋 방법을 박지 않는다 |
| 명세 안의 값도 참조로 | 재고를 20으로 바꿔도 불변식이 안 깨져야 한다 |
| 한 단어는 한 뜻 | LLM이 헷갈릴 여지를 남기지 않는다 |
| 읽기와 판정은 분리 | 언제 읽을지와 그 값이 맞는지는 다른 문제다 |
| 선택지는 닫혀 있다 | LLM이 없는 걸 지어내면 검증기가 잡는다 |
| 위험한 상태는 엔진이 되돌린다 | 장애 주입·인프라 변경은 명세가 빠뜨려도 자동 복구 |
| 모르는 것은 통과가 아니다 | 관측 못 했으면 판정 불가로 남긴다 |

---

## 1. 용어

| 용어 | 뜻 | 어디에 |
|---|---|---|
| **요청수** | 워크로드가 보낼 요청의 **총 개수** | 워크로드 단계 |
| **동시성** | 그중 **한 번에 몇 개씩** 보낼지 | 워크로드 단계 |
| **시행** | 이 테스트 **전체를 몇 번** 돌릴지 | 실행정책 |

`요청수: 100, 동시성: 10` = 100건을 10개씩 나눠 보낸다.
`요청수: 10, 동시성: 10` = 10건을 한꺼번에 보낸다.

`반복`이라는 단어는 두 뜻으로 읽히므로 쓰지 않는다.

### 1.1 정식 형식은 JSON

| | |
|---|---|
| **LLM 생성** | JSON Schema로 구조화 출력을 강제한다. 스키마를 어기면 모델이 다시 시도한다 |
| **검증** | 구조·타입·필수값 검사가 스키마로 자동화된다. 검증기는 의미 규칙만 맡는다 |
| **저장·전송** | JSON |
| **사람** | 명세 원문을 읽지 않는다. 화면에서 자연어로 풀어 보여준다 |

사람이 원문을 읽지 않으므로 YAML의 유일한 장점인 가독성은 선택 기준이 아니다.
Phase 5 분석 경로가 이미 JSON 강제 + 엄격 검증기 패턴을 쓰고 있어 일관성도 맞는다.

JSON에서는 구조 키를 영문으로 쓴다. 이 문서의 한글 키와의 대응은 다음과 같다.

| 문서(YAML) | JSON 키 |
|---|---|
| 준비 / 워크로드 / 관측 / 불변식 | `setup` / `workload` / `observations` / `invariants` |
| 이름 / 식 / 조건 / 설명 | `name` / `expr` / `condition` / `description` |
| 요청수 / 동시성 / 시행 | `requestCount` / `concurrency` / `trials` |
| 읽기시점 / 선행조건 / 예외 | `readAt` / `requires` / `exceptions` |
| 실행정책 / 정리 / 근거 | `policy` / `cleanup` / `evidence` |

### 참조 문법

| 표기 | 가리키는 것 |
|---|---|
| `{{준비.product.stock}}` | 준비 단계에 쓴 값 |
| `{{준비.product.productId}}` | 준비 단계가 저장한 값 |
| `{{워크로드.orders.요청수}}` | 워크로드 설정값 |
| `{{워크로드.orders.시작시각}}` / `.종료시각` | 엔진이 기록한 실행 구간 |
| `{{실행ID}}` / `{{요청번호}}` / `{{시행번호}}` | 엔진이 채우는 값 |

호출 경로·헤더·본문에서는 실행 중 생기는 캡처와 `runId`, `trialNumber`, `requestNumber`를 참조할 수 있다.
반면 **불변식 조건**은 승인 시점에 확정돼야 하므로 실행 전에 알 수 있는 값만 참조한다.
현재 허용되는 정적 값은 `policy.trials`, workload의 `requestCount`·`concurrency`, setup 본문의 최상위
스칼라 필드다. 캡처값이나 요청번호처럼 시행마다 달라지는 값을 조건에서 참조하면 검증기가 거부한다.

Profile의 `/products/{id}`는 **허용 경로 템플릿**이고, 실행 명세는
`/products/{{setup.product.productId}}`처럼 `{{...}}` 참조를 써야 한다. 치환된 값은 승인된 한 경로 세그먼트만
채울 수 있다. 쿼리·fragment·`..`·인코딩된 슬래시로 다른 경로를 만드는 값은 전송 직전에 다시 거부한다.

명세 헤더에는 자격증명을 직접 넣지 않는다. `Authorization`·토큰·쿠키 계열은 `authProfile`로만 공급하고,
`Host`·`Content-Length`·`X-ARL-Run-Id` 등 Runner가 관리하는 헤더는 명세가 덮어쓸 수 없다.

### 응답 접근

HTTP 메타데이터와 본문 필드를 반드시 가른다.
`OrderResponse`에 `status` 필드가 실제로 있어서, 안 가르면 HTTP 상태 코드와 충돌한다.

| 표기 | |
|---|---|
| `응답.상태코드` | HTTP 상태 |
| `응답.본문.orderId` | 응답 본문 필드 |
| `응답.소요시간` | 밀리초 |

---

## 2. 표현식 — 관측 경로와 CEL 판정

관측의 `expr`과 불변식의 `condition`은 역할과 실패 의미가 달라 별도 문법을 쓴다.

- **관측 `expr`**: 응답에서 값을 꺼내는 제한된 경로 문법.
  예: `response.body.stock`, `sum(orders[*].body.items[*].quantity)`
- **불변식 `condition`**: 이름 붙은 관측값을 판정하는 CEL(Common Expression Language).
  예: `remainingStock >= 0`, `acceptedQuantity + remainingStock == 10`

관측 경로가 없으면 위반이 아니라 `OBSERVATION_MISSING`이다. CEL은 관측값을 성공적으로 읽은 뒤에만
판정에 사용하며, 평가 환경에 등록한 기능만 존재한다. CEL 매크로(`all`, `exists`, `map`, `filter`)는 차단한다.

**식별자는 ASCII만 쓴다.** CEL 식별자 규칙(`[_a-zA-Z][_a-zA-Z0-9]*`)에 한글이 들어가지 않는다.
표시용 한글은 `설명`에 둔다.

```yaml
관측:
  - 이름: successQuantity          # 식에서 쓰는 식별자. ASCII
    설명: 성공한 주문 수량           # 화면에 보여줄 이름
```

아래는 평가 환경에 등록하는 함수다. **여기 없는 것은 쓸 수 없다.**

### 집계

| 함수 | 뜻 |
|---|---|
| `sum(경로)` | 합 |
| `count(경로)` | 개수 |
| `max(경로)` / `min(경로)` | 최대 / 최소 |
| `avg(경로)` | 평균 |

집계 함수 이름은 영문 그대로다. 명세의 키워드는 한국어지만 식 안은 CEL이고, 여기만 번역하면
평가기가 모르는 이름이 된다. `sum`·`min`·`avg`가 빈 목록을 만나면 판정 불가이지 0이 아니다 —
`count`만 빈 목록에서 0을 돌려준다.

트레이스에는 별도의 `구간목록` 함수를 두지 않았다. `TRACE` 소스의 field 자체가 스팬 목록을 돌려주며,
각 스팬은 `{traceId, name, startMs, endMs, durationMs}` 형태다. 목록을 만드는 것은 관측이고
그 목록을 판정하는 것은 아래 시간축 함수라는 구분은 그대로다.

### 관측 메타 — 값에 딸린 정보

**관측의 성공 여부를 식으로 묻지 않는다.** 값을 읽지 못했거나 제한 시간 안에 안정되지 않으면
엔진이 그 관측을 미관측으로 표시하고, 그 관측을 참조하는 불변식을 자동으로 `NOT_EVALUATED`로 내린다.
`observed(...)`나 `converged(...)` 같은 함수를 두면 같은 판정을 두 곳에서 하게 되고, 명세 작성자가
그 함수를 빠뜨렸을 때 조용히 통과가 나온다. 안정 조건은 관측의 `읽기시점`에서 선언한다.

### 비교·논리

CEL 기본 연산자를 쓴다. `==` `!=` `>` `>=` `<` `<=` `&&` `||` `!` `in`

**CEL 기본 기능 중 쓰지 않는 것:** 매크로(`all`, `exists`, `map`, `filter`)는 평가 환경에서 뺀다.
표현력이 늘면 LLM이 검증하기 어려운 식을 만들고, 사람이 승인 화면에서 읽기도 어려워진다.

### 시간축

스팬은 **`traceId`로 짝짓는다.** 위치나 시각으로 짝지으면 트레이스가 주장한 적 없는 관계를 지어내게 된다.

| 함수 | 뜻 |
|---|---|
| `noOverlap(spansA, spansB)` | 한 트레이스가 자기 A와 B 사이에 있는 동안 다른 트레이스가 A를 시작하지 않았는가 |
| `ordered(spansA, spansB)` | 모든 B에 같은 트레이스의 A가 있고 그 A가 더 늦게 시작하지 않았는가 |
| `maxStartLagMs(spansA, spansB)` | 한 트레이스의 A 시작과 B 시작 사이의 가장 큰 간격(ms) |
| `traceCount(spans)` | 관측이 담고 있는 서로 다른 트레이스의 수 |

**같은 밀리초의 시작은 끼어든 것으로 센다.** 두 요청이 같은 시각에 같은 값을 읽는 것이 바로 찾으려는
경쟁 상태이지 봐줄 우연이 아니다.

**한 트레이스 안의 재시도는 구간을 하나 더 연다.** 예약을 두 번, 차감을 두 번 한 트레이스는 임계 구간이
둘이다. 각 A는 그 뒤의 첫 B와 짝지어진다. 트레이스마다 가장 이른 시각만 쓰면 검사 구간이 첫 시도로
줄어들고, 재시도 중에 일어난 끼어듦을 놓친다 — 경쟁이 가장 일어나기 쉬운 곳이 바로 거기다.

**판정을 거부하는 자리는 다음과 같다.** 전부 "결함을 못 봤다"와 "결함이 없는 것을 봤다"를 구분하기 위한
것이다. 계측이 없거나 뒤처진 Target이 아무도 측정하지 않은 속성에 깨끗한 통과를 받는 일을 막는다.

| 상황 | 함수 | 이유 |
|---|---|---|
| 짝지을 트레이스가 하나도 없음 | 셋 다 | 아무것도 비교하지 않았다 |
| 트레이스가 하나뿐임 | `noOverlap` | 끼어듦은 두 트레이스에 대한 질문이다. 하나로는 답할 수 없다 |
| A는 있는데 B가 없는 트레이스 | `maxStartLagMs` | 완료된 것만 재고 그걸 답이라고 하면, 차감이 아예 없는 쪽이 통과가 된다 |

`ordered`에서 A 없이 B만 있는 트레이스는 무시가 아니라 **위반**이다. 선행 단계가 기록되지 않았는데
후속 단계가 끝난 것은 늦은 것보다 강한 발견이다. 이 규칙은 쿼리가 `${trial}`로 좁혀져 있다는 것에
기대고 있다 — 남의 트레이스가 섞여 들어오면 같은 모양이 되기 때문이다. 그래서 자리 표시자를 Profile
검증에서 강제한다.

### 읽기시점의 안정 판정

| 값 | 뜻 |
|---|---|
| `연속2회_동일` | 같은 값이 두 번 연속 읽히면 안정 |
| `연속3회_동일` | |
| `즉시` | 기다리지 않고 한 번 읽는다 (동기 값에 쓴다) |

---

## 3. Profile이 선언하는 것

명세는 여기 선언된 이름만 쓸 수 있다. 없는 걸 참조하면 검증기가 거부한다.

```yaml
인증프로필:
  - 이름: 상품등록권한
    방식: BEARER
    시크릿참조: arl/sideproject/seller-token    # 값이 아니라 참조. Runner에서만 해석
  - 이름: 구매권한
    방식: BEARER
    시크릿참조: arl/sideproject/buyer-token

관측소스:
  - 이름: harness
    종류: HARNESS_STATE
    주소: /harness/state
    제공필드: [dbStock, redisStock, redisHoldCount, orderCount, paymentCount, settlementAmount]
  - 이름: traces
    종류: TRACE
    주소: http://tempo:3200

장애주입:
  지원: [PAYMENT_FAILURE, EVENT_PUBLISH_FAILURE, EVENT_CONSUME_DELAY]
  최대TTL: 300s

인프라제어:
  지원: [중지, 재시작, 네트워크지연]
  대상: [payment-service, settlement-service, shipping-service]

정리:
  방법: 환경리셋
  명령참조: arl/sideproject/reset-hook
  예상소요: 120s

한계:
  최대동시성: 50
  최대요청수: 1000
  최대시행: 100
```

### 실행 Profile 형식

현재 구현은 `test-spec-execution.observation-sources`에 다음 영문 키를 사용한다.

```yaml
observation-sources:
  - name: harness
    kind: HARNESS_STATE
    endpoint: /harness/state
    fields: [dbStock, redisStock, redisHoldCount, orderCount]
    auth-profile: seller                    # 선택
  - name: metrics
    kind: PROMETHEUS
    endpoint: http://prometheus:9090
    fields: [httpErrorRate]
    queries:
      httpErrorRate: 'sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))'
  - name: traces                            # Phase 19
    kind: TRACE
    endpoint: http://tempo:3200
    fields: [reserveSpans, deductSpans]
    queries:
      reserveSpans: '{name="inventory.reserve"}'
      deductSpans: '{name="db.query" && span.db.table="products"}'
```

명세의 `DECLARED_SOURCE` 관측은 `sourceName`과 `expr`에 각각 Profile의 source 이름과 field 이름만 쓴다.
PromQL과 TraceQL은 Profile만 소유하며 명세 문서에서 직접 전달할 수 없다. 쿼리는 그 자체가 텔레메트리
저장소에 대한 실행 권한이므로, 모델이 쓴 자리가 아니라 사람이 승인한 자리에 있어야 한다.

`HARNESS_STATE` endpoint는 다음 읽기 전용 계약을 반환한다. `fields`는 런타임 capability이고,
Profile 허용 목록과 이 목록의 교집합만 실제로 읽힌다. 같은 source에서 여러 field를 관측하면 한 응답의
snapshot에서 함께 추출한다. 따라서 같은 Harness source의 관측들은 `readAt`의 rule, maxWait, interval을
동일하게 선언해야 한다.

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

endpoint 부재, 비정상 HTTP 상태, 계약 불일치, 필드 누락, Prometheus 빈/다중 series는 그 관측값만
`not observed`로 만든다. 그 값을 참조하는 불변식은 `NOT_EVALUATED`이고 다른 불변식은 계속 판정한다.

판정 불가에는 사유가 함께 남는다. 특히 **`OBSERVATION_INSUFFICIENT`와 `EXPRESSION_FAILED`는 다르다.**
전자는 관측을 읽기는 했지만 판정을 받칠 만큼이 아니었다는 뜻이고(짝지을 트레이스가 없음, 트레이스가
하나뿐, 차감에 도달하지 못한 예약이 있음), 후자는 식 자체가 평가되지 않았다는 뜻이다. 둘을 같은
사유로 묶으면 운영자가 **맞는 명세를 고치러 간다.** 시간축 함수가 거부한 이유 문장은 그대로 verdict의
`detail`에 남는다 — "no trace carries both spans"는 수집기를 보라는 말이고, "3 traces started the first
span without ever reaching the second"는 코드를 보라는 말이라 서로 다른 곳을 가리킨다.
settling 중 실패한 read는 연속 동일 횟수를 초기화하며, 요청 timeout과 polling sleep 모두 Runner가 허용한
남은 observation deadline을 넘을 수 없다.
외부 Prometheus·Tempo 주소는 Target Profile의 CIDR allowlist를 그대로 통과해야 한다.

`TRACE` 소스는 Tempo의 `GET /api/search`를 호출하고 `traces[].spanSets[].spans[]`을 읽는다.
`spanSet` 단수형 응답도 함께 받아들인다. 시각 범위는 명세가 아니라 **엔진이 정한다.** 시행의
워크로드 구간에서 시작해 **관측을 읽는 시점**에서 끝나는 창을 쿼리에 넣고, 창 밖에서 시작한 스팬은
응답에서 다시 걸러낸다. 창이 워크로드 종료가 아니라 읽는 시점에서 끝나는 이유는, 관측이 트레이스
저장소가 따라잡기를 최대 1분까지 기다리기 때문이다. 워크로드 직후에 닫는 창은 기다려서 얻은 스팬을
정확히 다시 버리게 되고, 그것도 늦은 정도에 비례해서 버린다 — 전파가 느려질수록 더 준수해 보이게 된다.
준비 단계는 창에서 뺀다. 준비도 Target을 호출하고 계측된 Target은 그 스팬을 남기지만, 그것은 판정
대상이 아니다.

### 트레이스는 Target이 계측해야 쓸 수 있다

시간 창만으로는 부족하다. 스팬 이름만으로 맞추는 쿼리는 다른 개발자의 요청, 이전 시행, 그리고
**이 시행 자신의 준비 단계**에도 걸린다. 준비의 `POST /products`가 `{name="db.query" &&
span.db.table="products"}`에 걸리는 것은 가정이 아니라 샘플 Profile에 실제로 있던 일이다. 그렇게 들어온
트레이스는 짝의 한쪽만 가지고 있어서, 아무도 저지르지 않은 위반으로 보고된다.

그래서 Target이 협조해야 한다. ARL은 **판정 대상인 워크로드 요청에만** `X-ARL-Trial` 헤더를 보내고,
트레이스 기반 불변식을 쓰려는 Target은 그 값을 스팬 속성으로 남긴다. 준비 단계에는 이 헤더가 붙지
않는다 — 준비도 같은 run과 같은 시행에 속하므로, run 단위로만 좁히면 준비가 "시작만 하고 끝내지 않은
워크로드 요청"처럼 보인다.

Profile의 TraceQL은 `${trial}` 자리 표시자를 **반드시** 포함해야 하고, 엔진이 질의 직전에 이 시행의
식별자로 채운다. 명세는 이 문자열에 닿지 못하므로 치환이 Profile이 허용한 범위를 넓히지 않는다.
자리 표시자가 없는 TRACE 쿼리는 Profile 검증에서 거부된다. 사람이 승인하는 것은 Profile이고,
명세가 스코프를 **넣을 수 없다면 뺄 수도 없어야** 하기 때문이다.

이것은 `HARNESS_STATE`가 `/harness/state`를 요구하는 것과 같은 거래다. 소스 종류는 Target이 자기 몫을
했을 때 쓸 수 있다.

### 덜 읽은 것은 다 읽은 것이 아니다

트레이스 저장소는 요청한 만큼만 돌려주고 무엇을 뺐는지는 말하지 않는다. 그래서 엔진은 `limit`과
`spss`를 **명시적으로** 보내고, 응답이 그 수에 닿으면 잘린 것으로 보아 관측을 미관측으로 내린다.
Tempo가 `matched`로 "맞은 것은 더 많다"고 말한 span set도 마찬가지다. 상한을 스스로 정해야 거기에
닿았다는 사실이 의미를 갖는다. 요청 200건 중 50건만 보고 "경쟁이 없었다"고 말하는 것이 이 도구가
낼 수 있는 최악의 답이다.

부분 인제스트는 이것만으로 막을 수 없다. 저장소가 아직 3개만 보여주고 두 번 연속 같은 값을 주면
안정 판정이 나기 때문이다. 그래서 **완전성은 명세가 단언한다** — `traceCount(reserveSpans) ==
{{워크로드.orders.요청수}}`처럼. 몇 개가 있어야 하는지는 엔진이 알 수 없고 워크로드를 선언한 명세가 안다.

빈 스팬 목록은 실패가 아니라 **관측된 결과**다. 그 침묵이 괜찮은지는 판정의 문제이며, 시간축 함수가
빈 시간축을 통과가 아니라 판정 불가로 처리한다.

---

## 4. 동시성 — 동시 주문 시 초과 판매

```yaml
id: stock-oversell-concurrent
버전: 1
title: 동시 주문 시 초과 판매가 발생하는가
category: CONCURRENCY
risk: MODERATE
소스: LLM_PROPOSED
프로파일버전: pv-2026-08-19-a                    # 이 명세가 검증된 Profile 버전
근거:
  - 출처: OPENAPI
    위치: paths./orders.post.description
    원문: "재고 부족 상품은 주문에서 제외되고 failedItems에 반환됩니다"

준비:
  - 이름: product
    프로토콜: HTTP
    호출: POST /products
    인증: 상품등록권한
    본문:
      name: "arl-test-{{실행ID}}-{{시행번호}}"    # 시행마다 다른 상품 → 시행끼리 간섭 없음
      stock: 10
      price: 1000
    저장: productId ← 응답.본문.id

워크로드:
  - 이름: orders
    프로토콜: HTTP
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-{{요청번호}}" }
    본문:
      items: [{ productId: "{{준비.product.productId}}", quantity: 1 }]
    요청수: 10
    동시성: 10
    저장: responses

관측:
  - 이름: dbStock
    소스: API
    호출: GET /products/{{준비.product.productId}}
    식: 응답.본문.stock

  - 이름: redisHold
    소스: harness
    식: redisHoldCount

  - 이름: successQuantity
    소스: RESPONSES
    식: sum(responses[*].본문.sellerOrders[*].items[*].quantity)

  - 이름: failedItemCount
    소스: RESPONSES
    식: count(responses[*].본문.failedItems[*])

불변식:
  - id: stock-never-negative
    설명: 재고는 0 미만이 될 수 없다
    조건: dbStock >= 0

  - id: stock-matches-success
    설명: 성공한 주문 수량만큼만 재고가 줄어야 한다
    조건: dbStock == {{준비.product.stock}} - successQuantity

  - id: all-requests-accounted
    설명: 보낸 요청은 성공이거나 실패이거나 둘 중 하나여야 한다
    조건: successQuantity + failedItemCount == {{워크로드.orders.요청수}}

  - id: no-dangling-hold
    설명: 테스트가 끝나면 예약이 남아 있으면 안 된다
    조건: redisHold == 0
    # /state 없는 Target에서는 redisHold가 미관측이 되고, 엔진이 이 불변식을 판정 불가로 내린다

실행정책:
  시행: 20
  판정: 한번이라도_위반하면_위반
  중단: 첫_위반에서_중단
  정리시점: 전체후

정리: 환경리셋
```

---

## 5. 멱등성 — 같은 키로 두 번 보내기

```yaml
id: order-idempotency-same-key
버전: 2                                          # 예외가 추가되어 버전이 올랐다
title: 같은 멱등키로 두 번 주문하면 재고가 한 번만 줄어드는가
category: IDEMPOTENCY
risk: MODERATE
소스: LLM_PROPOSED
프로파일버전: pv-2026-08-19-a
근거:
  - 출처: OPENAPI
    위치: paths./orders.post.parameters.Idempotency-Key
    원문: "A client-generated key reused when retrying the same order request."

준비:
  - 이름: product
    호출: POST /products
    인증: 상품등록권한
    본문: { name: "arl-test-{{실행ID}}-{{시행번호}}", stock: 10, price: 1000 }
    저장: productId ← 응답.본문.id

워크로드:
  - 이름: 첫번째
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-고정" }
    본문: { items: [{ productId: "{{준비.product.productId}}", quantity: 2 }] }
    요청수: 1
    저장: first

  - 대기: 1s

  - 이름: 두번째
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-고정" }   # 같은 키
    본문: { items: [{ productId: "{{준비.product.productId}}", quantity: 2 }] }
    요청수: 1
    저장: second

관측:
  - 이름: dbStock
    소스: API
    호출: GET /products/{{준비.product.productId}}
    식: 응답.본문.stock
  - 이름: orderedQuantity
    소스: RESPONSES
    식: sum(first.본문.sellerOrders[*].items[*].quantity)
  - 이름: firstOrderId
    소스: RESPONSES
    식: first.본문.orderId
  - 이름: secondOrderId
    소스: RESPONSES
    식: second.본문.orderId
  - 이름: secondStatusCode
    소스: RESPONSES
    식: second.상태코드                          # 본문의 status 필드와 구분된다

불변식:
  - id: stock-deducted-once
    설명: 같은 키의 재요청은 재고를 다시 줄이지 않는다
    조건: dbStock == {{준비.product.stock}} - orderedQuantity

  - id: same-order-returned
    설명: 재요청은 새 주문을 만들지 않고 같은 주문을 돌려준다
    조건: firstOrderId == secondOrderId
    예외:
      - 조건: secondStatusCode == 409
        설명: 처리 중인 같은 키에 409를 주는 것도 규약상 허용된다
        근거: paths./orders.post.responses.409
        추가경위: 오판신고                        # 실행 결과를 보고 사람이 추가했다
        승인자: jybeomss
        승인시각: 2026-08-19T14:20:00Z

실행정책: { 시행: 1, 정리시점: 전체후 }
정리: 환경리셋
```

---

## 6. 정합성 — 비동기 전파 후 수렴

```yaml
id: order-settlement-consistency
버전: 1
title: 주문이 결제·정산까지 어긋남 없이 전파되는가
category: CONSISTENCY
risk: MODERATE
소스: LLM_PROPOSED
프로파일버전: pv-2026-08-19-a
근거:
  - 출처: README
    위치: 아키텍처
    원문: "주문 이벤트는 Kafka를 통해 결제·정산·배송 서비스로 전파된다"

준비:
  - 이름: product
    호출: POST /products
    인증: 상품등록권한
    본문: { name: "arl-test-{{실행ID}}-{{시행번호}}", stock: 100, price: 1000 }
    저장: productId ← 응답.본문.id

워크로드:
  - 이름: orders
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-{{요청번호}}" }
    본문: { items: [{ productId: "{{준비.product.productId}}", quantity: 1 }] }
    요청수: 5
    동시성: 1
    저장: responses

관측:
  # 읽기 시점만 정한다. 그 값이 맞는지는 불변식이 판정한다.
  - 이름: settlementTotal
    소스: harness
    식: settlementAmount
    읽기시점:
      방식: 안정될때까지
      최대대기: 10s
      근거: 없음                                 # 문서에 없는 값. 승인 화면에서 강조
      간격: 500ms
      안정판정: 연속2회_동일

  - 이름: observedPaymentCount
    소스: harness
    식: paymentCount
    읽기시점: { 방식: 안정될때까지, 최대대기: 10s, 근거: 없음, 간격: 500ms, 안정판정: 연속2회_동일 }

  - 이름: orderSettlementTotal
    소스: RESPONSES
    식: sum(responses[*].본문.totalSettlementAmount)

불변식:
  # 전파가 10초 안에 안정되지 않으면 두 관측이 미관측이 되고, 아래 두 불변식은
  # 엔진이 판정 불가로 내린다. "아직 전파 중"이 위반으로 보고되지 않는다.
  - id: settlement-matches-order
    설명: 정산 서비스가 집계한 금액이 주문이 계산한 금액과 같아야 한다
    조건: settlementTotal == orderSettlementTotal

  - id: payment-per-order
    설명: 주문 수만큼 결제가 생성되어야 한다
    조건: observedPaymentCount == {{워크로드.orders.요청수}}

실행정책: { 시행: 3, 정리시점: 전체후 }
정리: 환경리셋
```

---

## 7. 재시도·복구 — 장애 주입

```yaml
id: payment-failure-stock-recovery
버전: 1
title: 결제가 실패하면 예약 재고가 복구되는가
category: RETRY_RECOVERY
risk: MODERATE
소스: LLM_PROPOSED
프로파일버전: pv-2026-08-19-a
근거:
  - 출처: README
    위치: 사가
    원문: "결제 실패 시 보상 트랜잭션으로 예약 재고를 해제한다"

준비:
  - 이름: product
    호출: POST /products
    인증: 상품등록권한
    본문: { name: "arl-test-{{실행ID}}-{{시행번호}}", stock: 10, price: 1000 }
    저장: productId ← 응답.본문.id

워크로드:
  - 이름: 결제장애
    주입:
      종류: PAYMENT_FAILURE
      범위: 다음1건
      TTL: 60s                                   # Target이 자동 만료. 엔진이 죽어도 풀린다
    저장: faultId

  - 이름: 주문
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-1" }
    본문: { items: [{ productId: "{{준비.product.productId}}", quantity: 3 }] }
    요청수: 1
    저장: order

  - 해제: "{{워크로드.결제장애.faultId}}"          # 선택. 안 써도 엔진이 실행 끝에 전부 해제

관측:
  - 이름: finalStock
    소스: API
    호출: GET /products/{{준비.product.productId}}
    식: 응답.본문.stock
    읽기시점: { 방식: 안정될때까지, 최대대기: 15s, 근거: 없음, 간격: 500ms, 안정판정: 연속2회_동일 }

  - 이름: remainingHold
    소스: harness
    식: redisHoldCount
    읽기시점: { 방식: 안정될때까지, 최대대기: 15s, 근거: 없음, 간격: 500ms, 안정판정: 연속2회_동일 }

  - 이름: orderStatus
    소스: RESPONSES
    식: order.본문.status

불변식:
  # 보상 처리가 15초 안에 안정되지 않으면 finalStock이 미관측이 되고,
  # 아래 재고 불변식은 판정 불가로 내려간다.
  - id: stock-restored
    설명: 결제가 실패했으면 재고가 원래대로 돌아와야 한다
    조건: finalStock == {{준비.product.stock}}
    선행조건: recovery-settled

  - id: no-orphan-hold
    설명: 실패한 주문의 예약이 남아 있으면 안 된다
    조건: remainingHold == 0
    선행조건: recovery-settled

  - id: order-marked-failed
    설명: 주문이 실패 상태로 기록되어야 한다
    조건: orderStatus in ["FAILED", "CANCELLED"]

실행정책: { 시행: 3, 정리시점: 시행마다 }          # 장애 주입은 전역 상태를 건드린다
정리: 환경리셋
```

**장애 주입의 안전 규칙 — 엔진이 강제한다**

1. **TTL 필수.** Target이 자동 만료시킨다. ARL이 죽어도 풀린다
2. **엔진이 finally로 전 주입 해제.** 명세가 `해제`를 빠뜨려도 실행 종료 시 모두 해제
3. **다음 실행 전 확인.** 활성 장애가 남아 있으면 **실행 거부**

정리 미확인 시 다음 실험을 막는 기존 인터록과 같은 구조다.

---

## 8. 트레이스 관측 — 원인 규명

스냅샷으로는 "재고가 -3"까지만 안다. **왜 그랬는지**는 시간축이 있어야 한다.
4절 동시성 명세의 `관측`에 다음을 더한다.

관측은 Profile이 선언한 source 이름과 field 이름만 쓴다. 스팬 선택자와 시각범위는 명세에 없다.

```yaml
관측:
  - 이름: reserveSpans
    소스: traces                                 # Profile의 TRACE source 이름
    식: reserveSpans                             # Profile의 field 이름. TraceQL은 Profile이 소유
    읽기시점: { 방식: 안정될때까지, 최대대기: 10s, 근거: 없음, 간격: 500ms, 안정판정: 연속2회_동일 }

  - 이름: deductSpans
    소스: traces
    식: deductSpans
    읽기시점: { 방식: 안정될때까지, 최대대기: 10s, 근거: 없음, 간격: 500ms, 안정판정: 연속2회_동일 }

불변식:
  - id: no-overlapping-reservation
    설명: 한 주문의 예약과 차감 사이에 다른 주문의 예약이 끼어들면 안 된다
    조건: noOverlap(reserveSpans, deductSpans)

  - id: deduction-follows-promptly
    설명: 예약 후 DB 반영이 지나치게 늦으면 안 된다
    조건: maxStartLagMs(reserveSpans, deductSpans) <= 100
    근거: 없음

  - id: every-order-traced
    설명: 보낸 주문 수만큼 예약 트레이스가 관측되어야 한다
    조건: traceCount(reserveSpans) == {{워크로드.orders.요청수}}
    미충족시: 판정불가                            # 저장소가 아직 못 따라온 것이지 결함이 아니다
```

### 문서 초안과 달라진 것, 그리고 이유

| 초안 | 구현 | 왜 |
|---|---|---|
| 명세의 `선택.스팬`·`속성` | Profile의 TraceQL | 쿼리는 실행 권한이다. 명세가 임의 쿼리를 보내면 Profile이 경계인 이유가 없어진다 |
| 명세의 `시각범위: {{워크로드...}}` | 엔진이 계산 | 시행마다 달라지는 참조라 조건에서 금지된 값과 충돌한다. 창은 시행이 실제로 돈 구간이다 |
| `식: 구간목록` | `식: <field 이름>` | field가 곧 스팬 목록이다. 별도 함수를 둘 이유가 없다 |
| `max(deductSpans[*].시작 - reserveSpans[*].시작)` | `maxStartLagMs(a, b)` | 두 목록을 원소끼리 빼려면 어느 예약이 어느 차감과 짝인지 정해야 한다. `traceId`가 그 답이다 |
| `선행조건: observed(...)` | 불필요 | 읽지 못한 관측을 참조하는 불변식은 엔진이 이미 `NOT_EVALUATED`로 내린다 |
| `<= 100ms` | `<= 100` | CEL 리터럴에 단위가 없다. 함수가 ms를 돌려주므로 비교값도 ms다 |

**트레이스가 없으면** 이 불변식들은 판정 불가로 내려가고 나머지는 정상 판정된다.
트레이스는 **정밀도를 올리는 선택지**지 전제가 아니다.

---

## 9. 인프라 장애

```yaml
id: payment-service-down-saga
버전: 1
title: 결제 서비스가 죽어도 주문 사가가 보상하는가
category: RETRY_RECOVERY
risk: DESTRUCTIVE                                # 최고 위험도. 승인 수준도 최고
소스: LLM_PROPOSED
프로파일버전: pv-2026-08-19-a

준비:
  - 이름: product
    호출: POST /products
    인증: 상품등록권한
    본문: { name: "arl-test-{{실행ID}}-{{시행번호}}", stock: 10, price: 1000 }
    저장: productId ← 응답.본문.id

워크로드:
  - 이름: 결제중단
    인프라:
      동작: 중지
      대상: payment-service
      최대유지: 60s                              # 넘으면 엔진이 강제 복구
    저장: 제어ID

  - 이름: 주문
    호출: POST /orders
    인증: 구매권한
    헤더: { Idempotency-Key: "{{실행ID}}-{{시행번호}}-1" }
    본문: { items: [{ productId: "{{준비.product.productId}}", quantity: 3 }] }
    요청수: 1
    저장: order

  - 대기: 10s
  - 복구: "{{워크로드.결제중단.제어ID}}"

관측:
  - 이름: finalStock
    소스: API
    호출: GET /products/{{준비.product.productId}}
    식: 응답.본문.stock
    읽기시점: { 방식: 안정될때까지, 최대대기: 30s, 근거: 없음, 간격: 1s, 안정판정: 연속2회_동일 }
  - 이름: remainingHold
    소스: harness
    식: redisHoldCount

불변식:
  # 복구가 제한 시간 안에 안정되지 않으면 finalStock이 미관측이 되고,
  # 아래 재고 불변식은 판정 불가로 내려간다.
  - id: stock-restored-after-outage
    설명: 결제 서비스 복구 후 재고가 원래대로 돌아와야 한다
    조건: finalStock == {{준비.product.stock}}
    선행조건: recovery-settled
  - id: no-orphan-hold-after-outage
    설명: 장애 중 생긴 예약이 남아 있으면 안 된다
    조건: remainingHold == 0
    선행조건: recovery-settled

실행정책: { 시행: 1, 정리시점: 시행마다 }
정리: 환경리셋
```

**인프라 제어의 안전 규칙**

1. `최대유지` 필수. 넘으면 엔진이 강제 복구
2. 엔진이 finally로 전부 복구
3. 다음 실행 전 대상이 정상인지 확인. 아니면 실행 거부
4. **`LOCAL`/`TEST` 환경에서만.** `STAGING`/`PRODUCTION`은 이 기능 자체가 차단

---

## 10. 실행정책

```yaml
실행정책:
  시행: 20
  판정: 한번이라도_위반하면_위반                   # 또는 비율기준
  중단: 첫_위반에서_중단                          # 또는 전부_실행
  정리시점: 전체후                                # 또는 시행마다
  간격: 0s
```

**`정리시점` 고르는 법**

| | 언제 | 비용 |
|---|---|---|
| `전체후` | 시행마다 새 fixture를 만들어 간섭이 없을 때 | 리셋 1회 |
| `시행마다` | 장애 주입·인프라 제어처럼 전역 상태를 건드릴 때 | 리셋 × 시행수 |

sideProject 기준 리셋 1회가 약 120초다. `시행: 20`에 `시행마다`면 40분이다.
가능하면 **시행마다 다른 fixture**를 쓰고 `전체후`로 둔다.

v1 Runner의 하드 상한은 WAIT 한 단계 5분, 시행 간격 1분, 관측 최대 대기 1분, 관측 간격 10초다.
등록 JSON 본문은 256 KiB를 넘을 수 없다. Profile의 요청수·동시성·시행 상한과 함께 더 작은 제한이 적용된다.

결과에는 **몇 번 중 몇 번 깨졌는지**를 남긴다. `20회 중 3회 위반`은 `1회 위반`과 다른 정보다.

---

## 11. 명세 버전 관리

승인된 명세는 회귀 테스트 자산이 된다. 그래서 **언제 다시 승인받아야 하는지**를 정해야 한다.

각 명세는 `버전`과 `프로파일버전`을 갖는다.

### Profile이 바뀌면

엔진이 명세의 모든 참조를 새 Profile에 대해 다시 검증한다.

| 결과 | 처리 |
|---|---|
| 참조가 전부 유효 | `프로파일버전`만 갱신하고 **계속 쓴다.** 재승인 불필요 |
| 참조가 깨짐 (경로·필드·인증프로필이 사라짐) | `SUPERSEDED`. 실행 중지하고 **사용자에게 알린다** |
| 상한이 줄어 명세가 초과 | `SUPERSEDED` |

읽기 전용 점검이 Profile 버전 변경으로 대체되는 기존 규칙과 같은 방향이다.

### 명세 자체가 바뀌면

`버전`이 오른다. 무엇이 바뀌었느냐에 따라 재승인 여부가 갈린다.

| 변경 | 재승인 |
|---|---|
| `예외` 추가 | **필요** — 무엇을 정상으로 인정할지는 사람이 정한다 |
| 불변식 추가·수정 | **필요** |
| 임계값 변경 (`최대대기`, `<= 100ms`) | **필요** |
| `시행` 수 조정 | 불필요 |
| 제목·설명 수정 | 불필요 |

이전 버전의 실행 기록은 남긴다. **판정 기준이 언제 바뀌었는지 모르면 회귀 추적이 안 된다.**

---

## 12. 오판 되먹임

이 도구는 문서에 적힌 것까지만 안다. 문서에 없는 도메인 규칙 때문에
**정상 동작이 위반으로 판정되는 일이 반드시 생긴다.**

되먹임 경로가 없으면 같은 오판이 매번 반복되고, 사용자는 도구를 믿지 않게 된다.

```
결과 화면에서 위반을 보고
        ↓
[이건 정상입니다] 를 누른다
        ↓
왜 정상인지 사용자가 적는다
  "처리 중인 같은 키에는 409를 주는 게 우리 규약"
        ↓
LLM이 예외 조건 초안을 만든다
  조건: secondStatusCode == 409
        ↓
검증기가 거른다
  · 예외가 불변식 전체를 무력화하면 거부
  · 관측되지 않은 값을 참조하면 거부
        ↓
사람이 승인 → 명세 버전이 오르고 예외가 기록된다
  추가경위: 오판신고 / 승인자 / 승인시각
        ↓
다음 실행부터 같은 상황은 통과
```

**예외는 반드시 좁아야 한다.** `조건: true` 같은 예외는 불변식을 없애는 것과 같아서 거부한다.
어떤 예외가 언제 누구에 의해 추가됐는지는 감사 기록으로 남는다.

---

## 13. 프로토콜 확장 자리

지금 구현은 `HTTP`만 지원한다. 형식만 예약해 둔다.

```yaml
워크로드:
  - 프로토콜: HTTP                               # 기본값. 생략 가능
    호출: POST /orders

  # 미구현
  - 프로토콜: KAFKA
    발행: { 토픽: order-events, 본문: {...} }
  - 프로토콜: GRPC
    호출: { 서비스: OrderService, 메서드: CreateOrder, 본문: {...} }
```

**형식이 REST에 묶여 있다.** 메시지 큐 기반이나 배치 시스템에는 지금 형식이 맞지 않는다.

---

## 14. 검증기가 거부하는 것

| 거부 대상 | 왜 |
|---|---|
| 활성 Profile에 없는 경로 호출 | LLM이 endpoint를 지어낼 수 있다 |
| 선언되지 않은 인증프로필 참조 | 권한 범위를 넓힐 수 없다 |
| 선언되지 않은 관측소스·필드 참조 | 없는 데이터를 읽을 수 없다 |
| 지원하지 않는 장애 종류·인프라 대상 | Target이 허용한 것만 |
| TTL·최대유지 누락 또는 상한 초과 | 되돌릴 수 없는 상태를 만들 수 없다 |
| 선언된 상한을 넘는 동시성·요청수·시행 | Profile과 capability 중 **작은 쪽**을 따른다 |
| 정의되지 않은 변수 참조 | `{{준비.X.Y}}`의 X가 없으면 |
| **2절 평가 환경에 등록되지 않은 함수** | CEL 환경에 없으면 컴파일 단계에서 실패한다 |
| **CEL 매크로 사용** | 검증·가독성을 위해 평가 환경에서 제외했다 |
| ASCII가 아닌 식별자 | CEL 식별자 규칙 위반 |
| **JSON Schema 위반** | 구조·타입·필수값은 스키마가 먼저 거른다 |
| `LOCAL`/`TEST`가 아닌 환경에서 장애·인프라 사용 | 안전 경계 |
| API 관측에 `GET`/`HEAD` 이외 메서드 사용 | 관측은 읽기 전용이다 |
| 실제 동작보다 낮은 `risk` 선언 | 상태 변경은 최소 `MODERATE`, 장애·인프라는 `DESTRUCTIVE` |
| 명세에 자격증명·Runner 관리 헤더 직접 선언 | 자격증명은 `authProfile`에서만 해석한다 |
| 비동기 관측에 `읽기시점` 누락 | 비동기 전파를 즉시 읽으면 항상 틀린다 |
| `선행조건`이 가리키는 불변식이 없음 | |
| **불변식을 통째로 무력화하는 예외** | 되먹임이 판정을 지우는 데 쓰이면 안 된다 |

---

## 15. 승인 화면에서 강조해야 할 것

- **`근거: 없음`인 임계값** — LLM이 지어낸 숫자다
- **위험도 `MODERATE` 이상** — 상태를 바꾸거나 장애를 일으킨다
- **장애 주입·인프라 제어 포함 여부** — 무엇이 중지되는지
- **`예외` 항목** — 무엇을 정상으로 인정하기로 했는지, 누가 언제 추가했는지
- **예상 소요 시간** — `시행 × (워크로드 + 정리)`

---

## 16. 남은 것

**다른 프로젝트로 형식 검증.** 아직 sideProject 하나에만 맞춰봤다.
도메인·인증 방식·아키텍처가 다른 프로젝트로 명세를 두어 개 써보기 전까지 이 형식은 잠정이다.

특히 확인이 필요한 것:

- 역할 기반이 아닌 인증(API 키, OAuth 스코프)이 `인증프로필`에 들어가는가
- 단일 서비스(MSA가 아닌) 프로젝트에서 `관측소스`가 과한 구조는 아닌가
- REST가 아닌 인터페이스를 만났을 때 `프로토콜` 자리로 충분한가

**안정되기까지 걸린 시간을 판정할 수 없다.** `읽기시점`은 "10초 안에 안정되지 않으면 미관측"까지
표현하고, 그 경우 해당 불변식은 판정 불가로 내려간다. 하지만 "전파가 10초를 넘었다"를 **위반**으로
보고할 방법은 없다. 지연 자체가 SLO인 경우에는 필요한 구분이다. 관측이 안정되기까지 걸린 시간을
값으로 노출하는 방식이 자연스럽지만, 그러면 불변식이 관측의 메타데이터를 읽게 되어 지금의
"관측은 값을 만들고 불변식은 값을 판정한다"는 경계가 흐려진다. 경계를 어떻게 지킬지 정하기 전까지 두지 않는다.
