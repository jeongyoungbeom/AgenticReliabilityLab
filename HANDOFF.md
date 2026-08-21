# HANDOFF — 다음 세션 인수인계

작성: 2026-08-21 / 기준 커밋: `082b4ec` (Phase 20 커밋 완료)

---

## 1. 한 줄 요약

Phase 20(LLM 제안)은 **ARL 쪽이 끝났다.** `DESIGN3.md`가 적어 둔 완료 기준 — "규칙 생성기가
못 찾은 유효한 테스트를 LLM이 1개 이상 찾을 것" — 을 만족하는 경로가 구현되고 테스트로 고정됐다.
새 규칙 기반 생성기를 따로 만들지 않고 Phase 12의 `TestCandidateService`를 그대로 재사용해서
"그 목록에 없던 유효한 테스트를 LLM이 찾는가"만 답하도록 범위를 좁혔다(설계 재검토 결과 채택한 B안).

Phase 19까지와 마찬가지로, **빌드가 통과했다고 판정이 옳은 것은 아니다.** 이번에도 독립 리뷰가
녹색 빌드 뒤에서 결함 3건을 찾았다 — 한 후보의 실패가 같은 run 안의 다른(이미 커밋된) 후보 기록을
지우는 문제, Idempotency-Key 재사용이 Knowledge Snapshot을 검증하지 않는 문제, title/specKey
길이를 저장 전에 검증하지 않는 문제. 셋 다 고치고 회귀 테스트로 고정한 뒤에 커밋했다.

---

## 2. 지금 상태

검증: `.\gradlew.bat clean check` 통과 — **283 tests, failures 0, errors 0, skipped 22**
(`build/test-results/test/TEST-*.xml`에서 직접 셈). Phase 19 종료 시점 279개에서 +4 —
전부 `TestSpecGenerationApiIntegrationTests`에 있고, 그중 2개는 독립 리뷰가 찾은 결함의
회귀 테스트다.

| 테스트 | 무엇을 고정했나 |
|---|---|
| `records every proposal and promotes only the one the validator accepts` | 채택 1건 + 거부 1건, 채택된 것만 `test_specification`에 실제로 저장됨 |
| `returns the same run for a repeated idempotency key without calling the model again` | 같은 Idempotency-Key 재요청이 모델을 다시 호출하지 않고 같은 run을 반환 |
| `completes the run and keeps the valid candidate when another candidate is too long to store` (신규, 리뷰 대응) | title이 저장 컬럼 폭을 넘는 후보가 있어도 run은 COMPLETED로 끝나고, 같은 run의 다른(유효한) 후보는 실제로 저장됨 |
| `rejects reusing an idempotency key against a different Knowledge Snapshot` (신규, 리뷰 대응) | 같은 키를 다른 스냅샷으로 재사용하면 409(`TEST_SPEC_GENERATION_IDEMPOTENCY_CONFLICT`) |

독립 리뷰(제 코드를 처음 보는 별도 에이전트 + 직접 코드 대조 검증)가 찾고 고친 결함 3건:

- **부분 커밋 감사기록 유실.** `toCandidate()`가 `SpecParseException`/`SpecValidationException`/
  `ClientRequestException` 세 타입만 잡아서, `specificationService.create()`에서 나올 수 있는
  다른 예외(예: DB 제약 위반)가 후보 순회 루프를 뚫고 나가면 run 전체가 FAILED로 끝나고
  `store.complete()`(run/candidate 레코드를 쓰는 유일한 지점)가 아예 실행되지 않았다. 그런데
  같은 run의 앞선 후보가 이미 `specificationService.create()`로 실제 커밋된 `test_specification`
  row라면? 그 row는 DB에 남는데 어떤 generation run이 그걸 제안했는지 기록이 사라진다.
  범용 catch를 마지막에 추가해서 어떤 후보가 어떤 이유로 실패하든 해당 후보만 REJECTED로
  기록되고 run은 항상 COMPLETED로 끝나게 고쳤다.
