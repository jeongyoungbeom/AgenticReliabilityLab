# Review Resolution

Task: target-onboarding-h4
Reviewed Revision: `c41955f` + 리뷰 당시 H4 미커밋 작업 트리
Applied At: `c41955f` + H4 작업 트리의 리뷰 반영 변경 (2026-09-29 15:53 KST)

Summary: ACCEPTED 4 / REJECTED 0 / ALREADY_RESOLVED 0 / DEFERRED 0 / STALE 0

## REV-001

Status: ACCEPTED

Reason:
`GenericPilotTemplateFactory.plans`가 Harness 키가 없으면 OpenAPI 재조회 전에 읽기 후보를 반환해, 활성화 후 변경된 공개 GET 계약을 실행 전에 차단하지 못했다.

Changes:
- 승인 시 OpenAPI 문서 묶음의 SHA-256을 Profile에 저장하고, 키 유무와 관계없이 후보 생성 전에 fresh OpenAPI와 대조한다. Harness 키가 있으면 기존 전체 계약 해시 검사도 유지한다.
- Profile YAML의 `openapi-sha256` 필드를 읽기·쓰기·검증한다.

Verification:
- 키 없는 공개 GET 실행 후 OpenAPI를 변경하면 discovery와 선택 실행이 모두 HTTP 409 `TARGET_CONTRACT_CHANGED`를 반환하는 통합 테스트 통과.
- 2026-09-29 15:53 KST `gradlew.bat check --no-daemon` 성공.

## REV-002

Status: ACCEPTED

Reason:
공개 GET의 호출 역할을 같은 path의 쓰기 역할에서 유추해, 실제 무인증으로 검증된 GET도 후보 검사에서 `NOT_READY`가 됐다. 역할별 GET은 credential preflight에 사용되므로 이를 지우기만 하면 쓰기 경로가 막힌다.

Changes:
- 공개 GET은 null 인증 호출로 저장하고, 역할별 GET은 별도 허용 호출로 보존한다.
- Profile 중복 검사와 Test Spec capability의 인증 역할 검사를 method/path/role 조합에 맞게 조정했다.

Verification:
- 같은 path의 공개 GET과 보호된 POST에서 읽기 후보 `READY` 및 무인증 실행을 확인하고, 기존 쓰기 preflight·실행 회귀 테스트 통과.
- 2026-09-29 15:53 KST `gradlew.bat check --no-daemon` 성공.

## REV-003

Status: ACCEPTED

Reason:
`RUN_CAPTURE`가 생산 응답과 소비 요청의 scalar 여부만 검사해 string ID를 integer 입력에 연결한 Profile 제안을 허용했다.

Changes:
- 이전 operation의 OpenAPI 응답 capture 타입과 현재 요청 property 타입을 비교한다. 같은 타입과 integer 생산값의 number 입력만 허용한다.

Verification:
- string 생산 ID를 integer 입력에 연결한 제안은 HTTP 400으로 거부하고, 일치하는 integer 연결의 생성·실행 테스트는 통과.
- 2026-09-29 15:53 KST `gradlew.bat check --no-daemon` 성공.

## REV-004

Status: ACCEPTED

Reason:
setup의 사전 body field 추출이 문자열 치환 후 JSON 파싱을 수행해, 정상 capture에 따옴표나 역슬래시가 있으면 전송 전에 실패했다.

Changes:
- 사전 추출에도 전송 경로와 동일한 `resolveJsonBody`를 사용해 값 단위로 치환하고 JSON escaping을 보존한다.

Verification:
- 따옴표·역슬래시가 있는 첫 setup capture를 두 번째 setup의 JSON 본문으로 전달하는 실행 테스트 통과.
- 2026-09-29 15:53 KST `gradlew.bat check --no-daemon` 성공.
