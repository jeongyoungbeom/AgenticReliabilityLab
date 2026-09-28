# HANDOFF

Updated: 2026-09-29
Task ID: `target-onboarding-h3`
Status: inactive

## Repository State

- ARL master에서 이전 readiness 및 H0-H3 작업 변경과 H-3 리뷰 수정분을 함께 커밋했다. 기존 Claude outputs/verify.yml도 사용자의 전체 변경 커밋 요청에 포함했다.
- Eventful Commerce SideProject는 이번 확인 시점에 작업 트리가 깨끗하다. H2 V1 adapter·Gateway 변경과 회귀 테스트는 앞선 작업에서 적용했다.
- 현재 TASK는 H3 범용 Profile 등록이며 리뷰 수정과 검증을 마쳤다. H4-H6은 미착수이고 push는 하지 않았다.

## Completed

- H3에서 `/api/target-profiles/proposals`가 명시된 OpenAPI 경로와 Harness V1 manifest를 CIDR 고정 전송으로 수집한다. method/path/operationId, 역할, 지원 fixture와 관측 필드를 대조해 범용 DRAFT Profile 완전본을 만든다. 활성화 전에 문서를 다시 읽고 제안 당시 계약과 달라지면 거부한다.
- H-3 리뷰 REV-001~003을 수용했다. 계약 원문의 SHA-256을 버전에 고정하고 중첩 필수 필드를 검사한다. health/Batch 읽기 후보는 실제 무인증 성공 GET으로 한정하고, 역할별 preflight용 인증 GET은 유지한다. 판정 근거는 `reviews/target-onboarding-h3/RESOLUTION.md`에 기록했다.
- 범용 제안은 기존 quick registration과 별도로 동작하며, 명시적 버전 활성화 전에는 쓰기 권한이 없다. 런타임 자격증명 저장·preflight가 임의의 안전한 역할 이름을 지원한다. Harness 키는 Profile에 저장하지 않는다.

- 이전 pilot-async-readiness 작업은 상품과 결제의 비동기 수렴을 polling으로 확인하도록 구현했고, local Docker의 Eventful Commerce 파일럿 7개가 모두 PASSED, cleanupVerified=true였다.
- 사용자와 다음 방향을 정했다: 타겟에 표준 Harness adapter와 소수의 연결 로직·테스트 계정·OpenAPI를 준비하면 기본 테스트를 실행하고, AI가 추가 후보를 제안한다. 명세 작성과 AI 자동 실행은 요구하지 않는다.
- 이 방향을 TARGET_ONBOARDING_V1.md에 단계 H0-H6으로 정리하고, TASK.md를 H0-H2 착수 범위로 갱신했다. DECISIONS.md의 구형 필수 Harness 계약을 폐기하고 후보별 capability 기준을 기록했다. README.md와 기존 설계·타겟 요구사항 문서에 현재 기준 링크를 반영했다.
- H0에서 같은 멱등 키의 동시 run 요청이 기존 run 조회와 실행 슬롯 검사 사이에 끼어들면 두 번째 요청이 슬롯 점유를 먼저 보고 거절될 수 있는 경로를 수정했다. 슬롯이 차단되면 같은 키의 저장된 run을 다시 조회하고, 다른 키의 점유는 계속 거절한다. 경쟁 테스트는 10회 반복하며 양쪽 응답의 201·동일 ID·DB 단일 run을 확인한다.
- H1에서 `docs/harness-v1.md`와 `schema/harness-v1.schema.json`에 버전·인증·run 격리·manifest/state/reset/readiness/fault 응답 및 오류 계약을 고정했다. 타겟 adapter 연결 안내는 Test Spec JSON이나 신규 business API 작성을 필수로 요구하지 않는다.
- 기존 7개 파일럿의 설정 게이트를 템플릿별 실제 의존성에 맞췄다. availability는 Harness 없이 가능하며, fault는 장애 후보, readiness는 해당 비동기 후보에만 요구한다. 쓰기는 기존 Profile 허용 범위와 reset/state 검증을 계속 요구한다.
- H1 리뷰 REV-001~003을 모두 수용했다. Harness 요청의 Runner 관리 버전 헤더, readiness의 V1 버전·run ID·필수 필드 검증, 구조화된 안전 fixture 입력 매핑을 추가했다. finding별 근거와 범위는 `reviews/target-onboarding-harness-v1/RESOLUTION.md`에 기록했다.
- H2에서 ARL의 Harness state 관측을 V1 최상위 필드·버전·run ID 검증으로 전환했다. REV-005를 반영해 표준 reset 응답의 정리 결과와 후속 state의 run ID를 확인하고, quick Profile에서 상품·주문·결제·fault 잔여량 6개를 모두 검사한다.
- REV-004를 반영해 SideProject의 참조 Harness와 결제 fault API를 V1으로 변경하고 Gateway local manifest route·보안 정책을 추가했다. 기존 run별 정리·준비 판정을 유지했다. 참조 manifest의 operation 매핑은 안전한 입력을 충분히 선언할 수 없어 빈 배열로 두었으며, 범용 매핑·소비는 H3/H4 범위다.
- local Gateway HTTP, production-profile 비노출, ARL의 7개 local 파일럿과 정리를 실제로 확인했다.

