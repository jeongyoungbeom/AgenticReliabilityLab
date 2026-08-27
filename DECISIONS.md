# DECISIONS

> 여러 세션에 걸쳐 **유지해야 하는 확정 설계 판단**만 기록한다.
> 상세 설계는 여기에 복사하지 않고 원본 문서를 참조한다. 이 문서는 두 번째 DESIGN 문서가 아니다.
> 명시적인 요구사항(`TASK.md`, 요구사항 문서)과 충돌할 수 없다. 충돌하면 요구사항이 이긴다.

각 결정의 Status는 `Accepted` / `Superseded` / `Open` 중 하나다.

---

## D001 — DESIGN 문서는 계약이 아니라 설계 메모다

Status: Accepted

### Decision

`DESIGN.md`~`DESIGN4.md`는 설계 과정과 상세 참고 자료다. 현재 제품 계약의 최종 기준으로 취급하지 않는다.

다만 이것이 **코드를 정답으로 삼는다는 뜻은 아니다.**

- 설계 문서를 갱신하는 경우는 `TASK.md`, 명시적 요구사항 문서, 또는 사용자의 결정으로 **방향이 의도적으로
  바뀐 때뿐이다.** 그때 바뀐 결정을 문서에 반영하고, 여러 세션에 걸쳐 유지할 판단이면 이 문서에도 남긴다.
- 코드와 문서가 다르다는 사실만으로 코드를 정답으로 간주해 문서를 고치지 않는다. 그 차이는 먼저
  **요구사항 불일치나 구현 결함일 가능성**으로 다루고, 어느 쪽이 맞는지 확인되기 전에는 양쪽 다 바꾸지 않는다.

### Reason

문서가 코드보다 앞서 작성됐고, 여러 차례 방향이 좁혀지면서 문서와 구현이 갈라졌다.
문서를 계약으로 취급하면 이미 폐기된 계획으로 되돌아가는 사고가 반복된다.
반대로 문서를 코드에 무조건 맞추면 요구사항 위반과 구현 결함이 "설계가 바뀐 것"으로 세탁된다.
두 실패를 모두 막으려면 갱신의 근거가 **의도된 결정**인지부터 확인해야 한다.

### Detailed Specification

- `DESIGN4.md`(파일럿 계약 초안), `TARGET_REQUIREMENTS.md`, `TEST_SPEC.md`

---

## D002 — Profile은 Target의 실행 allowlist다

Status: Accepted

### Decision

실행은 Profile에 선언된 것만 한다. `base-url`, 안전 환경, 허용 origin/CIDR, OpenAPI 문서 경로,
허용 operation·역할, Harness 4경로, 실행 상한만 선언하며 그 밖은 호출하지 않는다.
Swagger fetch도 허용 origin 안에서만 하고 redirect·다른 host·외부 `$ref`를 따라가지 않는다.
Swagger에 있다는 이유로 자동 실행하지 않고, 임의 POST를 실행하지 않는다.

### Reason

URL 하나를 입력받아 임의 내부망·임의 API를 호출하면 SSRF와 실데이터 파괴 위험이 그대로 열린다.
편의를 위해 이 경계를 넓히면 제품의 안전 주장이 무너진다.

### Detailed Specification

- `DESIGN4.md` 2·3절, `TARGET_REQUIREMENTS.md`

---

## D003 — Harness API 4개는 정식 테스트의 필수 계약이다

Status: Accepted

### Decision

`state` / `reset` / `fault` / `fault release`가 모두 선언되지 않으면 모든 후보는 `NOT_READY`다.
UI는 Harness를 선택 사항처럼 보여 주지 않고 **무엇이 빠졌는지** 알린다.
실행 게이트는 비변경 `GET state` preflight로만 열고, 상태를 바꾸는 POST는 진단 목적으로 호출하지 않는다.
서버도 실행 직전에 같은 preflight를 다시 수행하므로 브라우저 상태만 신뢰하지 않는다.

### Reason

Harness 없이 가능한 것은 연결·응답·권한 확인 수준이다. 상태 초기화, 결과 판정, 장애 재현, 정리 검증은
모두 Harness가 있어야 성립한다. 진단을 위해 reset/fault를 호출하면 진단이 곧 실데이터 파괴가 된다.

### Detailed Specification

- `TARGET_REQUIREMENTS.md` 3·5절, `DESIGN4.md` 4절

---

## D004 — 간편 등록은 입력 3개 + ARL이 생성한 완전한 Profile

Status: Accepted

### Decision

등록 입력은 `name` / `baseUrl` / `environment` 세 개다. 표준 Swagger·Harness 경로, 역할 프로필, 관측 필드,
실행 상한은 ARL이 내부에서 **완전한 Profile로 생성**하고 그 완전본을 버전으로 고정한다.
URL은 순수 origin만 허용하고, 허용 CIDR은 추측하지 않고 등록 시점의 DNS 해석 결과만 `/32`(또는 `/128`)로 넣는다.
환경은 `LOCAL` / `TEST`만 허용한다. 부분 merge API는 만들지 않는다 —
사용자는 생성된 완전본을 고급 YAML 편집의 출발점으로 받아 필요한 곳만 고친 뒤 정상 검증·등록 경로를 다시 통과한다.

### Reason

반복 기본값을 사용자에게 묻는 순간 파일럿이 성립하지 않는다. 동시에 숨은 기본값이 보이지 않으면
사용자는 무엇이 실행될지 알 수 없으므로, 생성된 전체를 그대로 보여 주고 편집 가능하게 만든다.

