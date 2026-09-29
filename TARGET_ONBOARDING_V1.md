# Target Onboarding V1 - 표준 Harness와 AI 추가 테스트

Status: Approved direction, H0-H5 implemented and verified; H6 pending
Updated: 2026-09-29

이 문서는 다음 제품화 단계의 요구사항과 구현 순서를 정의한다. 현재 구현 여부는 코드와 `HANDOFF.md`에서 확인한다. 과거 Eventful Commerce 파일럿 설계는 `DESIGN4.md`, 기존 관측·명세 계약은 `TARGET_REQUIREMENTS.md`와 `TEST_SPEC.md`에 남긴다.

## 1. 제품 약속과 범위

사용자는 `LOCAL` 또는 격리된 `TEST` 타겟에 ARL Harness를 붙이고, 필요한 타겟별 연결 로직 몇 개와 테스트 계정만 준비한다. ARL은 OpenAPI와 Harness 계약을 읽어 기본 테스트 후보를 만들고, 사용자가 허용 범위와 실행을 승인하면 테스트·판정·정리를 수행한다. AI는 기본 목록에 없는 추가 Test Spec 초안을 제안한다. 사용자가 매번 테스트 코드나 JSON 명세를 작성하는 흐름은 기본 경험이 아니다.

첫 지원 범위는 HTTP API + OpenAPI를 가진 타겟이다. 언어에 종속되지 않는 HTTP 계약을 먼저 정하고, 현재 Kotlin/Spring Eventful Commerce Harness를 첫 참조 구현으로 사용한다. 다른 스택용 SDK나 배포 자동화는 두 번째 타겟 검증 이후 판단한다.

### 사용자가 준비할 것

1. 격리된 `LOCAL`/`TEST` 환경, 도달 가능한 기존 business API와 OpenAPI 문서.
2. Harness 참조 구현 또는 같은 HTTP 계약의 adapter. Harness가 표준 endpoint를 제공하고 타겟 개발자는 상태 조회·run 범위 정리·필요한 준비 판정을 연결한다.
3. 테스트 계정·Harness 키. 자격증명은 ARL의 비영속 런타임 세션 또는 배포 환경 secret으로만 전달한다.
4. 실행을 허용할 business operation과 역할의 확인. ARL이 제안한 Profile 완전본을 보고 승인하며 Test Spec은 직접 작성하지 않는다.

기존 business API가 테스트 대상이다. 타겟에 새로 만들 수 있는 API는 Harness 제어면이며, 비동기·장애 기능을 쓰지 않는 프로젝트에 해당 endpoint까지 강요하지 않는다. 다만 안전한 fixture 입력과 판정 근거가 없는 쓰기 operation은 자동 실행 후보가 될 수 없다.

## 2. Harness V1 계약

H1에서 확정한 HTTP 계약과 adapter 연결 지침은 `docs/harness-v1.md`, 요청·응답 JSON Schema는 `src/main/resources/schema/harness-v1.schema.json`을 따른다. 아래 경로는 참조 구현의 기본값이며 Profile에는 실제 상대 경로를 버전으로 저장한다. H2에서 Eventful Commerce adapter를 이 계약에 맞췄다. 현재 참조 manifest의 `operations`는 빈 배열이며 범용 operation 연결과 소비는 H3/H4에서 완성한다.

| 기능 | 기본 경로 | 필수 조건 | 의미 |
| --- | --- | --- | --- |
| 기능 선언 | `GET /api/harness/manifest` | 쓰기 후보 자동 생성 시 | 계약 버전, 지원 기능, 안전한 operation 연결 정보. 비밀값 없음 |
| 상태 조회 | `GET /api/harness/state` | 상태 판정이 필요한 후보 | `X-ARL-Run-Id` 범위의 관측 필드와 값 |
| 정리 | `POST /api/harness/reset` | 상태 변경 후보 | 해당 run의 fixture·예약·fault만 정리하고 결과를 검증 가능하게 반환 |
| 수렴 확인 | `GET /api/harness/readiness/{kind}/{id}` | 비동기 후보만 | `{ready: boolean, reason: string}`. 준비 전 재요청 대상은 business POST가 아니라 이 GET |
| 장애 주입·해제 | `POST /api/harness/fault`, `/fault/release` | 장애 후보만 | 허용 fault만 제한된 TTL·run 범위로 주입하고 finally에서 해제 |