## Verification

- 2026-09-29 02:06 KST, H-3 수정 뒤 ARL `gradlew.bat check --no-daemon` 성공: detekt, 컴파일, 전체 테스트 통과. 종료된 Testcontainers PostgreSQL을 백그라운드 worker가 다시 접속하려는 기존 로그가 있었지만 Gradle 결과는 성공이다. 이후 코드 변경은 없으며 문서만 갱신했다.
- 2026-09-29 02:03 KST, `TargetProfileApiIntegrationTests`와 `detekt` targeted 실행 성공. 이후 보호된 GET만 있는 타겟의 거부 테스트를 추가했고 이 테스트는 위 전체 `check`에 포함됐다.
- H3 통합 테스트에서 두 종류의 계약 제안·활성화와 역할 preflight, fixture·관측·멱등성 변경 거부, 중첩 필수 입력 누락 거부, 무인증 읽기 후보 선별을 확인했다. 실제 두 번째 Target 실행은 H6 범위다.
- H2의 SideProject adapter/Gateway 테스트와 7개 local 파일럿 실행 결과는 이전 세션의 검증 기록이며 H-3 수정 후 재실행하지 않았다.

## Current Risks

1. Quick registration과 파일럿 후보·템플릿은 Eventful Commerce의 경로·필드를 유지한다. H3는 별도 범용 Profile 제안 경로를 제공하지만 범용 후보 생성·실행은 H4 작업이다.
2. H3의 자동 쓰기 매핑은 inline JSON object 요청과 숫자 0 baseline만 지원한다. 지원하지 않는 recipe는 제안에서 거부하며 H4의 후보 생성에 추측값을 사용하지 않아야 한다.
3. AI Test Spec 생성 백엔드는 있으나 단일 Snapshot 입력 중심이고 기본 후보와 결합된 검토 UI는 없다. 여러 문서의 확정 Snapshot 활용 여부도 검증되지 않았다.
4. H1은 fixture recipe 형식을 확정했지만 manifest를 소비해 범용 쓰기 후보를 만드는 엔진은 H4 범위다. 현재 참조 manifest의 `operations`는 빈 배열이므로 범용 쓰기 후보는 아직 생성되지 않는다. 지원하지 않는 recipe나 필수 입력 누락은 H4에서 후보 제외로 구현·검증해야 한다.

## Next

1. 다음 작업은 H4 범용 후보 생성과 fixture recipe 소비다. H3의 활성 Profile과 fresh manifest를 교집합으로 사용하고 기존 7개 파일럿을 유지한다.
2. H5의 AI/UI와 H6의 두 번째 실제 타겟 실행은 아직 수행하지 않았다.

## Relevant Files

- TARGET_ONBOARDING_V1.md: 제품 약속과 전체 H0-H6 계획
- docs/harness-v1.md 및 src/main/resources/schema/harness-v1.schema.json: H1 HTTP 계약·schema
- TASK.md: 완료된 H3 작업 범위와 검증 기준
- DECISIONS.md: 승인된 안전·capability 결정
- DESIGN4.md: Eventful Commerce 파일럿의 역사적 설계
- TARGET_REQUIREMENTS.md: 기존 타겟 연동 요구사항
- docs/history/HANDOFF-2026-08-27.md: 이전 인수인계 이력
