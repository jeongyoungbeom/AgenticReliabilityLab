# HANDOFF

> 이 문서는 **현재 어디까지 되어 있는가**만 전달한다.
> 해야 할 일은 `TASK.md`, 유지해야 하는 설계 판단은 `DECISIONS.md`,
> 독립 리뷰 결과는 `reviews/<task>/REVIEW.md`에 있다.
> 이 문서는 코드나 요구사항보다 우선하지 않는다. 여기 적힌 완료 주장과 실제 코드가 다르면 **코드가 사실**이다.
> 2026-08-27 이전의 전체 개발 이력은 `docs/history/HANDOFF-2026-08-27.md`에 보존돼 있다.

Updated: 2026-08-27

## Current Goal

SideProject(eventful-commerce)를 대상으로 한 파일럿 흐름을 **사용자가 실제로 쓸 수 있는 수준까지 좁히는 것**.
Target 등록 → Swagger 자동 발견 → 역할별 자격증명 → Harness 게이트 → 고정 템플릿 실행 → 저장된 결과 확인.

현재 TASK는 `TASK.md`를 본다.

## Repository State

- Branch: `master`
- HEAD: `0424fe7` (`fix: align SideProject pilot setup with harness overlay`)
- Working tree: **대량 미커밋 상태다.** UX 단순화 1–6단계 전체가 아직 커밋되지 않았다.
  - 추적 파일 68개 변경 (`git diff --ignore-all-space --stat` 기준 +1530 / −5398).
    삭제가 큰 이유는 4단계 UI 축소에서 프런트 컴포넌트·API 모듈 32개를 실제로 지웠기 때문이다.
  - 미추적 항목 다수 — 6단계 백엔드(`targetdiscovery/{domain,application/port,infrastructure}`,
    `V29__pilot_test_sessions.sql`), 프런트 `PilotTestSessionResultsPanel`,
    1–5단계 신규(`QuickTargetProfileRegistrationWorkflow`, `EffectiveTargetProfile*`,
    `TargetCredentialSessionCookie`/`Registry` 등), 그리고 이번 workflow 정리분.
  - `.agents/skills/SKILL.md`는 **삭제된 상태**이고 `.agents/skills/develop-with-user/SKILL.md`가 그 자리를 대신한다.
- 다음 세션이 조심할 것:
  - 이 미커밋 변경은 **여러 세션에 걸친 실제 작업물이다.** 되돌리거나 덮어쓰지 않는다.
  - 이 저장소는 OneDrive 위에 있어 `git checkout --`가 unlink 권한 오류로 실패할 수 있다.
    파일을 되돌려야 하면 삭제 대신 내용을 직접 고친다.
  - 커밋은 사용자 승인 뒤에 1/2/3/4/5/6단계로 나눠서 한다.

## Completed

UX 단순화 8단계 계획 중 **1–6단계 구현 완료**. (7–8은 착수하지 않았다.)

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 간편 등록 — `name`/`baseUrl`/`environment` 3개로 표준 Profile 생성·Swagger 자동 발견·즉시 활성화 | 구현 완료 |
| 2 | Target 목록 정리 — 사용자 등록 Target만 노출, 이름·URL·환경·문서 수 표시 | 구현 완료 |
| 3 | 세션 자격증명 — HttpOnly 쿠키 세션, 유휴 TTL 8시간, 새로고침 복구 | 구현 완료 |
| 4 | UI 축소 — 상단 nav 3개(테스트/결과/세션 종료), 미사용 화면 32개 삭제 | 구현 완료 |
| 5 | Harness 4개 계약 게이트 + 생성된 전체 YAML을 고급 편집 출발점으로 제공 | 구현 완료 |
| 6 | 파일럿 테스트 세션 저장 모델 — 승인한 선택 1회를 세션으로 영속, 멱등 재생, 재기동 복구 | 구현 완료 |

1–5단계는 독립 리뷰 2회와 그 반영까지 끝났다. **6단계는 독립 리뷰를 받았고 반영은 아직이다**
(`reviews/pilot-ux-simplification/REVIEW.md`).

## Verification

Last verified at working tree of 2026-08-27 12:00 UTC (backend) / 같은 날 프런트 실행:

- 백엔드 `.\gradlew.bat check` — **343 tests / skipped 0 / failures 0 / errors 0**, detekt findings 0
  (`build/test-results/test/*.xml` 49개, `build/reports/detekt/*` 기준)
