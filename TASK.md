# Current Task — pilot-ux-simplification

> 이 문서는 **지금 무엇을 해야 하는가**의 기준이다.
> 코드가 이 문서와 다르게 구현돼 있으면 코드가 정답이 아니라 **요구사항 불일치**를 의심한다.
> 현재 진행 상태는 `HANDOFF.md`, 유지해야 하는 설계 판단은 `DECISIONS.md`를 본다.

Task ID: `pilot-ux-simplification`
Review 디렉터리: `reviews/pilot-ux-simplification/`
Updated: 2026-08-27

## Goal

SideProject 같은 Target을 **처음 보는 사용자가 화면만 보고 한 사이클을 끝낼 수 있게** 만든다.

```text
Target 등록(이름·URL·환경 3개)
→ Swagger 자동 발견
→ 역할별 Target 자격증명 입력·preflight
→ Harness 계약 확인 후 실행 후보 노출
→ 고정 템플릿 선택·명시 승인·순차 실행
→ 저장된 세션 결과 확인
```

## Requirements

1. 등록 입력은 `name` / `baseUrl` / `environment` 세 개다. 나머지 표준값은 ARL이 완전한 Profile로 생성하고
   그 완전본을 버전으로 고정한다. 표준에서 벗어나는 Target만 고급 YAML로 덮어쓴다.
2. 실행은 Profile에 선언된 경로만 호출한다. URL 하나를 받았다고 임의 내부망이나 임의 API를 호출하지 않는다.
3. Harness `state` / `reset` / `fault` / `fault release` 네 경로가 모두 선언되지 않으면 모든 후보는 `NOT_READY`이고,
   **어느 API가 빠졌는지** 화면에 보여 준다. 비변경 `GET state` preflight가 성공해야 실행 선택지를 노출한다.
   상태를 바꾸는 POST는 진단 목적으로 호출하지 않는다.
4. ARL 접근 토큰과 Target 테스트 토큰은 분리한다. Target 토큰은 서버 메모리에만 두고 DB·YAML·로그·응답·
   Evidence에 남기지 않는다. 세션은 HttpOnly 쿠키로 식별하고 새로고침을 넘겨 복구된다.
5. UI는 사용자가 실제로 쓰는 경로만 남긴다. 백엔드 기능을 지우는 것이 아니라 진입점을 줄인다.
6. 사람이 명시 승인한 선택 1회는 하나의 세션으로 영속된다. 같은 Idempotency-Key 재요청은 Target을 다시 건드리지 않고
   저장된 결과를 재생한다. 재기동으로 끊긴 세션이 완료된 것처럼 보이면 안 된다.
7. **화면이 실패·미검증을 성공처럼 보여 주지 않는다.** 이 제품의 존재 이유이므로 다른 편의보다 우선한다.

## Non-goals

- 7단계 오류 진단 모델, 8단계 SideProject 실제 Docker 통합 검증. 이번 TASK 범위가 아니다.
- LLM 기반 후보 생성·회귀·AI 해석 화면 확장.
- 실행 allowlist의 일반화(다른 모양의 Target 지원). `DECISIONS.md` D005의 미결 사항이다.
- 삭제한 화면(수동 명세 등록·승인, 지식 스냅샷, 분석 워크스페이스 등)의 복구.

## Acceptance Criteria

1. 1–6단계 구현이 위 Requirements를 만족한다. **(현재: 구현 완료)**
2. `reviews/pilot-ux-simplification/REVIEW.md`의 각 REV finding이 현재 코드에서 재검증되고,
   `ACCEPTED / REJECTED / ALREADY_RESOLVED / DEFERRED / STALE` 중 하나로 판정된다.
3. ACCEPTED finding이 수정되고, 그 수정마다 회귀 테스트가 있다. 특히 Requirement 7을 지키는 테스트.
4. 백엔드 `.\gradlew.bat check` 실패 0 / detekt findings 0, 프런트 `npm test` 전부 통과, `npm run build` 성공.
   결과를 `HANDOFF.md`의 Verification에 실행 시점과 함께 기록한다.
5. `reviews/pilot-ux-simplification/RESOLUTION.md`가 작성된다.
6. 사용자 승인 뒤 1/2/3/4/5/6단계로 나눠 커밋한다. **승인 없이 커밋하지 않는다.**

## Relevant Context

- 현재 상태와 미커밋 범위: `HANDOFF.md`
- 유지해야 하는 설계 판단: `DECISIONS.md`
- 처리해야 할 리뷰: `reviews/pilot-ux-simplification/REVIEW.md`
- 파일럿 계약 초안(계약 확정본 아님): `DESIGN4.md`
- Target이 갖춰야 할 것: `TARGET_REQUIREMENTS.md`
- 명세 스키마와 실행 계약: `TEST_SPEC.md`
- 과거 개발 이력: `docs/history/HANDOFF-2026-08-27.md`
