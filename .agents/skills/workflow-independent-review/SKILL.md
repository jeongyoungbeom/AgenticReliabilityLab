---
name: workflow-independent-review
description: 구현에 참여하지 않은 새 세션에서 현재 구현을 요구사항과 비교해 독립적으로 리뷰하고 실제 결함만 기록한다. 제품 코드는 수정하지 않는다.
---

# 독립 리뷰 Workflow

제품 코드·테스트는 읽기 전용으로 리뷰한다. 안전한 기존 테스트 실행은 가능하다. workflow 문서와 REVIEW 결과는 필요하면 갱신할 수 있다. 억지 finding이나 취향 리뷰를 만들지 않는다.

## 읽기 순서

1. 저장소 지침과 최신 요구사항
2. active `.workflow/TASK.md`가 있으면 목표·요구사항·완료 조건·Task ID·Independent Review 값
3. 검토 대상 코드와 직접·간접 영향받는 호출 흐름
4. 관련 테스트와 필요한 실행 결과
5. 독립적으로 finding 후보를 만든 뒤 현재 작업에 적용되는 active decision과 유효한 HANDOFF 확인

TASK나 다른 기록의 내용은 실제 코드와 최신 요구사항으로 검증한다.

## 확인 영역

검토 대상과 실제로 관련된 항목만 본다.

- 요구사항 누락 / 비즈니스 로직 / regression
- validation / exception / API 계약
- transaction / race condition / lock / idempotency
- 데이터 정합성 / migration / index / performance
- retry / messaging / cache / partial failure / recovery
- 인증·인가·민감정보
- 테스트 누락·오검증
- 실제 위험을 만드는 불필요한 복잡성

독립 리뷰의 목적은 요구사항과 실제 결함 검증이다. 단순화 취향만으로 finding을 만들지 않고 별도 over-engineering audit을 수행하지 않는다.

## Finding 기준

- `Critical` — 데이터 손실, 보안 사고, 권한 우회, 핵심 기능 전체 불능처럼 즉시 막아야 하는 결함
- `Major` — 일반적인 사용 조건에서 요구사항 위반, 잘못된 결과, 중요한 회귀 또는 복구 어려운 장애를 만드는 결함
- `Minor` — 제한적인 조건에서 발생하고 영향 범위가 작지만 실제 수정 가치가 있는 결함

각 finding은 사람이 읽는 항목명을 한국어로 써서 `위치 / 문제 / 발생 조건 / 영향 / 근거 / 권장 수정`을 적는다. `심각도` 같은 항목명은 한국어로 쓰되 finding ID(`REV-001`)와 severity 값(`Critical` / `Major` / `Minor`)은 고정 토큰을 유지한다.
재현 가능하거나 코드 흐름으로 입증할 수 있는 실제 결함만 기록한다. 문제가 없으면 없다고 기록한다.

## 결과 저장과 종료

- active TASK가 있고 Task ID가 유효하면 `reviews/<task-id>/REVIEW.md`를 사용한다. active TASK가 없으면 ad-hoc 리뷰로 `reviews/REVIEW.md`를 사용한다.
- active TASK의 Task ID가 placeholder이거나 형식에 맞지 않으면 task-scoped 리뷰 경로를 임의로 만들지 말고 메타데이터 문제를 먼저 명시한다.
- REVIEW에는 사람이 읽는 항목명을 한국어로 써서 `검토 범위 / 요구사항 출처 / 수행한 검증 / 검토 제외 범위 / 미검증 항목`을 먼저 남긴다.
- finding ID는 `REV-001` 형식을 사용한다.
- 기존 REVIEW가 있으면 현재 리뷰 결과로 갱신하고 별도 이력을 만들지 않는다.
- finding이 1개 이상이면 여기서 수정하지 않고 종료한다. `Independent Review: required`인 active TASK는 active 상태로 유지해 `workflow-apply-review`로 넘긴다.
- finding이 0개이고 `Independent Review: required`인 active TASK면 REVIEW와 필요한 검증을 마친 뒤 TASK를 `completed`로 바꾸고 종료한다. 이 경우 `workflow-apply-review`는 실행하지 않는다.
- ad-hoc 리뷰는 TASK 상태를 변경하지 않는다.
