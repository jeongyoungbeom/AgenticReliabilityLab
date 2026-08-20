# HANDOFF — 다음 세션 인수인계

작성: 2026-08-20 / Phase 17 체크포인트의 기준 부모 커밋: `658f6e4`

---

## 1. 한 줄 요약

Phase 17(선언형 명세 수직 슬라이스)은 **완료**됐다.
JSON 명세 등록 → 검증 → 사람 승인 → 멱등 실행 → 관측 → 결정적 판정 → reset 검증 → 결과 저장까지 연결됐고,
동시성·멱등성·정합성 세 명세가 같은 엔진에서 코드 변경 없이 모두 `PASSED`로 완주했다.

---

## 2. 지금 상태

최종 검증: `.\gradlew.bat clean check bootJar` 통과 — **222 tests, failures 0, errors 0, skipped 22**.
skip은 Docker가 없을 때 비활성화되는 PostgreSQL Testcontainers 계열이다. H2 계약은 전부 실행됐다.
프론트엔드는 Windows에서 `npm ci`, **46 tests**, `tsc`, `vite build`까지 통과했다.

| 영역 | 완료된 것 |
|---|---|
| 형식 | JSON Schema, 엄격 parser, Profile 기반 의미 검증 |
| 실행 | setup, CALL/WAIT workload, 동시 발사, API/응답 관측 |
| 판정 | CEL 샌드박스, 불변식·예외·선행조건, trial 집계 |
| 안전 | LOCAL/TEST 쓰기 제한, 읽기 전용 observation, 경로·auth·헤더 이중 검증, 실행 상한, 검증된 reset 전 재실행 차단 |
| 영속화 | V23 명세/run/verdict/reset 저장 + V24 Target별 단일 활성 실행 슬롯과 재시작 복구 |
| API | 명세 등록·승인·조회, 멱등 run 생성·실행·조회 |
| 완료 기준 | 동시성 3 trials, 멱등성 2 trials, 정합성 2 trials가 같은 엔진에서 모두 `PASSED` |

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

Phase 18(`/harness/state`, Prometheus)로 이동한다.

1. `/harness/state` 관측 클라이언트와 capability 협상
2. Prometheus query 관측 소스
3. 관측 실패 국소화 — 못 읽은 값에 딸린 불변식만 `NOT_EVALUATED`
4. `/state` 없는 Target에서도 나머지 불변식이 정상 판정되는 완료 기준 검증

그 다음: Phase 19(트레이스) → 20(LLM 제안) → 21(장애 주입) → 22(되먹임).
Phase 20 완료 조건은 **"규칙 생성기가 못 찾은 유효한 테스트를 LLM이 1개 이상 찾을 것"**. 못 하면 넣지 않는다.

파일럿 타겟은 `\\wsl.localhost\Ubuntu\home\jybeomss\sideProject` (eventful-commerce). **맨 마지막에** 붙인다.

---

## 4. Phase 17에서 확정한 설계 결정

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

---

## 7. 체크포인트 포함 범위

- Phase 17 설계·명세: `DESIGN3.md`, `TEST_SPEC.md`, JSON Schema, Profile 예시
- 백엔드: 명세 등록·승인·실행·조회 API, 검증기, Runner, 관측·CEL 판정·reset, auth provider, H2/PostgreSQL 영속화와 V23/V24 migration
- 회귀 검증: Phase 17 완료 기준, 안전 경계, 재시작 복구, PostgreSQL 계약 테스트
- 기존 미커밋 프런트엔드: Phase 11–15 Workbench 화면/API 모듈과 46개 테스트
- 문서: 현재 구현 범위와 다음 Phase를 반영한 `README.md`, 이 인수인계 문서

이 범위는 하나의 Phase 17 체크포인트 커밋으로 묶는다. PostgreSQL Testcontainers 22개는 Docker Desktop을 켠 환경에서 다시 실행해야 한다.
