# HANDOFF

Updated: 2026-09-29
Task ID: `target-onboarding-h4`
Status: inactive

## Repository State

- ARL `master`, HEAD `c41955f`. H4 코드·테스트·README·TASK·이 문서가 미커밋 상태다. 기존 H3 HEAD 이후의 변경은 이번 H4 범위다. push하지 않았다.
- `TASK.md`는 H4 completed이다. 이후 독립 리뷰의 REV-001~004를 현재 작업 트리에서 모두 ACCEPTED로 판정·수정했고 `reviews/target-onboarding-h4/RESOLUTION.md`에 기록했다.

## Completed

- 활성 범용 Profile의 public GET으로 읽기 후보를 만들고 Harness 자격증명 없이 실행한다. 읽기 전용 Test Spec은 쓰기용 reset plan을 실행하지 않는다.
- 범용 후보는 Harness 키가 없어도 fresh OpenAPI를 승인 당시 해시와 대조한다. 키가 있는 쓰기 후보는 manifest까지 다시 읽고 전체 계약 해시도 대조한다. 승인된 method/path/역할만 사용한다.
- SYNTHETIC_JSON_V1의 run 태그 문자열, 제한 정수, 이전 operation의 문자열·숫자·boolean capture를 Test Spec 요청 body로 구성한다. JSON 치환은 타입과 문자열 escaping을 유지한다.
- manifest 관측값, KEYED 멱등성, readiness kind와 단일 ID capture에 근거한 기본 후보를 만들고, 필수 capability나 안전한 OpenAPI 입력 근거가 부족하면 해당 후보를 NOT_READY로 표시한다. 명세를 생성 직후 기존 parser/validator로 점검한다.
- 기존 Eventful Commerce 고정 7개 후보 경로를 유지한다. fault 요청도 새 JSON 치환 방식에 맞게 조정했다.
- 범용 fault·동시성은 타겟 고유의 판정 근거가 필요한 선택 후보로 분리했다. 선언이 없는 타겟의 기본 후보 실행을 막지 않는다.

## Verification

- 2026-09-29 15:52 KST, H4 리뷰 수정의 관련 통합·단위 테스트 73개와 `detekt` 통과.
- 2026-09-29 15:53 KST, 최종 코드에서 `gradlew.bat check --no-daemon` 성공: 전체 테스트·컴파일·detekt 통과.
- 공개 GET/보호된 쓰기 동일 경로, Harness 키 없는 읽기의 OpenAPI drift 차단, capture 타입 불일치 제안 거부, 특수 문자열 capture의 다단계 setup 전달을 회귀 테스트로 확인했다.
- `git diff --check` 통과. 기존 CRLF 정규화 안내만 출력됐다.
- 검증 이후 관련 코드가 바뀌면 다시 검사해야 한다.

## Current Risks

1. 기존에 저장된 범용 Profile에 `openapi-sha256`이 없으면 읽기 후보가 안전하게 차단된다. 새 제안·활성화로 Profile을 갱신해야 한다.
2. 두 번째 계약은 로컬 HTTP fixture로 실행했다. 별도 실제 타겟 adapter의 end-to-end 검증은 H6에 남는다.
3. V1 manifest에는 동시성·fault 상황의 구체적 기대값이 없어 이번 범용 생성기는 해당 선택 후보를 추측해 만들지 않는다. 필요할 때 타겟별 판정 계약을 추가해야 한다.
4. H3는 지원하지 않는 recipe와 필수 입력 누락을 Profile 제안 단계에서 거부한다. H4는 활성화 가능한 recipe 중 생성 body가 OpenAPI 제약을 만족하지 않는 후보를 NOT_READY로 표시한다.
5. 범용 후보용 별도 검토 UI와 AI 추천은 H5 범위다. 현재 API와 기존 파일럿 UI를 이용한다.

## Next

1. 다음 제품 단계는 H5 AI/UI와 H6 실제 두 번째 타겟 검증이다.
2. 범용 fault·동시성 후보가 필요해지면 선택적 판정 계약을 먼저 설계한다.
3. Eventful Commerce 실제 7개 파일럿 재실행은 사용자가 최종 테스트에서 수행한다. H4 완료 게이트로 취급하지 않는다.

## Relevant Files

- `TASK.md`, `TARGET_ONBOARDING_V1.md`, `README.md`
- `src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/GenericPilotTemplateFactory.kt`
- `src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/PilotDiscoveryService.kt`
- `src/main/kotlin/com/project/agenticreliabilitylab/targetdiscovery/application/PilotTemplateExecutionService.kt`
- `src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/SpecReferenceResolver.kt`
- `src/main/kotlin/com/project/agenticreliabilitylab/testspec/application/TestSpecRunner.kt`
- `src/test/kotlin/com/project/agenticreliabilitylab/targetprofile/api/TargetProfileApiIntegrationTests.kt`