Manifest의 operation 연결 정보에는 OpenAPI `operationId` 또는 정확한 method/path, 역할, `SYNTHETIC_JSON_V1` fixture 입력 매핑, 응답에서 capture할 ID, 판정에 필요한 관측 필드·기대 상태, 멱등성 의미와 readiness 참조를 담는다. 입력 매핑은 run 태그 문자열·범위 제한 정수·같은 run의 이전 operation capture만 사용하며 자세한 형식은 `docs/harness-v1.md`를 따른다. 이는 **실행 가능성을 설명하는 최소 계약**이지 타겟 개발자가 완성된 Test Spec을 써서 제공하는 통로가 아니다. ARL은 manifest를 그대로 권한으로 믿지 않고, OpenAPI 교집합과 사람이 확인한 Profile allowlist를 다시 적용한다. 매핑이 필수 요청 필드를 안전하게 채우지 못하면 해당 쓰기 후보를 만들지 않는다.

모든 Harness 요청은 local/test 전용 인증과 run 식별자를 검증한다. 조회는 상태를 바꾸지 않고, reset은 같은 run에 반복 호출해도 안전하며 다른 run과 실제 사용자 데이터를 변경하지 않는다. 준비 확인은 현재 파일럿의 10초/200ms polling을 기본으로 하되 실패·시간 초과에는 이유를 남기고 다음 쓰기 요청을 보내지 않는다. 인증정보·요청/응답의 민감한 본문은 Evidence·로그·LLM 입력에 넣지 않는다.

Harness가 없어도 기존 읽기 전용 점검은 유지된다. `manifest`/`state`/`reset`은 **쓰기 후보를 자동 구성하기 위한 기본 조합**이고, readiness와 fault는 후보별 기능이다. 기능이 빠졌으면 전체 Target을 막지 않고 해당 후보만 `NOT_READY`와 누락 이유를 표시한다.

## 3. ARL 등록과 실행 흐름

1. URL·환경을 등록하고 역할별 테스트 자격증명을 입력한다. ARL은 허용된 origin에서 OpenAPI와 Harness manifest를 읽는다.
2. 각 문서의 출처·버전을 보존한 Knowledge Snapshot을 보여 주고 확인받는다. 여러 서비스 문서는 추천 시 하나의 입력 묶음으로 다루되, 어떤 문서에서 나온 근거인지 잃지 않는다.
3. ARL이 business operation/역할/fixture/관측/정리의 매핑과 Profile 완전본을 제안한다. 사용자가 쓰기 허용 범위를 명시적으로 확인한 뒤에만 활성화한다. Swagger 또는 manifest만으로 쓰기 allowlist를 넓히지 않는다.
4. 후보별 capability gate를 계산한다. H4 기본 후보인 읽기·쓰기·멱등성·비동기 workflow는 필요한 계약이 갖춰진 경우에만 READY다. 범용 동시성·장애/복구는 타겟 고유의 판정 근거가 선언될 때 선택적으로 확장한다.
5. 기본 후보 선택 → 명시 승인 → 기존 Test Spec 엔진의 run별 reset, fixture, workload, readiness, observation, 결정적 판정과 정리 검증을 수행한다. Eventful Commerce의 기존 7개 템플릿은 사용자 최종 테스트 후 전용 경로 제거 여부를 결정할 때까지 호환 경로로 유지한다.

## 4. AI 추가 추천

기존 `TestSpecGenerationService`와 Ollama 모델 경로를 재사용한다. 현재 서비스는 한 Snapshot ID를 요구하고, 기존 후보와 다르다는 조건을 주로 prompt에 의존한다. 따라서 여러 문서의 **확정된** Snapshot, 활성 Profile의 허용 operation·역할·Harness capability, 기본 후보와 이전 실행의 안전한 결과 요약을 입력으로 묶고, 정규화한 operation 순서·판정 목적의 fingerprint로 중복을 코드에서 걸러야 한다.