- **Idempotency-Key가 Knowledge Snapshot을 검증하지 않음.** `configurationHash`가 모델/프롬프트
  버전만 해시하고 `knowledgeSnapshotId`는 빠져 있었다. 같은 키를 새 스냅샷으로 재요청하면 에러
  없이 예전 run을 그대로 돌려줬다 — `TestSpecificationService.execute()`의 `ensureSameRunRequest`가
  참조 엔티티 id를 해시에 포함시켜 막는 것과 같은 종류의 문제를 Phase 20만 놓쳤다.
  `knowledgeSnapshotId`를 해시에 넣어서 불일치 시 409를 던지도록 고쳤다.
- **title/specKey 길이 미검증.** `rejectionReason`은 저장 전에 방어적으로 truncate하는데
  `title`/`specKey`는 그대로 저장을 시도했다. `test_specification`/`test_spec_generation_candidate`
  둘 다 `varchar(500)`/`varchar(200)` 제한이 있고 `TestSpecParser`/`TestSpecValidator` 어디에도
  길이 검증이 없어서, 악의적이지 않은 평범한 모델 출력만으로도 위 첫 번째 결함이 트리거됐다.
  `specificationService.create()` 호출 전에 길이를 확인해서 넘으면 명확한 이유로 거부하도록
  고쳤다.

detekt(2.0.0-alpha.3, baseline만 있고 커스텀 설정 없음)는 새 파일 기준으로 5건을 잡았고 전부
기존 관례를 그대로 따라 고쳤다 — `MatchingDeclarationName`(단일 최상위 선언 파일명 불일치 →
파일명 변경), `TooGenericExceptionCaught`/`SwallowedException`(원본 예외를 실제로 `cause`로
체이닝), `ThrowsCount`(`@Suppress` + 이유 주석). 컴파일 경고 하나(Elvis 연산자가 항상 좌변만
반환)는 `FollowUpSuggestionService`의 동일 패턴(`?: fallback` 없음)과 맞춰 제거했다. `when`
분기 누락(`JobExecutionCapacity`가 새 `OutboxJobType.TEST_SPEC_GENERATION`을 놓침)도 하나
있었다 — 같은 enum에 대한 다른 exhaustive `when`이 더 있는지 grep으로 확인했고 여기가 유일했다.

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
| Phase 20 LLM 제안 | 규칙 기반 목록 재사용(신규 생성기 없음), 비동기(202+폴링) Ollama 제안, 채택/거부 전량 기록, 기존 검증기 게이트 재사용, 원본 문서는 비영속·매 요청 프롬프트 컨텍스트 |

완료 기준 테스트는 `Phase17CompletionIntegrationTests`다(Phase 17~19 범위, 변경 없음).
Phase 20 자체의 완료 기준은 `TestSpecGenerationApiIntegrationTests`의 첫 테스트로 고정했다 —
가짜 모델이 규칙 기반 목록에 없는 유효한 후보 1개를 제안하면 실제로 승격되어 저장된다.

프론트엔드는 Phase 20에서 건드리지 않았다(마지막 확인은 `npm ci`, **46 tests**, `tsc`,
`vite build` 통과 — Phase 19 기준).

---

## 3. 다음 작업

**(1) 커밋 완료.** Phase 20 전체가 `082b4ec`로 커밋됐다.

**(2) Phase 21(장애 주입).** `DESIGN3.md` 기준 다음 단계.

Target 작업과 UI는 **여기 있는 항목이 아니다.** 둘 다 의도적으로 맨 뒤로 미뤘고
각각 `TARGET_REQUIREMENTS.md`와 `UI_BACKLOG.md`에 모여 있다. Phase 19의 완료 기준
("3·7·9번이 같은 시각에 예약을 읽었고 반영이 340ms 늦었다")을 **실제 Target으로 눈으로 확인하는
것은 그때 처음 가능해진다.** 그전까지는 스텁과 단위 테스트로만 확인된 상태다.

