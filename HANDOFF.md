# HANDOFF — 다음 세션 인수인계

작성: 2026-08-22 / 기준 커밋: `abafb97` (Phase 18·19 커밋 완료)

---

## 1. 한 줄 요약

Phase 19(트레이스)는 **ARL 쪽이 끝났다.** `DESIGN3.md`가 적어 둔 세 항목 — 트레이스 조회, 시간축 관측,
분석 프롬프트에 트레이스 근거 연결 — 이 모두 구현되고 테스트로 고정됐다.

완료 기준 문장("3·7·9번이 같은 시각에 예약을 읽었고 반영이 340ms 늦었다")을 **눈으로 확인하는 것은
계측된 Target이 있어야 한다.** 그건 의도적으로 맨 뒤로 미뤄 둔 작업이다(`TARGET_REQUIREMENTS.md`).
지금까지 확인된 것은 전부 스텁과 테스트다.

Phase 17의 선언형 명세 엔진에 `/harness/state`·Prometheus에 이어 Tempo trace 관측과
시간축 판정(`noOverlap`/`ordered`/`maxStartLagMs`/`traceCount`)을 붙였다.

---

## 2. 지금 상태

검증: `.\gradlew.bat clean check bootJar` 통과 — **279 tests, failures 0, errors 0, skipped 22**
(`build/test-results/test/TEST-*.xml`에서 직접 셈). 250 → 273(리뷰 대응) → 279(분석 경로)로 늘었고,
증가분은 전부 리뷰가 지적한 결함과 새 계약에 붙인 회귀 테스트다.

| 클래스 | 늘어난 수 | 무엇을 고정했나 |
|---|---|---|
| `SpanTimelineTests` | +6 | 트레이스 1개, 재시도 구간, 짝 없는 예약, 공유 트레이스 없음, `traceCount`, 거부 사유 전달 |
| `TempoSpanParserTests` | +9 (신규) | 잘림 감지, `matched` 불일치, 단수 `spanSet`, 읽을 수 없는 형태, 창 필터 |
| `SpecObservationReaderTests` | +2 | 한 소스의 field를 한 라운드에서, setup을 창에서 제외 |
| `InvariantEvaluatorTests` | +3 | 판정 불가 사유 분리, 진짜 식 오류, 근거 규모 보존 |
| `TestSpecValidatorTests` | +1 | 명세가 시행 귀속 헤더를 설정하지 못함 |
| `TestSpecExecutionProfileMapperTests` | +2 | `${trial}` 없는 TRACE 쿼리 거부 |
| `AnalysisDatasetSpecRunTests` | +6 (신규) | 스팬이 evidence bundle까지 도달, 시행별 근거 id, 미검증 정리 거부 |

`JdbcTestSpecPersistenceTests`는 개수가 그대로지만 단언이 늘었다 — 관측 원본의 왕복과,
새 `observations_json` 컬럼에도 자격증명이 닿지 않는다는 확인이 기존 테스트 안에 들어갔다.
이 테스트가 H2에서 실제로 돌았다는 것은 **V25 마이그레이션이 H2에서 통과했다**는 뜻이기도 하다
(`drop constraint`와 `CASE` 기반 check 제약은 이 저장소에 전례가 없었다).

skip은 Docker가 없을 때 비활성화되는 PostgreSQL Testcontainers 계열이다. 프론트엔드는
Phase 19에서 건드리지 않았다(마지막 확인은 `npm ci`, **46 tests**, `tsc`, `vite build` 통과).

**빌드가 통과했다고 판정이 옳은 것은 아니다.** 이번 Phase가 그 교훈이다 — 250개가 전부 녹색인 상태에서
독립 리뷰가 통과 판정을 조용히 틀리게 만드는 결함 4개를 찾았다. 시간축 테스트 10개가 전부
"트레이스당 스팬 1개, 양쪽 트레이스 집합 동일"이라는 가장 쉬운 입력만 쓰고 있었기 때문이다.