AI 출력은 제안이다. schema·Profile allowlist·환경·fixture·readiness·정리 가능성 검증을 통과한 명세만 `PENDING_APPROVAL`로 저장한다. 거부된 제안과 사유도 검토 화면에 남긴다. 모델이 새 권한을 만들거나 임의의 민감한 본문·실데이터를 입력으로 정하지 못한다. 사용자가 근거, API 순서, 입력, 위험도와 기대 결과를 검토·승인한 뒤에만 실행한다. LLM은 PASS/VIOLATED를 판정하지 않는다.

기본 후보와 AI 후보는 같은 화면에서 출처를 구분한다. Ollama가 없거나 유효한 새 후보가 없으면 기본 실행은 계속 가능하며 `추가로 검증 가능한 후보 없음`을 보여 준다. 실행 결과를 근거로 후속 테스트를 추천하는 기능은 이 첫 연결 이후에 확장한다.

## 5. 구현 순서와 단계별 완료 조건

### H0 - 검증 기준선

- ARL PostgreSQL 동시 실행 API 테스트의 ID 누락 응답을 재현·원인 분리한다. 새 기능 작업의 회귀 신호가 되도록 전체 `check`를 통과시키거나, 환경/기존 결함이면 정확한 실패 조건을 기록한다.
- Eventful Commerce 7개 local 파일럿 결과와 run 정리 상태를 기준선으로 보존한다. 기존 미커밋 변경을 임의로 되돌리지 않는다.

### H1 - 계약과 conformance 테스트

- Harness V1 manifest, state, reset, readiness, fault의 schema·오류 코드·버전 협상·인증·run 범위를 확정한다. fault/readiness의 부재가 기본 후보 전체를 막지 않는 규칙을 테스트로 고정한다.
- 계약 테스트는 정상, 미인증, 다른 run 참조, 중복 reset, readiness 시간 초과, fault release 실패를 포함한다. 산출물은 HTTP 계약과 타겟 adapter 구현 안내다.

### H2 - 참조 Harness

- Eventful Commerce의 `reliability-harness`를 첫 adapter로 맞추고 표준 endpoint를 제공한다. 상품 projection/Redis 재고와 결제 예약 readiness, run별 reset/fault 동작은 유지한다.
- ARL의 현행 7개 후보가 그대로 통과하고 정리가 확인되어야 한다. 운영 모드에서 Harness 경로를 노출하지 않는다.

### H3 - 범용 Profile 등록

- `QuickTargetProfileFactory`의 Eventful Commerce 경로·역할·필드 고정을 범용 등록 경로에서 제거한다. OpenAPI와 manifest를 안전하게 읽고, 제안된 완전한 Profile의 쓰기 allowlist를 사람이 확인·활성화한다.
- Profile 버전, 기존 quick registration, SSRF 방어, 역할별 preflight가 유지되어야 한다. 문서·manifest 불일치나 미지원 버전은 실행 전 명확히 거부한다.

구현된 H3 API는 별도 `/api/target-profiles/proposals`에서 명시된 OpenAPI 경로와 Harness manifest를 CIDR 고정 전송으로 읽고 DRAFT 버전을 만든다. 활성화 시 계약을 다시 검사한다. 현행 안전한 자동 쓰기 매핑은 inline JSON object 요청과 진단 run의 0 숫자 baseline을 요구하며, 지원하지 않는 recipe는 거부한다. 범용 후보 생성·실행은 H4 범위다.

### H4 - 범용 기본 후보

- `PilotDiscoveryService`와 `PilotTestTemplateFactory`의 business 경로 고정을 capability 기반 후보·명세 생성으로 옮긴다. 공통 recipe만 재사용하고 도메인 기대값은 manifest/관측 계약에서 얻는다.
- 후보별 의존성만 검사한다. 기존 고정 장애 후보의 fault gate를 유지하고, readiness 미지원은 해당 비동기 후보만 막는다. 범용 fault·동시성은 판정 근거를 추가로 선언할 때 선택적으로 확장하며, 부족한 fixture·판정 근거를 모델의 추측으로 메우지 않는다.
- 기존 7개 고정 후보 경로의 자동화 회귀와 범용 계약의 읽기/쓰기/멱등/비동기 사례를 검증한다. Eventful Commerce 실제 7개 파일럿 재실행은 사용자 최종 테스트에서 수행하며 H4 완료 게이트로 삼지 않는다. 전용 경로 제거 여부는 최종 테스트 후 결정한다.

