---
name: independent-review
description: 구현이 끝난 변경사항을 구현에 참여하지 않은 새 세션에서 독립적으로 코드 리뷰하고 reviews/<task>/REVIEW.md에 finding을 기록할 때 사용한다. 코드는 수정하지 않는다.
---

# 독립 코드 리뷰

적용되는 `../../../AGENTS.md`를 우선한다. 이 skill은 **검토만** 하는 세션용이다.

## 1. 이 세션이 지키는 것

- **코드를 수정하지 않는다.** 파일 편집, 커밋, 테스트 코드 추가 모두 하지 않는다.
  (읽기 전용 명령과, 필요하다면 기존 테스트 실행까지만 한다.)
- 구현자의 설계 판단을 정당화하려 하지 않는다. 왜 그렇게 했는지 추측해서 변호하지 않는다.
- `HANDOFF.md`의 완료 주장을 그대로 신뢰하지 않는다. 실제 repository를 직접 확인한다.
- 요구사항 위반과 구현 상태를 구분한다. "요구사항과 다르다"와 "내 취향과 다르다"는 다른 것이다.
- 문제가 없으면 억지 finding을 만들지 않는다. **없다고 보고하는 것이 정상적인 결과다.**
- 추측을 사실처럼 쓰지 않는다. 코드를 읽어 확인한 것과 의심 수준을 문장에서 구분한다.

## 2. 읽는 순서 — 앵커링을 막는다

기존 리뷰나 구현자의 설명을 먼저 읽으면 최초 판단이 그쪽으로 끌려간다. 순서를 지킨다.

**먼저 이것만 본다.**

1. `AGENTS.md`
2. `TASK.md`
3. TASK가 직접 참조하는 요구사항 문서
4. `git status`
5. 관련 `git diff` (미커밋 변경 포함 여부를 확인한다)
6. 변경된 코드
7. 그 코드의 호출 관계
8. 관련 테스트

**이 단계에서 먼저 독립적으로 finding 후보를 만든다.**

**그 다음에만** `reviews/<task>/REVIEW.md`와, 필요하면 `HANDOFF.md`·`DECISIONS.md`를 읽는다.
`HANDOFF.md`에서 얻을 것은 설계 설명이 아니라 known risk, 미검증 영역, 테스트 실행 상태, 현재 작업 범위다.

`DECISIONS.md`는 finding을 쓰기 전에 확인한다. 확정된 결정을 모르고 "왜 이렇게 했나"를 finding으로 만들면
중복 논쟁이 된다. 다만 **결정 자체가 요구사항을 위반한다면 그것은 정당한 finding이다.**

## 3. 검토 범위

변경과 실제로 관련된 것만 깊게 본다. 아래를 기계적으로 전부 훑지 않는다.

- 요구사항 누락, 잘못된 비즈니스 로직, 기존 동작 regression, 잘못된 상태 전이
- validation, null / empty / boundary, 예외 처리
- 트랜잭션 경계, 동시성, race condition, locking, 멱등성
- 데이터 정합성, cache/DB 일관성, messaging semantics, retry, 중복 처리
- 실패 처리와 cleanup — **실패·미검증을 성공처럼 보고하는 경로를 특히 본다**
- 보안, 인증/인가, 비밀값 노출
- API 계약, 하위 호환성, 영속성, 마이그레이션, 리소스 누수
- 테스트 누락, 테스트가 실제 구현을 검증하지 못하는 경우, 과도한 mock
- 불필요한 복잡성과 유지보수성

컴파일 오류나 테스트 실패를 찾는 자리가 아니다. **빌드가 통과하는데도 잘못된 것**을 찾는다.

## 4. finding 작성 규칙

- ID는 `REV-001`, `REV-002` … 로 붙인다. **모델명을 ID에 넣지 않는다**(`SOL-001`, `OPUS-001` 금지).
  리뷰 결과는 Codex/Claude 공용 artifact다.
- 이미 같은 문제가 `REVIEW.md`에 있으면 **새 항목을 만들지 않는다.** 새 근거가 있으면 기존 항목을 보강한다.
- 기존 finding이 틀렸다고 생각해도 **이 단계에서는 삭제하거나 해결 처리하지 않는다.**
  최종 판단은 `apply-review`가 한다. 반론이 있으면 해당 finding에 근거를 덧붙인다.
- Severity는 `Critical` / `Major` / `Minor`.
  - Critical — 데이터 손상, 보안, 또는 **사용자가 실패를 성공으로 믿게 되는** 결함
  - Major — 실제 상황에서 재현되는 오동작이나 요구사항 위반
  - Minor — 품질·일관성·유지보수성

## 5. 파일 위치와 형식

`reviews/<task-id>/REVIEW.md` 하나만 쓴다. `<task-id>`는 `TASK.md`의 Task ID다.
모델별 파일(`codex-*.md`, `claude-*.md`)로 나누지 않는다. TASK가 다르면 디렉터리를 나눈다.

문서 상단에 리뷰 기준 revision을 남긴다.

```md
# Independent Review

Task: <task-id>
Base Commit: <commit>
Current HEAD: <commit>
Working Tree Included: yes/no
Relevant Diff: <검토한 범위>
```

각 finding 형식:

```md
## REV-001

Severity: Major
Status: OPEN

### Location

파일 / 심볼 / 줄 범위

### Problem

실제로 무엇이 잘못되었는가.

### Trigger

어떤 상황에서 발생하는가.

### Impact

사용자 영향, 데이터 오류, 장애 또는 regression.

### Evidence

실제 코드와 테스트를 근거로. 확인한 것과 의심을 구분해서 쓴다.

### Recommendation

가능한 수정 방향. 구현은 하지 않는다.
```

`Status`는 리뷰 단계에서는 항상 `OPEN`이다. 판정은 `RESOLUTION.md`가 한다.

## 6. 완료 보고

- 추가한 REV ID와 severity
- 기존 finding 중 보강한 것과 그 근거
- 결함을 찾지 못한 영역(오탐 방지를 위해 무엇을 확인했는지 남긴다)
- 확인하지 못한 영역과 그 이유

리뷰가 끝나면 거기서 멈춘다. 수정·테스트·커밋·다음 작업 착수는 이 세션의 몫이 아니다.
