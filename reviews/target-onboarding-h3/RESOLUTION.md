# Review Resolution

Task: target-onboarding-h3
Reviewed Revision: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68 + 당시 working tree
Applied At: 4f0cadedf32dac0e4ee874f3a25aca629a12fe68 + H-3 수정 working tree

Summary: ACCEPTED 3 / REJECTED 0 / ALREADY_RESOLVED 0 / DEFERRED 0 / STALE 0

Final verification: 2026-09-29 02:06 KST, ARL `gradlew.bat check --no-daemon` 성공. 이후 코드 변경은 없고 리뷰 기록·README·HANDOFF 문서만 갱신했다.

## REV-001

Status: ACCEPTED

Reason:
기존 재매핑 결과에는 fixture 값, observation 기대값, 멱등성 등 계약 내용이 남지 않아 활성화 직전 변경을 감지하지 못했다.

Changes:
- 제안 시 읽은 OpenAPI 문서와 Harness manifest의 SHA-256을 Profile 버전에 저장하고 활성화 시 동일 문서를 다시 읽어 비교한다. YAML 입출력과 Profile 검증에도 필드를 반영했다.

Verification:
- fixture prefix, observation 기대값, idempotency 변경을 각각 활성화 직전에 적용하면 400을 반환하고 버전이 DRAFT로 남는 통합 테스트를 추가했다.

## REV-002

Status: ACCEPTED

Reason:
기존 recipe 검사는 최상위 required 경로의 접두사만 확인하여 필수 객체의 다른 필수 자식이 빠져도 통과했다.

Changes:
- inline object schema의 중첩 required 경로를 재귀적으로 수집해 recipe의 각 필수 leaf 매핑을 검사한다.

Verification:
- 필수 address.city가 빠진 제안을 거부하고 street, city를 모두 넣으면 DRAFT를 만드는 통합 테스트를 추가했다.

## REV-003

Status: ACCEPTED

Reason:
기존 mapper는 인증 없이 401을 반환하는 GET도 health 경로와 읽기 Batch 후보에 넣었다. Batch 실행은 인증 헤더를 보내지 않는다.

Changes:
- OpenAPI에서 공개된 고정 GET을 무인증으로 실제 호출해 선언된 성공 상태를 반환한 경로만 health와 Batch 읽기 후보에 넣는다.
- 쓰기 역할과 연결된 인증 GET은 역할별 credential preflight용 허용 호출에 유지한다. 공개 GET이 없으면 제안을 거부한다.

Verification:
- 보호된 business GET과 공개 health GET이 함께 있는 두 타겟에서 health 후보만 생성되고 역할별 preflight가 READY인 통합 테스트를 보강했다.
- 보호된 GET만 있는 타겟은 제안 단계에서 거부하는 통합 테스트를 추가했다.