전체 순서: Phase 20(LLM 제안, 완료) → **21(장애 주입)** → 22(되먹임).

나중으로 미뤄 둔 것은 문서 두 개에 모아 뒀다. 해당 시점에 열어 보면 된다.

- `TARGET_REQUIREMENTS.md` — Target(sideProject)이 갖춰야 할 것. 맨 마지막에 붙인다.
- `UI_BACKLOG.md` — 화면. 명세 엔진(Phase 17~20)에는 아직 UI가 하나도 없다.

파일럿 타겟은 `\\wsl.localhost\Ubuntu\home\jybeomss\sideProject` (eventful-commerce). **맨 마지막에** 붙인다.

---

## 4. Phase 20에서 확정한 설계 결정

- **새 규칙 기반 생성기를 만들지 않는다.** Phase 12의 `TestCandidateService`가 이미 같은
  Knowledge Snapshot에서 규칙 기반 후보 목록을 만든다. Phase 20이 답해야 할 질문은 "그 목록이
  놓친, 유효하고 실행 가능한 테스트를 LLM이 찾는가" 하나뿐이다 — 처음에 새 파서·필드 역할 추론
  로직을 짜려던 접근(A안)은 재검토 끝에 폐기했고, 규칙 기반 목록 재사용(B안)으로 확정했다.
- **원본 OpenAPI 문서는 저장하지 않고 매 요청마다 프롬프트 컨텍스트로만 쓴다.** 영속화된
  Knowledge Snapshot은 의도적으로 필드 단위 스키마를 갖지 않는다(불변성 유지). 문서를 매번
  새로 받는 것이 모델이 request/response 필드 이름을 실제 근거에 접지시키는 유일한 방법이고,
  생략해도 run은 생기되 대부분 거부될 뿐이다.
- **모델의 모든 제안(채택·거부 불문)을 기록한다.** 거부된 제안도 `document`와 `rejectionReason`을
  포함해 응답에 남긴다 — 리뷰어가 모델이 실제로 뭘 시도했는지 볼 수 있는 유일한 자리이기 때문이다.
- **채택은 기존 검증기 게이트를 그대로 통과해야 한다.** `TestSpecificationService.create()`를
  한 줄도 바꾸지 않고 그대로 호출한다(source: `MODEL_PROPOSED`). LLM 출력은 가설이지 사실이
  아니라는 원칙(5절)을 지키는 유일한 방법은 다른 명세와 똑같은 문 하나만 통과시키는 것이다.
- **오케스트레이션은 비동기(202+폴링)다.** Ollama 응답이 느리거나 HTTP 타임아웃이 우려되는
  경로라서, `FollowUpSuggestionService`가 이미 쓰는 idempotency-key 멱등성 + outbox job
  claim/complete/fail + 재시작 시 `recoverIncompleteRuns()` 패턴을 그대로 따랐다. 새 오케스트
  레이션 관례를 만들지 않았다.
- **`JobExecutionCapacity`에서는 분석(analysis) permits 그룹이다.** 이 job은 살아있는 Target을
  건드리지 않고 LLM 호출과 기존 검증기를 통한 DB 쓰기만 한다 — `FOLLOW_UP_SUGGESTION`과 같은
  분류다.
- **완료 기준을 만족 못 하면 아무것도 붙이지 않는다.** 모델이 규칙 기반 목록에 이미 있는 것만
  제안하거나 전부 거부되면, run은 COMPLETED로 끝나되 승격된 명세는 0개다 — 이것도 정상 종료다.

---

## 5. 절대 어기면 안 되는 것

- **자격증명·비밀번호·토큰·DB 접속 문자열은 문서·Profile·프롬프트·Evidence 어디에도 넣지 않는다.**
  참조 ID만 DB에 남고 값은 Runner 환경에서만 해석된다.