### Detailed Specification

- `HANDOFF.md`의 Completed 1·5단계, `docs/history/HANDOFF-2026-08-27.md`

---

## D005 — 실행 allowlist는 code-owned이며 현재 SideProject 모양이다

Status: Open

### Decision

`QuickTargetProfileFactory`의 `allowedCalls`가 eventful-commerce 경로를 코드에 고정하고 있다.
allowlist가 코드 소유인 것은 D002에 따른 **의도된 안전 결정**이다.
다만 표준값이 SideProject 모양이라는 점은 미결이다. 일반화(고급 YAML의 operations 덮어쓰기)와
파일럿 계약으로 못 박기 중 무엇을 택할지 정해지지 않았다.

### Reason

다른 모양의 Target은 Swagger를 읽어도 allowlist와의 교집합이 비어 실행 후보가 나오지 않는다.
이것을 배선 버그로 오해하고 안전 경계를 푸는 수정이 반복될 위험이 있어 명시해 둔다.

---

## D006 — Target 테스트 자격증명은 분리·비영속·쿠키 세션

Status: Accepted

### Decision

UI의 ARL 접근 토큰과 Target seller/buyer/harness 테스트 토큰은 분리한다.
Target 토큰은 서버 프로세스 메모리에만 두고 DB·YAML·Evidence·로그·응답·prompt에 남기지 않는다.
세션 식별자는 HttpOnly / SameSite=Strict 쿠키로만 오간다(응답 본문에 넣지 않는다).
만료는 **절대 TTL이 아니라 유휴 TTL 8시간**이다. 세션 수 상한 100이며 가장 오래 안 쓴 것부터 축출한다.
`cookie-secure` 기본값은 `false`로 남긴다.

### Reason

절대 TTL은 작업 중에 토큰을 잃게 만들어 제거했고, 대신 아무도 안 쓴 세션만 회수하도록 되살렸다.
`cookie-secure`를 기본 `true`로 두면 브라우저가 `http://192.168.x.x`의 Secure 쿠키를 거부해
LAN에서 자격증명이 통째로 동작을 멈춘다. https를 앞에 두는 배포는 설정으로 켤 수 있게 노출했다.

### Detailed Specification

- `arl.target-credential.*` (`src/main/resources/application.yaml`)

---

## D007 — UI는 진입점만 줄이고 백엔드 기능은 남긴다

Status: Accepted

### Decision

4단계에서 프런트 화면 32개를 실제로 삭제했지만 백엔드 API·서비스·테스트는 유지했다.
기능이 없어진 것이 아니라 사용자 진입점이 없어진 것이다. 고급 YAML 입력은 표준을 벗어난 Target에 필요하므로 남긴다.

### Reason

파일럿의 실패 원인은 기능 부족이 아니라 화면 과잉이었다. 반대로 백엔드까지 지우면 되돌리기 비용이 커지고
7단계 이후 기능이 근거를 잃는다.

---

## D008 — 파일럿 결과는 세션 단위로 영속하고, 증거는 Test Spec Run이 소유한다

Status: Accepted

### Decision

사람이 명시 승인한 선택 1회를 하나의 파일럿 테스트 세션으로 저장한다.
세션 항목은 **참조와 판정만** 갖고 상세 증거는 기존 Test Spec Run이 소유한다.
같은 Target + Idempotency-Key 재요청은 Target·discovery·preflight를 건드리지 않고 저장된 세션을 재생하며,
같은 키에 다른 요청이면 거부한다. 재기동으로 끊긴 세션은 `RECOVERY_REQUIRED`로 내려 완료로 오인되지 않게 한다.

### Reason

실행 결과가 응답으로만 존재하면 사용자는 화면을 닫는 순간 무엇을 실행했는지 다시 찾을 수 없다.
증거까지 세션에 복제하면 같은 사실이 두 곳에서 갈라진다.

### Known limitation

재기동 복구는 **단일 인스턴스를 전제**한다. 같은 DB에 ARL 두 대가 붙으면 한쪽 부팅이 다른 쪽의 진행 중 세션을 내린다.

---

## D009 — Target 쓰기 테스트는 순차 실행하고 매 실행 전후로 상태를 검증한다

Status: Accepted

### Decision

한 Target에 대한 쓰기 테스트는 순차 실행한다. 매 테스트 전 reset → state 확인, 종료 시 정리와 fault 해제를 검증한다.
정리 검증에 실패한 실행은 성공으로 분류하지 않는다.

### Reason

병렬 실행은 결과 판정을 비결정적으로 만들고, 정리 실패를 성공으로 넘기면 다음 실행이 오염된 상태에서 시작한다.

---

## D010 — 화면은 실패·미검증을 성공처럼 보여 주지 않는다

Status: Accepted

### Decision

판정(`resultOutcome`)과 실행 상태(`status`), 정리 검증(`cleanupVerified`)은 서로 다른 사실이므로
한 개의 색·라벨로 뭉치지 않는다. 값이 없거나(`null`) 검증 대상이 없는 경우를 "확인됨"으로 표시하지 않는다.

### Reason

이 제품의 목적은 신뢰성 판정을 사람에게 정확히 전달하는 것이다.
거짓 안심은 기능 부족보다 나쁘다. 리뷰에서 반복해서 발견된 결함 유형이기도 하다.