### H5 - AI 추천과 UI

- 다중 Snapshot 입력, 안전한 capability 목록, 결정적 중복 제거를 기존 명세 생성 서비스에 추가한다. stub model 테스트로 유효·중복·범위 밖·잘못된 JSON·모델 부재를 검증한다.
- 기본 후보 옆에 AI 추가 후보, 근거·위험·거부 이유, 명세 검토·승인·실행을 연결한다. 생성은 실행이 아니며 실패해도 기본 후보 UI가 동작한다.
- 실제 모델 평가에서는 기본 목록과 다른 유효 후보가 있는지 확인한다. 후보가 없으면 억지로 표시하지 않는다.

### H6 - 두 번째 타겟 검증

- Eventful Commerce와 도메인이 다른 HTTP 프로젝트에서 타겟 adapter·계정·OpenAPI·허용 범위만 준비한다. ARL에 그 프로젝트 전용 경로·템플릿 코드를 추가하지 않는다.
- 기본 테스트가 실행·판정·정리되고, AI 후보의 검증·승인 경로가 작동해야 한다. 타겟 개발자가 별도 Test Spec JSON이나 테스트 코드를 작성할 필요가 없어야 한다.
- 실제 준비 단계, 필요한 adapter 함수/API 수, 막힌 후보와 이유를 기록해 `최소 준비` 약속을 검증한다.

### 구현 위치와 단계 게이트

| 단계 | 먼저 확인할 위치 | 단계 종료 시 확인할 것 |
| --- | --- | --- |
| H0 | ARL PostgreSQL API 통합 테스트와 실패 응답 생성 경로 | 실패 원인·재현법을 기록하고 전체 `check` 결과를 새 기준선으로 남김 |
| H1 | ARL Profile/Harness 계약과 `PilotDiscoveryService`의 현재 전체 게이트 | 계약 schema·conformance suite·후보별 누락 기능 판정이 서로 일치함 |
| H2 | SideProject `reliability-harness`, Gateway local overlay, ARL 파일럿 실행 | 기존 7개 후보·정리 확인; 비활성 환경에서 Harness 접근 불가 |
| H3 | `QuickTargetProfileRegistrationWorkflow`, `QuickTargetProfileFactory`, Profile 검증·활성화 | 두 종류의 OpenAPI/manifest로 Profile 제안과 승인·거부를 테스트함 |
| H4 | `PilotDiscoveryService`, `PilotTestTemplateFactory`, Test Spec validator/executor | 도메인 경로를 ARL 코드에 추가하지 않고 서로 다른 두 범용 HTTP 계약의 기본 후보 실행 |
| H5 | `TestSpecGenerationService`, Snapshot 저장·확인 흐름, `PilotDiscoveryPanel` 및 `PilotTemplateRunnerPanel` | 안전한 신규 제안만 승인 대기 상태; 모델 실패 시 기본 후보 그대로 사용 |
| H6 | 별도 도메인의 참조 adapter와 ARL E2E 기록 | Target별 ARL 코드 없이 연결·실행·판정·정리·AI 승인까지 재현 |

각 단계에서는 먼저 실패/미지원 사례를 테스트로 고정하고, 통과 후 이전 단계의 회귀 검증을 다시 수행한다. H3 이후에도 고정 7개 파일럿은 새 범용 경로가 같은 판정·정리 근거를 낼 때까지 유지한다.

## 6. 비목표와 호환성

- 생산 환경 대상 상태 변경 테스트, 무승인 AI 실행, Swagger만으로 모든 도메인 불변식 추론, 임의 내부망 탐색은 범위 밖이다.
- 기존 Eventful Commerce 파일럿과 수동 고급 YAML/명세 API는 범용 경로가 검증될 때까지 유지한다. Profile 권한이나 정리 실패 차단을 편의 때문에 완화하지 않는다.
- H1의 확정 endpoint·manifest 세부 필드는 `docs/harness-v1.md`와 schema가 기준이다. 계약을 바꿀 때는 테스트와 이 문서를 함께 갱신한다.