CEL 커스텀 함수 등록은 `SpanTimelineTests`의
`evaluates the time axis functions through the expression environment`가 실제 컴파일·평가까지
돌려서 확인했다. 등록되지 않은 함수 이름을 거부하는 것도 같은 클래스에서 확인한다.

| 영역 | 완료된 것 |
|---|---|
| 형식 | JSON Schema, 엄격 parser, Profile 기반 의미 검증 |
| 실행 | setup, CALL/WAIT workload, 동시 발사, API/응답 관측 |
| 판정 | CEL 샌드박스, 불변식·예외·선행조건, trial 집계 |
| 안전 | LOCAL/TEST 쓰기 제한, 읽기 전용 observation, 경로·auth·헤더 이중 검증, 실행 상한, 검증된 reset 전 재실행 차단 |
| 영속화 | V23 명세/run/verdict/reset 저장 + V24 Target별 단일 활성 실행 슬롯과 재시작 복구 |
| API | 명세 등록·승인·조회, 멱등 run 생성·실행·조회 |
| 완료 기준 | 동시성 3 trials, 멱등성 2 trials, 정합성 2 trials가 같은 엔진에서 모두 `PASSED` |
| Phase 18 관측 | `HARNESS_STATE_V1`, Profile 소유 PromQL, source별 auth, 실패한 값만 `NOT_EVALUATED` |
| Phase 19 트레이스 | Profile 소유 TraceQL, 엔진이 정하는 조회 창, `traceId` 짝짓기, 시행 귀속, 잘림 감지, 빈 시간축은 판정 불가 |

완료 기준 테스트는 `Phase17CompletionIntegrationTests`다. 테스트용 로컬 HTTP Target에 실제로 주문 6회,
동일 키 결제 4회, 이체 2회를 보내고 총 7회의 환경 reset과 reset 검증을 수행한다.

독립 리뷰 뒤 다음 음성 계약도 회귀 테스트로 고정했다.

- API observation의 POST/PUT/PATCH/DELETE 거부 및 Runner의 PRODUCTION 이중 차단
- Profile `/products/{id}`와 명세 `/products/{{setup.product.productId}}` 매칭, 치환 후 query·traversal·인코딩 슬래시 거부
- 경로별 auth profile 강제, 자격증명·Runner 관리 헤더의 명세 직접 선언 거부
- 실제 동작에서 최소 risk 도출: 상태 변경은 `MODERATE`, 장애·인프라는 `DESTRUCTIVE`
- 동시 요청 실패 시 남은 요청을 취소하고 실제 종료까지 기다린 후 reset
- Target별 PENDING/RUNNING/RECOVERY_REQUIRED 단일 활성 슬롯, 재시작 시 PENDING 실패 처리·RUNNING 복구 필요 처리
- WAIT·시행/관측 간격 상한과 256 KiB 등록 본문 제한

---

## 3. 다음 작업

**(1) 커밋 완료.** Phase 18·19 전체가 `abafb97`로 커밋됐다.

**(2) Phase 20(LLM 제안).** 완료 기준은 `DESIGN3.md` 기준
"규칙 생성기가 못 찾은 유효한 테스트를 LLM이 1개 이상 찾을 것"이고, 못 찾으면 넣지 않는다.

Target 작업과 UI는 **여기 있는 항목이 아니다.** 둘 다 의도적으로 맨 뒤로 미뤘고
각각 `TARGET_REQUIREMENTS.md`와 `UI_BACKLOG.md`에 모여 있다. Phase 19의 완료 기준
("3·7·9번이 같은 시각에 예약을 읽었고 반영이 340ms 늦었다")을 **실제 Target으로 눈으로 확인하는
것은 그때 처음 가능해진다.** 그전까지는 스텁과 단위 테스트로만 확인된 상태다.

전체 순서: Phase 20(LLM 제안) → 21(장애 주입) → 22(되먹임).

나중으로 미뤄 둔 것은 문서 두 개에 모아 뒀다. 해당 시점에 열어 보면 된다.

- `TARGET_REQUIREMENTS.md` — Target(sideProject)이 갖춰야 할 것. 맨 마지막에 붙인다.
- `UI_BACKLOG.md` — 화면. 명세 엔진(Phase 17~19)에는 아직 UI가 하나도 없다.

