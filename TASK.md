# Current Task - target-onboarding-h5

Task ID: `target-onboarding-h5`
Updated: 2026-09-29
Status: completed
Independent Review: not-required

## Goal

`TARGET_ONBOARDING_V1.md`의 H5 AI 추가 추천과 검토 UI를 구현한다. 기본 후보는 모델 상태와 관계없이 계속 사용할 수 있어야 한다.

## Requirements

1. 같은 활성 Profile에 속한 확정 Snapshot 여러 개의 출처를 보존해 모델 입력으로 묶고, 승인된 operation·역할·관측 capability와 기본 후보·이전 실행의 안전한 요약을 제공한다.
2. 모델 출력은 기존 schema·Profile validator에 더해 출처 근거, 합성 fixture, reset 가능성, 결정적 fingerprint 중복 검사를 거친다. 유효한 새 명세만 PENDING_APPROVAL로 저장하고 거부 이유도 보존한다.
3. 기본 후보와 AI 추가 후보를 한 작업 화면에서 구분한다. Snapshot 검토·확인, 명세의 근거·API 순서·입력·위험·판정 검토, 별도 승인·실행을 연결한다. 모델 실패 또는 새 후보 부재를 명확히 표시한다.
4. 기본 후보와 기존 7개 파일럿 경로를 보존한다. H6 두 번째 실제 타겟 구축과 독립 리뷰는 이 작업 범위에 넣지 않는다.

## Acceptance Criteria

1. Stub 모델로 유효·중복·범위 밖·잘못된 JSON·모델 부재와 다중 Snapshot을 검증하고 유효한 제안만 승인 대기 상태임을 확인한다.
2. UI에서 기본 후보와 AI 후보의 생성·검토·승인·실행 경로를 검증한다. 모델이 없어도 기본 후보를 사용할 수 있다.
3. 관련 백엔드·프런트엔드 테스트와 빌드·정적 검사가 통과한다. 실제 모델로 기본 목록과 다른 유효한 제안 여부를 평가하고, 없으면 결과를 그대로 기록한다.

## Verification

- H5 생성 API 통합 테스트에서 다중·미확정 Snapshot, 유효·중복·범위 밖 제안, 잘못된 JSON, 모델 부재, Profile 변경과 저장된 생성 기록을 확인했다.
- 생성 API, H4 discovery/실행 관련 테스트와 `detekt`가 통과했다. 프런트엔드 AI/기본 후보 화면 테스트와 production build가 통과했다.
- 로컬 `gpt-oss:20b`를 합성 `/health`·`/orders` 계약으로 평가해 기본 후보와 다른 `GET /orders` 제안 1개를 받았다. 해당 응답을 통합 테스트에 넣어 유효성 검사와 승인 대기 저장을 확인했다.
- 실제 두 번째 타겟의 AI 후보 실행·정리는 H6에서 검증한다. 이번 평가는 합성 계약과 로컬 모델에서 수행했다.