- **ARL은 Target 코드를 고치지 않는다. PR도, 배포도, 프로덕션 변경도 없다.**
- 상태 변경·부하·장애 테스트는 **`LOCAL`/`TEST`에서만**. `TestSpecRunner.requireSafeEnvironment`가 마지막 방어선.
- **LLM 출력은 가설이지 사실이 아니다. 합격/불합격은 절대 LLM이 정하지 않는다.** Phase 20에서도
  모델이 제안한 명세는 기존 `TestSpecValidator`를 그대로 통과해야만 저장되고, 모델 스스로 자신의
  제안을 승인하는 경로는 없다.
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
| **detekt `MatchingDeclarationName`** | 파일에 최상위 선언이 하나뿐이면 파일명이 그 선언 이름과 정확히 같아야 한다. 여러 선언이 있는 `*Commands.kt` 묶음 파일은 안 걸리지만, 하나만 있으면 걸린다 — `@Suppress`보다 파일명을 선언 이름으로 바꾸는 게 관례에 맞다 |
| **detekt `SwallowedException`** | catch한 예외의 `.message`/`.javaClass.simpleName`만 새 메시지에 문자열로 넣는 것으로는 안 풀린다. 새로 던지는 예외 생성자에 `cause: Throwable? = null`을 두고 실제로 `cause = exception`을 넘겨야 스택트레이스가 안 사라진다 |
| **Spring + 함수형 생성자** | `EnvironmentSpecAuthProvider(private val environment: (String) -> String?)` 는 Spring이 못 채운다. `@Autowired constructor() : this(System::getenv)` 로 무인자 생성자를 하나 더 둔다 |
| **`vite.config.ts`** | `defineConfig` 를 `'vitest/config'` 에서 가져와야 한다. `'vite'` 에서 가져오면 `test` 키를 거부하는데 **`npm run build`에서만** 터진다 |
| **빌드 결과 확인** | `BUILD SUCCESSFUL` 을 믿지 말 것. `build/test-results/test/TEST-*.xml` 에서 `tests=` / `skipped=` 를 직접 세라. up-to-date로 안 돌고 지나간 적 있다 |
| **CEL 커스텀 함수 (0.9.1)** | 이 버전의 binding은 **중첩 타입** `CelRuntime.CelFunctionBinding`이다. top-level `dev.cel.runtime.CelFunctionBinding`은 더 최신 버전에만 있다. 선언은 `dev.cel.common.CelFunctionDecl.newFunctionDeclaration(name, CelOverloadDecl.newGlobalOverload(overloadId, resultType, paramTypes...))`, 컴파일러는 `addFunctionDeclarations`, 런타임은 `addFunctionBindings`. 바인딩 인자 타입은 `List::class.java`면 되고, CEL이 넘겨주는 값은 그대로 `List<Map<String, Any>>`로 읽힌다 |
| **detekt `ReturnCount`** | 한도가 2다. `?: return null`을 두 번 쓰면 바로 걸린다. `?.let { }` 체인으로 바꾸는 편이 `@Suppress`보다 낫다 — 실제로 반환 경로가 하나로 줄어드는 자리가 대부분이다 |
| **detekt는 컴파일보다 먼저 돈다** | `check`에서 detekt가 실패하면 `compileKotlin`이 아예 실행되지 않는다. detekt만 고쳐 놓고 "빌드가 되는구나" 하면 안 된다 |
| **detekt `TooManyFunctions`** | 인터페이스 한도가 11이다. 다른 모듈의 포트를 저장소 인터페이스에 **직접 구현시키면** 금방 넘는다. 별도 어댑터로 빼는 편이 억제보다 낫다 — 그쪽이 "이 경로가 볼 수 있는 것"을 한 곳에서 말해 주기도 한다 |
| **H2는 부분 인덱스를 모른다** | `create index ... where ...`는 PostgreSQL 전용이다. 테스트는 `MODE=PostgreSQL`인 H2에서 도는데 이건 지원 범위 밖이다. `alter table drop constraint`와 `CASE` 기반 check 제약은 H2에서도 통과한다(V25에서 확인) |
| **새 `OutboxJobType` 추가 시 exhaustive `when`이 하나가 아니다** | 핸들러 등록(`OutboxJobHandlerConfiguration`)만 고치고 끝내기 쉬운데, `JobExecutionCapacity.permitsFor(type)`도 같은 enum에 대한 exhaustive `when`이라 새 항목을 놓치면 컴파일이 깨진다. 새 enum 값을 추가할 때는 `grep -rn "when (type)\|OutboxJobType\."`으로 다른 switch가 더 있는지 먼저 확인하는 편이 안전하다 |
| **Kotlin이 제네릭 Java 메서드 반환 타입을 non-null로 좁힐 때가 있다** | `transactionTemplate.execute { ... }`의 람다가 항상 non-null을 반환하면 Kotlin이 호출 전체를 non-null로 추론해서, 뒤에 붙인 `?: fallback`이 "항상 죽은 코드"라는 컴파일 경고가 뜬다. `FollowUpSuggestionService`처럼 애초에 `?: fallback`을 안 붙이는 게 맞다 |

