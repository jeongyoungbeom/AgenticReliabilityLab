---
name: apply-review
description: 독립 리뷰가 남긴 reviews/<task>/REVIEW.md의 finding을 현재 코드에서 재검증하고 타당한 것만 반영한 뒤 RESOLUTION.md와 HANDOFF.md를 갱신할 때 사용한다.
---

이 프로젝트의 canonical 리뷰 반영 workflow는 다음 파일이다.

`.agents/skills/apply-review/SKILL.md`

작업을 시작하기 전에 그 파일을 읽고 절차를 그대로 따른다. 이 adapter에는 규칙을 복제하지 않는다.

주의: 리뷰 의견을 자동으로 정답 처리하지 않는다. 각 finding을 실제 코드에서 재검증하고
`ACCEPTED / REJECTED / ALREADY_RESOLVED / DEFERRED / STALE` 중 하나로 판정한다.
