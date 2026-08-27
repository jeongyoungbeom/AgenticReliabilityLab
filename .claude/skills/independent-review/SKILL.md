---
name: independent-review
description: 구현이 끝난 변경사항을 새로운 세션에서 독립적으로 코드 리뷰하고 reviews/<task>/REVIEW.md에 finding을 기록할 때 사용한다. 코드는 수정하지 않는다.
---

이 프로젝트의 canonical 독립 리뷰 workflow는 다음 파일이다.

`.agents/skills/independent-review/SKILL.md`

작업을 시작하기 전에 그 파일을 읽고 절차를 그대로 따른다. 이 adapter에는 규칙을 복제하지 않는다.

주의: 이 skill을 쓰는 세션은 `HANDOFF.md`와 기존 `reviews/<task>/REVIEW.md`를 **먼저 읽지 않는다.**
`TASK.md` + 요구사항 + 실제 코드부터 독립적으로 검토한 뒤에 읽는다.
