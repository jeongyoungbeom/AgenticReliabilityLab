# Current Task - target-onboarding-h3

Task ID: `target-onboarding-h3`
Updated: 2026-09-29
Status: completed
Independent Review: not-required

## Goal

`TARGET_ONBOARDING_V1.md`의 H3 범용 Profile 등록을 구현한다. H0-H2의 Eventful Commerce 호환 경로와 기존 미커밋 변경은 보존한다.

## Requirements

1. 등록한 origin과 고정 CIDR 전송만 사용해 제한된 OpenAPI 문서와 Harness V1 manifest를 읽는다. 미지원 버전, 잘못된 매핑, 두 문서의 불일치를 거부한다.
2. 문서의 business method/path/role로 완전한 버전별 DRAFT Profile을 제안한다. 사람이 해당 버전을 명시 활성화하기 전에는 쓰기 권한을 부여하지 않는다.
3. 기존 quick registration, SSRF 방어와 역할별 런타임 자격증명 점검을 유지한다. 범용 제안 활성화 직전 계약을 다시 읽고 변경을 거부한다.
4. H4 후보 생성, H5 AI/UI, H6 두 번째 타겟 실행은 이 단계 범위에서 제외한다.

## Acceptance Criteria

1. 서로 다른 두 OpenAPI/manifest 계약에서 검토 가능한 Profile을 제안하고 확인 후에만 활성화한다.
2. operation 불일치, 미지원 버전과 활성화 전 manifest 변경은 활성화 전에 실패한다.
3. 기존 quick registration과 관련 Profile/credential 테스트, 변경 모듈 컴파일과 정적 검사가 통과한다.
