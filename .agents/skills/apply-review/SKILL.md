---
name: apply-review
description: reviews/<task>/REVIEW.md의 finding을 현재 코드에서 재검증하고 타당한 것만 수정한 뒤 RESOLUTION.md와 HANDOFF.md를 갱신할 때 사용한다.
---

# 리뷰 반영

적용되는 `../../../AGENTS.md`를 우선한다.
이 skill은 **리뷰 내용을 그대로 구현하는 skill이 아니다.** finding을 현재 코드 기준으로 판정하는 것이 먼저다.

## 1. 원칙

- reviewer의 의견을 자동으로 정답 처리하지 않는다. 각 finding을 **실제 코드에서 직접 재검증**한다.
- 타당한 것만 고친다. 거부한 finding은 이유와 근거를 남긴다.
- 원본 `REVIEW.md`를 임의로 삭제하거나 해결된 것처럼 고치지 않는다. 처리 결과는 `RESOLUTION.md`에 쓴다.
- 수정한 동작에는 회귀 테스트를 남긴다. 테스트 없이 "고쳤다"고 보고하지 않는다.

## 2. 순서

1. `AGENTS.md` 확인
2. `TASK.md`와 요구사항 확인
3. 현재 repository와 Git 상태 확인 (`git status`, 관련 `git diff`) — 기존 미커밋 변경을 보존한다
4. `HANDOFF.md`로 현재 진행 상태와 known risk 확인
5. `DECISIONS.md`로 유지해야 하는 판단 확인
6. `reviews/<task>/REVIEW.md` 확인
7. 각 finding을 실제 코드에서 재검증
8. finding별 판정 (아래 3절)
9. `ACCEPTED`만 수정
10. 필요한 회귀 테스트 추가
11. 관련 테스트 실행, 그다음 가능한 범위의 전체 검증
12. `reviews/<task>/RESOLUTION.md` 작성
13. `HANDOFF.md` 갱신 — 특히 `Verification`을 이번 실행 결과로 교체한다

## 3. 판정 상태

- `ACCEPTED` — 실제 문제이며 수정한다.
- `REJECTED` — 리뷰가 잘못됐거나 요구사항에 맞지 않는다. **반드시 이유와 근거를 남긴다.**
- `ALREADY_RESOLVED` — 현재 코드에서는 이미 해결돼 있다. 어디서 해결됐는지 밝힌다.
- `DEFERRED` — 문제는 맞지만 현재 TASK 범위가 아니다. 이유와 후속 작업을 기록한다.
- `STALE` — 리뷰 이후 구현이 바뀌어 finding의 전제가 더 이상 성립하지 않는다.
  **revision이 다르다는 이유만으로 자동 STALE 처리하지 않는다.** 전제가 실제로 사라졌는지 코드로 확인한다.

## 4. RESOLUTION.md 형식

`reviews/<task-id>/RESOLUTION.md`에 쓴다. finding마다:

```md
## REV-001

Status: ACCEPTED

Reason:
실제 코드에서 동일한 문제를 확인했다. (근거)

Changes:
- 파일 / 변경 내용

Verification:
- 추가한 테스트, 실행한 검증과 결과
```

거부·보류도 같은 형식으로 남긴다. `Reason`이 비어 있는 판정은 만들지 않는다.

문서 상단에 판정 기준 revision과 요약을 남긴다.

```md
# Review Resolution

Task: <task-id>
Reviewed Revision: <REVIEW.md 기준>
Applied At: <실제로 반영한 시점의 HEAD 또는 working tree 설명>

Summary: ACCEPTED n / REJECTED n / ALREADY_RESOLVED n / DEFERRED n / STALE n
```

## 5. 검증과 기록

- 변경 범위에 비례해 테스트·빌드·정적 검사를 실행한다. 수행하지 못한 검증을 성공으로 보고하지 않는다.
- 검증 결과를 `HANDOFF.md`의 `Verification`에 **실행 시점과 함께** 기록하고,
  그 이후 코드가 바뀌면 재검증이 필요하다는 사실을 남긴다. 과거 PASS를 현재 상태처럼 표현하지 않는다.

## 6. 범위 제한

이 세션은 **현재 TASK의 리뷰 반영과 검증까지만** 한다.
현재 TASK가 끝나도 다음 TASK로 전환하거나 다음 단계 기능 개발을 시작하지 않는다.
다음 TASK의 확정과 개발 착수는 새 `develop-with-user` 세션에서 한다.

커밋·푸시는 사용자가 명시적으로 요청했을 때만 한다.