파일럿 타겟은 `\\wsl.localhost\Ubuntu\home\jybeomss\sideProject` (eventful-commerce). **맨 마지막에** 붙인다.

---

## 4. Phase 17–19에서 확정한 설계 결정

### Phase 19 트레이스

- **TraceQL은 Profile이 소유한다.** Prometheus와 같은 이유다. 쿼리는 텔레메트리 저장소에 대한 실행
  권한이고, 명세는 모델이 쓰는 문서다. 덕분에 `test-spec.schema.json`·파서·검증기는 **한 줄도 안 바뀌었다.**
  명세는 `source: DECLARED_SOURCE`, `sourceName: traces`, `expr: <field 이름>`을 쓸 뿐이다.
- **TraceQL의 시행 스코프는 엔진이 채운다.** Profile 쿼리는 `${trial}` 자리 표시자를 반드시 포함해야
  하고(없으면 Profile 검증에서 거부), 엔진이 질의 직전에 이 시행의 식별자로 치환한다. ARL은 그 값을
  **워크로드 요청에만** `X-ARL-Trial`로 보내고, Target이 스팬 속성으로 남긴다.
  준비 단계에 안 붙이는 이유: 준비도 같은 run·같은 시행이라, run 단위로만 좁히면 fixture 생성이
  "시작만 하고 끝내지 않은 워크로드 요청"으로 보인다. `x-arl-trial`은 Runner 관리 헤더에 넣어
  명세가 직접 설정하지 못하게 막았다 — 이 헤더를 명세가 쓸 수 있으면 귀속 전체가 무의미해진다.
- **조회 시각 범위는 엔진이 정한다.** 워크로드 구간에서 시작해 **관측을 읽는 시점**에서 끝난다.
  준비 단계는 뺀다. 창이 워크로드 종료에서 끝나면, 저장소를 최대 1분까지 기다려 얻은 스팬을 정확히
  다시 버리게 되고 그것도 늦은 정도에 비례해서 버린다 — 전파가 느려질수록 더 준수해 보인다.
  `TEST_SPEC.md` 초안의 `시각범위: {{워크로드.orders.시작시각}}`은 시행마다 달라지는 참조라
  조건에서 그런 참조를 금지한 규칙과 충돌한다. 그래서 명세가 아니라 엔진이 소유한다.
- **스팬은 `traceId`로 짝짓고, 재시도는 구간을 하나 더 연다.** 각 A는 그 뒤의 첫 B와 짝지어진다.
  트레이스마다 가장 이른 시각만 쓰면 검사 구간이 첫 시도로 줄어들어, 재시도 중의 끼어듦을 놓친다 —
  경쟁이 가장 일어나기 쉬운 자리가 거기다.
- **판정을 거부하는 자리가 셋이다.** 짝지을 트레이스가 하나도 없을 때(셋 다), 트레이스가 하나뿐일
  때(`noOverlap` — 끼어듦은 두 트레이스에 대한 질문이다), A는 있는데 B가 없는 트레이스가 있을
  때(`maxStartLagMs` — 완료된 것만 재고 그걸 답이라 하면 차감이 아예 없는 쪽이 통과가 된다).
- **덜 읽은 것은 다 읽은 것이 아니다.** `limit`과 `spss`를 명시적으로 보내고, 응답이 그 수에 닿거나
  Tempo가 `matched`로 더 있다고 말하면 잘린 것으로 보아 미관측으로 내린다. 상한을 스스로 정해야
  거기 닿았다는 사실이 의미를 갖는다.
- **완전성은 명세가 단언한다.** `traceCount(reserveSpans) == {{워크로드.orders.요청수}}`.
  부분 인제스트는 settling으로 못 막는다 — 저장소가 3개만 보여주며 두 번 연속 같은 값을 주면
  안정 판정이 나기 때문이다. 몇 개가 있어야 하는지는 엔진이 알 수 없고 워크로드를 선언한 명세가 안다.
  엔진에 "크게 못 미치면" 같은 임계값을 넣지 않은 것은 의도다. 설명할 수 없는 판정은 만들지 않는다.
