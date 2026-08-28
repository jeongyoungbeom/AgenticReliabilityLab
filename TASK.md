# Current Task — error-diagnosis-model

> 이 문서는 **지금 무엇을 해야 하는가**의 기준이다.
> 코드가 이 문서와 다르게 구현돼 있으면 코드가 정답이 아니라 **요구사항 불일치**를 의심한다.
> 현재 진행 상태는 `HANDOFF.md`, 유지해야 하는 설계 판단은 `DECISIONS.md`를 본다.

Task ID: `error-diagnosis-model`
Updated: 2026-08-28

## Goal

실행·preflight·정리 과정의 실패를 사용자가 즉시 조치할 수 있도록, 실패를 **단계 / 한국어 설명 /
예상 원인 / 다음 행동 / 기술 정보**로 구조화해 저장·표시한다. 기술 정보에는 토큰, Authorization 값,
응답 본문 등 민감값이 남지 않아야 한다.

## Requirements

1. 실패를 발생한 단계와 구분해 영속화한다. 단계별 한국어 설명, 예상 원인, 다음 권장 행동, 안전하게
   축약·정제한 기술 정보를 함께 보존하고 결과 화면에서 보여 준다.
2. 사용자가 영어 예외 코드 한 줄을 해석해야 다음 행동을 알 수 있는 UI가 되면 안 된다. 실패·복구 필요·
   설정 필요 상태마다 사용자가 바로 할 수 있는 다음 행동을 제시한다.
3. HTTP 오류, 예외 메시지, 로그성 기술 정보에서 access token, bearer token, Authorization 헤더 값,
   cookie, Harness key, 응답 본문 및 알려진 민감 키를 제거하거나 마스킹한다. 민감 원문을 DB·Evidence·
   API 응답·브라우저 저장소에 저장하지 않는다.
4. 기존 파일럿 세션의 `PASSED` / `VIOLATED` / `INCONCLUSIVE`, 실행 상태, 정리 검증 및 재기동 복구
   의미를 바꾸지 않는다. 오류 진단은 사실을 보완할 뿐 실패를 성공이나 READY로 바꾸면 안 된다.
5. 간편 등록, Swagger 발견, Harness·역할별 preflight, 템플릿 실행, 세션 결과 화면의 기존 성공 흐름을
   회귀시키지 않는다.

## Non-goals

- 8단계 실제 SideProject Docker 통합 검증의 실행·승인·상태 변경.
- SideProject 제품 코드·보안 모델·운영 환경 변경, 실행 allowlist 일반화(D005), 새 테스트 시나리오 추가.
- 독립 리뷰 실행. 이번 세션에서는 독립 리뷰를 하지 않는다.
- commit·push 또는 이전 1–6단계 변경의 정리·되돌리기.

## Acceptance Criteria

1. 대표 실패(최소 HTTP 인증/권한, 연결·타임아웃, Harness/preflight 실패, 실행 실패)를 단계·한국어 설명·
   예상 원인·다음 행동·기술 정보 구조로 저장하고, 결과 또는 관련 화면에서 확인할 수 있다.
2. 사용자는 영어 코드 한 줄만 보지 않고 바로 조치할 수 있다. 기술 세부 사항은 필요할 때만 보조 정보로
   제공되며 결과 판정과 모순되지 않는다.
3. 토큰, Authorization 값, cookie, Harness key 및 응답 본문이 테스트·저장소 파일·DB 기록·API 응답·
   Evidence·브라우저 저장소에 남지 않음을 회귀 테스트로 확인한다.
4. 영향받는 백엔드·프런트 테스트와 빌드를 실행하고, 기존 1–6단계 성공 흐름의 관련 테스트가 통과한다.
5. 자체 리뷰를 수행하고 `HANDOFF.md`에 구현 범위·검증 결과·남은 위험을 갱신한다. 독립 리뷰·commit·push는 하지 않는다.

## Relevant Context

- `DECISIONS.md` D003, D006, D008, D009, D010 — Harness 게이트, 자격증명 격리, 결과·세션·정리 표시 계약.
- `src/main/kotlin/**/targetdiscovery/**` — 파일럿 후보·실행·세션·결과 모델.
- `src/main/kotlin/**/targetcredential/**` — 세션 자격증명 및 preflight 경계.
- `frontend/src/features/profiles/*`, `frontend/src/features/specifications/*` — 실패·결과 UI.
- 8단계 Docker 환경 준비는 이미 되었지만, 이 Task의 구현·검증 완료 전에는 실제 Target 실행 단계로 진행하지 않는다.