- 프런트 `npm test` (`frontend` 디렉터리에서 실행) — **10 files / 38 tests 통과**
- 프런트 `npm run build` — 성공 (`frontend/dist`)

**신선도:** 이 검증 이후 애플리케이션 코드(`src/`, `frontend/src/`)는 변경되지 않았다.
이후 변경은 문서와 `.agents`/`.claude` skill 구조뿐이다. 따라서 위 결과는 현재 애플리케이션 코드에 유효하다.
**리뷰 반영으로 코드를 고치는 순간 이 표는 무효가 된다.** 다시 실행하고 이 절을 갱신한다.

실행 방법 메모:

- 백엔드는 저장소 루트에서 `.\gradlew.bat check`. 소스가 그대로면 `7 actionable tasks: 7 up-to-date`로 끝나는데
  이는 "안 돌았다"가 아니라 직전 성공 실행의 입력과 현재 소스가 같다는 뜻이다. 강제 재실행은 `--rerun-tasks`.
- 프런트는 **반드시 `frontend` 디렉터리에서** `npm test` / `npm run build`. 루트에는 `package.json`이 없다.

## Current Risks

1. **6단계 독립 리뷰 finding이 미반영이다.** `reviews/pilot-ux-simplification/REVIEW.md`에
   **REV-001~REV-016 (Critical 3 / Major 6 / Minor 7)**.
   Critical 3건은 정리 미확인 항목이 통과처럼 보임(REV-001), 중단된 세션이 항목 0건으로 남음(REV-002),
   아무것도 실행되지 않은 세션이 정리 확인됨으로 저장됨(REV-003)이다.
2. **손으로 UI를 눌러 본 검증이 한 번도 없다.** 자동 테스트만 통과했다.
3. **실제 SideProject Docker Target에 대한 통합 검증이 없다.** Harness 4개 계약은 소스를 읽어 확인만 했다.
4. **간편 등록의 실행 allowlist가 SideProject 모양에 맞춰 코드에 고정돼 있다.**
   다른 모양의 Target은 Swagger를 읽어도 실행 후보가 비게 된다. 일반화 여부는 미결이다(`DECISIONS.md` D005).
5. Testcontainers PostgreSQL이 Windows 호스트에서 간헐적으로 끊겨 동시성 테스트가 한 번 실패한 적이 있다.
   단독 재실행하면 통과하는 **환경 flake**이며 코드 문제가 아니다.
6. `README.md`가 4단계 이전의 5탭 화면을 설명하고 있을 가능성이 있다. 커밋 전에 확인 대상이다.

## Next

`TASK.md`의 Acceptance Criteria를 따른다. 요약하면:

1. `apply-review`로 `REVIEW.md`의 REV finding을 현재 코드에서 재검증하고 타당한 것만 수정한다.
2. 수정한 범위에 대한 회귀 테스트를 추가한다.
3. 백엔드 `check` + 프런트 `npm test` / `npm run build`를 다시 실행하고 이 문서의 Verification을 갱신한다.
4. `reviews/pilot-ux-simplification/RESOLUTION.md`를 작성한다.
5. 사용자 승인 뒤 1/2/3/4/5/6단계로 나눠 커밋한다.

7단계(오류 진단 모델)와 8단계(SideProject 실제 통합 검증)는 **새 `develop-with-user` 세션에서** 시작한다.
apply-review 세션은 현재 TASK를 끝내는 데까지만 한다.

## Relevant Files

읽는 순서:

1. `TASK.md` — 지금 무엇을 해야 하는가
2. `DECISIONS.md` — 유지해야 하는 설계 판단
3. `reviews/pilot-ux-simplification/REVIEW.md` — 처리해야 할 finding (independent-review 세션은 코드를 먼저 본 뒤에 읽는다)

주요 코드:

- 간편 등록: `targetprofile/application/QuickTargetProfileRegistrationWorkflow.kt`
- 적용 설정·YAML 렌더: `targetprofile/application/EffectiveTargetProfile*.kt`
- 자격증명 세션: `targetcredential/{api/TargetCredentialSessionCookie.kt,application/*}`
- 파일럿 후보·실행·세션: `targetdiscovery/**`
- 프런트 화면: `frontend/src/App.tsx`, `frontend/src/features/profiles/*`, `frontend/src/features/specifications/*`

배경 문서(필요할 때만): `DESIGN4.md`(파일럿 계약 초안), `TARGET_REQUIREMENTS.md`(Target 요구사항),
`TEST_SPEC.md`(명세 스키마), `docs/history/HANDOFF-2026-08-27.md`(과거 이력).