- **한 소스의 모든 field는 한 라운드에서 읽는다.** HARNESS_STATE에만 있던 계약을 모든 선언 소스로
  넓혔고, 검증기가 공유 읽기시점을 강제한다. field마다 따로 settling하면 예약 목록과 차감 목록이
  다른 순간을 가리키고, 그 사이에 도착한 트레이스가 한쪽에만 있어 위반으로 보고된다.
- **같은 밀리초의 시작은 끼어든 것으로 센다.** 두 요청이 같은 시각에 같은 값을 읽는 것이 찾으려는
  경쟁 상태 그 자체다.
- Tempo 응답은 `traces[].spanSets[].spans[]`을 읽고 단수형 `spanSet`도 받는다. **인식 못 하는 형태는
  빈 목록이 아니라 미관측이다** — 파서가 저장소 형식보다 뒤처졌을 때 조용한 통과가 되지 않게.
- `TEST_SPEC.md` 8절에 초안과 구현이 달라진 항목과 이유를 표로 정리해 뒀다.

### Phase 19 분석 경로

- **명세 run은 dataset만 가리킨다.** `analysis_run`·`analysis_comparison`·`follow_up_suggestion`과 그
  DTO들이 전부 `experiment_run_id` / `target_test_batch_id` 두 소스로 분기한다. 세 번째 컬럼을 그대로
  추가하면 같은 비정규화가 그 전부로 번지는데, `analysis_run.analysis_dataset_id`는 V6부터 있었고
  dataset이 원래 진짜 입력이다. 그래서 명세 run의 멱등성은 `(analysis_dataset_id, idempotency_key)`가
  답한다(`AnalysisRunStore.findByDatasetAndIdempotencyKey`).
- **관측 원본을 trial 기록에 남긴다.** `verdicts_json`의 `observedValues`는 5개까지만 렌더한 요약이라
  6개째부터의 스팬이 사라졌다. 개선 제안은 증거를 근거로 추론해야지 증거에 관한 문장을 근거로 할 수
  없다. 크기를 넘으면 값을 버리되 **버렸다는 사실을 기록한다**(`ObservedEvidence.omitted`) — 조용히
  비어 있는 기록은 증거가 원래 적었던 기록과 구분되지 않는다.
- **정리가 검증되지 않은 run은 분석하지 않는다.** Target이 아무도 확인하지 않은 상태로 남았다는 뜻이고,
  그 다음 run의 관측이 이 run의 잔여물을 설명할 수 있다. 거기서 원인을 추론하는 것은 추론하지 않느니만
  못하다. 반면 `INCONCLUSIVE`는 통과시킨다 — "판정할 수 없었다"는 개선 제안이 봐야 할 발견이다.
- **분석 경로가 볼 수 있는 것은 어댑터가 정한다.** `TestSpecRunEvidenceSource`가 완료된 run과 그 시행만
  노출한다. `TestSpecRunStore`에 인터페이스를 직접 구현시키면 실행 슬롯·복구 장부·멱등성 조회까지 전부
  분석 쪽에 딸려 나가고, 같은 읽기에 두 번째 이름이 생긴다.

### Phase 18 관측 소스

- `HARNESS_STATE`는 상대 경로만 허용한다. 응답은 `contractVersion: HARNESS_STATE_V1`, `fields`, `state`를
  제공하고 Profile 허용 필드와 runtime `fields`의 교집합만 읽는다. 같은 source의 여러 field는 한 snapshot에서
  추출하며 동일한 `readAt` timing을 사용한다.