---

## 7. Git 상태와 변경 범위

- Phase 17 설계·명세: `DESIGN3.md`, `TEST_SPEC.md`, JSON Schema, Profile 예시
- 백엔드: 명세 등록·승인·실행·조회 API, 검증기, Runner, 관측·CEL 판정·reset, auth provider, H2/PostgreSQL 영속화와 V23/V24 migration
- 회귀 검증: Phase 17 완료 기준, 안전 경계, 재시작 복구, PostgreSQL 계약 테스트
- 프런트엔드: Phase 11–15 Workbench 화면/API 모듈과 46개 테스트
- 문서: 현재 구현 범위와 다음 Phase를 반영한 `README.md`, 이 인수인계 문서

Phase 17 범위는 `1b939c8`에, Phase 18(observation source Profile 계약, HTTP reader, 완료 기준
테스트)과 Phase 19 범위는 `abafb97`에, Phase 20(LLM 제안) 범위는 `082b4ec`에 커밋됐다.

Phase 20이 건드린 파일 (전부 `082b4ec`):

- 도메인: `TestSpecGenerationModels.kt`(신규 — `TestSpecGenerationRunStatus`,
  `TestSpecGenerationCandidateOutcome`, `TestSpecGenerationRunRecord`,
  `TestSpecGenerationCandidateRecord`, `TestSpecGenerationRunDetails`)
- 포트: `TestSpecGenerationStore.kt`, `TestSpecProposalModel.kt`, `TestSpecGenerationSettings.kt`(전부 신규)
- 애플리케이션: `StartTestSpecGeneration.kt`(명령), `TestSpecGenerationService.kt`(핵심 오케스트레이션 —
  멱등성, 크기 상한, 스냅샷/프로필 최신성 확인, outbox 핸드오프, 후보별 검증기 통과·기록)
- 인프라: `OllamaTestSpecProposalModel.kt`, `TestSpecGenerationProperties.kt`,
  `sql/TestSpecGenerationSql.kt`, `JdbcTestSpecGenerationRepository.kt`, `V26` migration,
  `TestSpecExecutionConfiguration.kt`(재시작 복구 `ApplicationRunner` 추가)
- outbox 배선: `OutboxJob.kt`(`TEST_SPEC_GENERATION` 추가), `OutboxJobHandlerConfiguration.kt`
  (핸들러 등록), `JobExecutionCapacity.kt`(분석 permits 그룹에 배분)
- API: `TestSpecGenerationController.kt`, `dto/StartTestSpecGenerationRequest.kt`,
  `dto/TestSpecGenerationCandidateResponse.kt`, `dto/TestSpecGenerationRunResponse.kt`
- 테스트: `TestSpecGenerationApiIntegrationTests.kt`(신규 — 4개, 그중 2개는 독립 리뷰 대응 회귀)
- 문서: 이 문서

PostgreSQL Testcontainers는 Docker Desktop을 켠 환경에서 다시 실행해야 한다.
