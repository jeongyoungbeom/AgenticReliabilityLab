---
name: workflow-apply-review
description: 독립 리뷰 finding을 현재 요구사항과 코드에서 다시 검증하고 타당한 finding만 최소 범위로 수정·검증할 때 사용한다.
---

# 리뷰 반영 Workflow

리뷰어의 의견을 자동으로 정답 처리하지 않는다. REVIEW에 finding이 있을 때 사용한다.

## 1. 재검증

1. 최신 요구사항과 현재 구현 상태 확인
2. active TASK와 유효한 Task ID가 있으면 `reviews/<task-id>/REVIEW.md`, active TASK가 없으면 `reviews/REVIEW.md`를 검토 대상으로 사용한다. active TASK의 Task ID가 placeholder이거나 형식에 맞지 않으면 임의의 task-scoped 경로를 만들지 말고 메타데이터 문제를 먼저 명시한다.
3. REVIEW의 Reviewed Scope와 현재 코드를 비교
4. 각 finding은 판정에 필요한 최소 근거만 확인한다. 코드만으로 명확하면 별도 재현을 요구하지 않고, 동일 근거로 판단 가능한 finding은 중복 검증하지 않는다.

리뷰 이후 코드가 달라졌더라도 자동으로 무효 처리하지 않는다. finding의 전제가 실제로 사라졌는지 확인한다.

## 2. 판정

- `ACCEPTED` — 실제 문제이며 수정
- `REJECTED` — 사실과 다르거나 요구사항과 맞지 않음
- `ALREADY_RESOLVED` — 이미 해결됨
- `DEFERRED` — 문제는 맞지만 현재 범위 밖
- `STALE` — 이후 변경으로 finding의 전제가 사라짐

## 3. 수정과 검증

- `ACCEPTED`만 가장 작은 안전한 변경으로 수정한다.
- 관련 없는 리팩터링이나 별도 단순화 작업을 섞지 않는다.
- ACCEPTED finding을 수정한 뒤 해당 문제와 직접·간접 관련된 최소 회귀 검증으로 해결 여부를 확인한다.
- 수정으로 기존 검증 결과가 무효화되지 않았다면 이미 통과한 테스트·빌드·정적 검사를 반복하지 않는다. 단, race condition, flaky behavior, 비결정적 동작, 성능 변동처럼 반복 자체가 검증 방법이면 필요한 횟수만 반복한다.
- 공유 API·공통 함수·계약을 변경했다면 수정하지 않은 caller라도 실제 영향받는 범위까지 검증한다. 그 밖의 관련 없는 영역으로는 확장하지 않는다.
- 요청 범위 밖 대상이나 되돌리기 어려운 자원을 건드려야 한다면 `AGENTS.md`의 안전과 권한 기준에 따라 먼저 사용자에게 묻는다.

## 4. 결과 저장과 완료

- active TASK와 유효한 Task ID가 있으면 `reviews/<task-id>/RESOLUTION.md`, active TASK가 없으면 `reviews/RESOLUTION.md`에 finding별 `판정 / 근거 / 변경 내용 / 실제 검증 결과`를 한국어로 남긴다. 판정 값(`ACCEPTED`, `REJECTED`, `ALREADY_RESOLVED`, `DEFERRED`, `STALE`)은 번역하지 않는다. active TASK의 Task ID가 유효하지 않으면 임의 경로를 만들지 않는다.
- 기존 RESOLUTION이 있으면 현재 반영 결과로 갱신하고 별도 이력을 만들지 않는다.
- `Independent Review: required`인 active TASK는 모든 finding을 판정하고 ACCEPTED 수정의 필요한 검증을 마친 뒤, 현재 Acceptance Criteria를 위반하는 미해결 finding이 없을 때만 `completed`로 바꾼다. `DEFERRED`는 현재 Acceptance Criteria 밖의 문제에만 사용할 수 있고 남은 위험과 범위를 명확히 남긴다.
- REVIEW에 finding이 0개라면 이 workflow를 실행하지 않는다.