- `PROMETHEUS`는 Profile에 절대 base URL과 field별 PromQL을 둔다. 명세는 field 이름만 쓸 수 있다.
- Prometheus 주소는 Target과 같은 CIDR allowlist를 통과하고 source의 `auth-profile`도 Runner에서만 해석한다.
- 404, 계약 불일치, 필드 누락, 빈/다중 series는 run 실패가 아니라 해당 `ObservedValue.missing`이다.
- polling 실패는 연속 안정 횟수를 끊고, 요청과 sleep은 남은 observation deadline을 넘지 않는다.
- 완료 기준은 실제 로컬 Target의 `/harness/state`가 404일 때 API 기반 불변식 `PASSED`, source 의존
  불변식 `NOT_EVALUATED`, run `INCONCLUSIVE`를 동시에 검증한다.

### 불변식 조건의 `{{...}}` 참조

`조건: successQuantity + failedItemCount == {{워크로드.orders.요청수}}` 는 CEL에서 그대로는 안 돌아간다.
그래서 **실행 전에 알 수 있는 값만** 조건에 쓸 수 있게 했다.

- 정적 바인딩: `policy.trials`, `workload.<이름>.requestCount`, `workload.<이름>.concurrency`,
  `setup.<이름>.<본문의 최상위 스칼라 필드>` (단, 그 값 자체가 참조면 제외)
- 시행마다 달라지는 값(`setup.product.productId` 같은 캡처, `requestNumber`)을 조건에 쓰면 **검증기가 거부**한다.
  승인이 첫 시행에만 유효해지는 걸 막기 위해서다.
- 판정 결과의 `condition`에는 **치환된 형태**가 남는다 (`dbStock == 10 - successQuantity`).
  자리표시자가 남은 조건은 운영자가 검산할 수 없다.

### 관측식은 CEL이 아니다

- **불변식 조건** = CEL (관측 id들에 대해)
- **관측식** = 자체 경로 문법 (`response.body.stock`, `sum(responses[*].body.items[*].quantity)`)

둘을 섞지 않는 이유: 응답에서 값을 꺼내는 것과 그 값을 판정하는 것은 실패 방식이 다르다.
없는 경로는 "위반"이 아니라 **"읽지 못했다"** 로 나와야 한다.

집계 규칙:
- `sum`/`count`가 0건을 만나면 **0** — "성공한 주문이 하나도 없음"은 진짜 있는 결과다
- `max`/`min`/`avg`가 0건을 만나면 **에러** — 답이 없는데 지어내지 않는다
- 응답 본문은 **필요할 때만** 파싱한다. 실패한 요청이 HTML을 돌려줘도 같은 시행의 다른 관측을 오염시키지 않는다

### 인증

`SpecAuthProvider` 포트 + `EnvironmentSpecAuthProvider` 구현.
환경변수 `ARL_SPEC_AUTH_<타겟>_<프로필>` (헤더 이름은 `..._HEADER`, 기본 `Authorization`).
값은 요청 헤더로만 나가고 **에러 메시지에도 안 들어간다**.

### 리셋

Target에 레코드 단위 삭제를 요구하지 않는다. **환경 리셋 + 리셋 검증**.
검증 없는 리셋은 `verified = false`로 처리한다 — 확인 안 된 리셋은 *다음* 실행의 판정을 망가뜨리기 때문에
경고가 아니라 차단 사유다.

---

## 5. 절대 어기면 안 되는 것

- **자격증명·비밀번호·토큰·DB 접속 문자열은 문서·Profile·프롬프트·Evidence 어디에도 넣지 않는다.**
  참조 ID만 DB에 남고 값은 Runner 환경에서만 해석된다.
- **ARL은 Target 코드를 고치지 않는다. PR도, 배포도, 프로덕션 변경도 없다.**
- 상태 변경·부하·장애 테스트는 **`LOCAL`/`TEST`에서만**. `TestSpecRunner.requireSafeEnvironment`가 마지막 방어선.
- **LLM 출력은 가설이지 사실이 아니다. 합격/불합격은 절대 LLM이 정하지 않는다.**
- 되돌릴 수 없는 상태를 만들 수 없다 — TTL·최대유지 없는 장애·인프라 조작은 거부.
- 예외(`exceptions`)가 불변식을 무력화할 수 없다.
- **빈 관측도, 덜 읽은 관측도 통과로 만들지 않는다.** 계측이 없거나 뒤처진 Target이 아무도 측정하지
  않은 속성에 깨끗한 통과를 받는 것이 이 도구가 낼 수 있는 가장 나쁜 답이다. 완전 부재만이 아니라
  **부분 부재**도 막아야 한다 — 실전에서 압도적으로 흔한 쪽이 그것이다.
