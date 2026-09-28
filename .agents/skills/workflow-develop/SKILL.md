---
name: workflow-develop
description: 코드·테스트·설정·빌드·의존성·마이그레이션을 구현, 수정, 리팩터링하거나 버그를 고칠 때 사용한다.
---

# 개발 Workflow

`../../../AGENTS.md`와 저장소의 기존 지침을 우선하고 기존 변경을 보존한다.

## 1. 요구사항과 조사

- 최신 요구사항, 입력/출력, 예외, 제약, 완료 조건을 확인하고 사실과 가정을 구분한다.
- 관련 진입점, 호출 흐름, 데이터 모델, 테스트, 빌드/실행 방법, 현재 구현 상태를 필요한 범위만 확인한다.
- 기존 구조와 컨벤션이 있으면 재사용하고, 없으면 `AGENTS.md`의 개인 기본 컨벤션을 적용한다.
- Java/Spring 프로젝트에 기존 규칙이 없으면 `.workflow/profiles/java-spring.md`를 추가로 참고한다.
- `.workflow/TASK.md`는 active일 때만, `.workflow/HANDOFF.md`는 active TASK와 Task ID가 일치할 때만 참고한다. `.workflow/DECISIONS.md`는 현재 작업에 적용되는 active decision만 참고한다.

## 2. 설계

새 프로젝트는 요구사항과 제약을 기준으로 기술 스택과 최소 구조를 짧게 결정한다.
기존 프로젝트는 기존 기술 스택과 구조를 우선하며, 복잡한 작업만 구현 전에 추가 설계를 짧게 정리한다.

- 완료 조건과 변경 영역
- 데이터 흐름과 주요 상태
- transaction / consistency / concurrency / failure handling
- 필요한 성능·확장성 고려
- 테스트 방법

기술을 먼저 선택하지 않는다. 실제 문제를 정의한 뒤 가장 단순한 해결책을 선택한다.

## 3. 구현

1. 가장 작은 end-to-end 핵심 흐름
2. 필요한 validation / exception / transaction / constraint
3. 핵심 비즈니스 테스트
4. 요구사항에 필요한 concurrency / idempotency / performance / retry
5. 관련 테스트·빌드·실행
6. 변경한 코드 자체 리뷰와 불필요한 코드 제거

시간 제한이 있으면 runnable한 핵심 요구사항을 먼저 완성한다.
요청 범위 밖 대상이나 되돌리기 어려운 자원을 건드려야 한다면 `AGENTS.md`의 안전과 권한 기준에 따라 먼저 사용자에게 묻는다.

## 4. 검증

- 변경 요구사항, 직접·간접 영향받는 호출 흐름, 확인할 실패 위험에 맞춰 필요한 범위만 검증한다. 근거 없이 전체 회귀나 관련 없는 파이프라인 시나리오를 기본으로 실행하지 않는다.
- 변경과 영향 관계가 없는 영역까지 범위를 확장하지 않는다. 공유 API·공통 함수·계약을 변경했다면 수정하지 않은 caller라도 실제 영향받는 범위는 검증 대상에 포함한다.
- 관련 테스트가 통과하고 변경된 흐름의 correctness가 확인되면 검증을 종료한다.
- 변경 모듈의 compile/build처럼 변경 코드를 실행 가능한 상태로 확인하는 검증은 필요하면 수행한다. 전체 저장소 수준의 테스트·빌드는 공통 모듈, 빌드 설정, 의존성, 광범위한 계약 변경처럼 실제 영향 범위가 넓을 때만 수행한다.
- 동일 코드 상태에서 이미 성공한 검증은 새로운 변경이나 구체적인 의심 근거가 없으면 반복하지 않는다. 단, race condition, flaky behavior, 비결정적 동작, 성능 변동처럼 반복 실행 자체가 검증 방법인 경우는 필요한 횟수만 반복한다.
- 정상·경계·실패 상황은 변경 범위와 실제 위험에 필요한 경우만 확인한다.
- 데이터 변경은 해당 변경과 관련된 경우에만 원자성, 중복 요청, race condition을 확인한다.
- 대량 조회는 해당 변경과 관련된 경우에만 index, pagination, N+1, memory 사용을 확인한다.
- 실행하지 않은 검증을 PASS라고 쓰지 않는다.

## 5. 세션 전환과 종료

- 여러 세션을 이어가면 `.workflow/HANDOFF.md`를 현재 active TASK와 같은 Task ID로 활성화해 현재 상태, 실제 검증, 남은 위험, 다음 작업만 짧게 남긴다.
- 다음 세션이 실제 상태를 확인해 인계를 받으면 `.workflow/HANDOFF.md`를 inactive로 바꾼다.
- 지속해야 할 중요한 판단이 있으면 `.workflow/DECISIONS.md`에 decision별 Status/Scope로 기록한다.
- active TASK의 `Independent Review: not-required`면 완료 조건과 검증을 마친 뒤 TASK를 completed로 바꾼다.
- active TASK의 `Independent Review: required`면 구현·검증이 끝나도 TASK를 active로 유지하고 새 세션에서 `workflow-independent-review`로 넘긴다.
- active TASK가 없는 한 세션 작업은 workflow 문서를 억지로 만들거나 활성화하지 않는다.