- **귀속할 수 없는 트레이스로 판정하지 않는다.** 남의 요청이나 준비 단계로 위반을 보고하면,
  틀린 통과만큼은 아니어도 판정 자체를 무의미하게 만든다.

---

## 6. 개발 환경에서 걸렸던 것들

| | |
|---|---|
| **Jackson 3 `JsonNode.map()`** | Kotlin `Iterable.map` 확장을 가린다. `.values().map { }` / `.values().filter { }` 를 써라. `properties()` 아니고 `propertyNames()` |
| **CEL 식별자** | ASCII만 (`[_a-zA-Z][_a-zA-Z0-9]*`). 한글은 `label`/`설명`에 둔다. 매크로(`all`/`exists`/`map`/`filter`)는 `setStandardMacros()`를 **안 부르는 것**으로 차단 |
| **ArchUnit** | `..application..` 은 `..infrastructure..`·`..api..`·`..http..` 에 의존 못 한다. 설정값은 `application/port`에 인터페이스, `infrastructure`에 `@ConfigurationProperties` 구현 (`FollowUpSuggestionProperties` 패턴) |
| **detekt** | 2.0.0-alpha.3 + baseline. 기본 설정이라 줄 120자, `ReturnCount` 2, `ThrowsCount` 2. 필요하면 `@Suppress`에 **왜인지 주석**을 붙여라 |
| **Spring + 함수형 생성자** | `EnvironmentSpecAuthProvider(private val environment: (String) -> String?)` 는 Spring이 못 채운다. `@Autowired constructor() : this(System::getenv)` 로 무인자 생성자를 하나 더 둔다 |
| **`vite.config.ts`** | `defineConfig` 를 `'vitest/config'` 에서 가져와야 한다. `'vite'` 에서 가져오면 `test` 키를 거부하는데 **`npm run build`에서만** 터진다 |
| **빌드 결과 확인** | `BUILD SUCCESSFUL` 을 믿지 말 것. `build/test-results/test/TEST-*.xml` 에서 `tests=` / `skipped=` 를 직접 세라. up-to-date로 안 돌고 지나간 적 있다 |
| **CEL 커스텀 함수 (0.9.1)** | 이 버전의 binding은 **중첩 타입** `CelRuntime.CelFunctionBinding`이다. top-level `dev.cel.runtime.CelFunctionBinding`은 더 최신 버전에만 있다. 선언은 `dev.cel.common.CelFunctionDecl.newFunctionDeclaration(name, CelOverloadDecl.newGlobalOverload(overloadId, resultType, paramTypes...))`, 컴파일러는 `addFunctionDeclarations`, 런타임은 `addFunctionBindings`. 바인딩 인자 타입은 `List::class.java`면 되고, CEL이 넘겨주는 값은 그대로 `List<Map<String, Any>>`로 읽힌다 |
| **detekt `ReturnCount`** | 한도가 2다. `?: return null`을 두 번 쓰면 바로 걸린다. `?.let { }` 체인으로 바꾸는 편이 `@Suppress`보다 낫다 — 실제로 반환 경로가 하나로 줄어드는 자리가 대부분이다 |
| **detekt는 컴파일보다 먼저 돈다** | `check`에서 detekt가 실패하면 `compileKotlin`이 아예 실행되지 않는다. detekt만 고쳐 놓고 "빌드가 되는구나" 하면 안 된다 |
| **detekt `TooManyFunctions`** | 인터페이스 한도가 11이다. 다른 모듈의 포트를 저장소 인터페이스에 **직접 구현시키면** 금방 넘는다. 별도 어댑터로 빼는 편이 억제보다 낫다 — 그쪽이 "이 경로가 볼 수 있는 것"을 한 곳에서 말해 주기도 한다 |
| **H2는 부분 인덱스를 모른다** | `create index ... where ...`는 PostgreSQL 전용이다. 테스트는 `MODE=PostgreSQL`인 H2에서 도는데 이건 지원 범위 밖이다. `alter table drop constraint`와 `CASE` 기반 check 제약은 H2에서도 통과한다(V25에서 확인) |

---

## 7. Git 상태와 변경 범위

- Phase 17 설계·명세: `DESIGN3.md`, `TEST_SPEC.md`, JSON Schema, Profile 예시
- 백엔드: 명세 등록·승인·실행·조회 API, 검증기, Runner, 관측·CEL 판정·reset, auth provider, H2/PostgreSQL 영속화와 V23/V24 migration
- 회귀 검증: Phase 17 완료 기준, 안전 경계, 재시작 복구, PostgreSQL 계약 테스트
- 프런트엔드: Phase 11–15 Workbench 화면/API 모듈과 46개 테스트
- 문서: 현재 구현 범위와 다음 Phase를 반영한 `README.md`, 이 인수인계 문서

위 Phase 17 범위는 `1b939c8`에, Phase 18(observation source Profile 계약, HTTP reader, 완료 기준 테스트)과
Phase 19 범위는 `abafb97`에 커밋됐다.

Phase 19가 건드린 파일:

- 도메인: `SpanObservation.kt`(신규 — `ObservedSpan`, `ObservationWindow`, `TraceScope`, `TraceReadLimits`),
  `SpecExecutionModels.kt`(`StepRole`), `InvariantJudgement.kt`(`ObservedEvidence`,
  `OBSERVATION_INSUFFICIENT`), `TestSpecPersistenceModels.kt`
- 판정: `SpanTimeline.kt`(신규), `SpecExpressionEnvironment.kt`(CEL 함수 등록, 거부 사유 보존),
  `InvariantEvaluator.kt`
- 관측: `DeclaredObservationSourceClient.kt`(포트가 `DeclaredObservationRequest`를 받도록 변경),
  `SpecObservationReader.kt`, `HttpDeclaredObservationSourceClient.kt`(TRACE 분기),
  `TempoSpanParser.kt`(신규), `SpecHttpCaller.kt`·`SpecWorkloadExecutor.kt`·`SpecRequestPolicy.kt`(시행 귀속)
- Profile: `TargetProfileModels.kt`, `TestSpecExecutionProfileValidator.kt`, `TargetSpecCapabilities.kt`,
  `TestSpecValidator.kt`
- 분석 경로: `V25` migration, `AnalysisDatasetService.kt`, `SingleReliabilityAgent.kt`,
  `MultiReliabilityAgent.kt`, `AnalysisEvidenceSources.kt`, `AnalysisDatasetStore.kt`,
  `AnalysisRunStore.kt`, `AnalysisModels.kt`, 관련 Jdbc 어댑터와 SQL,
  `TestSpecRunEvidenceSource.kt`(신규), `JdbcTestSpecRunRepository.kt`, `TestSpecRunSql.kt`
- 테스트: `SpanTimelineTests.kt`·`TempoSpanParserTests.kt`·`AnalysisDatasetSpecRunTests.kt`(신규),
  `HttpDeclaredObservationSourceClientTests.kt`, `SpecObservationReaderTests.kt`,
  `InvariantEvaluatorTests.kt`, `TestSpecValidatorTests.kt`, `TestSpecExecutionProfileMapperTests.kt`,
  `JdbcTestSpecPersistenceTests.kt`, `TestSpecRunnerTests.kt`, `SpecExecutionFixtures.kt`
- 문서: `README.md`, `TEST_SPEC.md`, `target-profile.sample.yaml`,
  `TARGET_REQUIREMENTS.md`(신규), `UI_BACKLOG.md`(신규), 이 문서

포트 시그니처가 바뀌었으므로(`read(request)`) 다른 구현이나 fake가 더 있으면 함께 고쳐야 한다.
PostgreSQL Testcontainers는 Docker Desktop을 켠 환경에서 다시 실행해야 한다.
