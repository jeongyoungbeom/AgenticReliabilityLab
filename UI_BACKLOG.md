# UI에 남은 것

선언형 명세 엔진(Phase 17~19)에는 **화면이 하나도 없다.** 백엔드 API는 다 있고
`frontend/src/api/`에 클라이언트조차 없다. 나중에 UI를 붙일 때 필요한 것을 모아 둔다.

기준은 코드다. "백엔드가 이미 주는 것"과 "화면이 새로 만들어야 하는 것"을 갈라 적었다.

스택: React 19 + TypeScript + Vite + Vitest. 상태 관리 라이브러리 없음.
`features/<도메인>/<이름>Workspace.tsx` + `api/<도메인>.ts` 가 기존 관례다.
`App.tsx`의 `WorkspaceView`와 `SectionNav`에 섹션을 추가하는 식으로 붙는다.

---

## 0. 화면을 만들 때 절대 뭉개면 안 되는 것

엔진이 애써 갈라 놓은 구분을 화면이 다시 합치면, 백엔드에서 한 일이 그대로 무효가 된다.
아래는 **표시상의 취향 문제가 아니라 판정의 의미**다.

| 뭉개면 안 되는 것 | 왜 |
|---|---|
| `PASSED` / `VIOLATED` / **`NOT_EVALUATED`** | 판정 불가를 통과처럼 보이게 칠하면 이 도구가 거짓말을 시작한다. 초록으로 칠하지 말 것 |
| `OBSERVATION_MISSING` / `OBSERVATION_INSUFFICIENT` / `EXPRESSION_FAILED` | 각각 수집기·증거·명세를 가리킨다. 셋을 "판정 불가" 한 덩어리로 보여주면 운영자가 **맞는 명세를 고치러 간다** |
| `INCONCLUSIVE` / `PASSED` (trial 수준) | 하나라도 판정 못 하면 그 trial은 통과가 아니다 |
| `근거: 없음` 임계값 | LLM이 지어낸 숫자다. 눈에 띄지 않으면 승인이 무의미해진다 |

`InvariantVerdict.detail`에는 거부 이유 문장이 그대로 들어 있다
("no trace carries both spans" → 수집기, "3 traces started the first span without ever reaching
the second" → 코드). 이 문장을 접어 두거나 잘라내지 말 것. 그게 이 판정의 유일한 행동 지침이다.

---

## 1. 승인 화면 — 가장 중요하다

`DESIGN3.md`의 흐름에서 **사람이 개입하는 유일한 지점**이다. 그리고 승인의 의미가 보통과 다르다.

> 승인 단계는 **"이 테스트를 돌릴까요?"가 아니라 "이 기준으로 판정할까요?"**다.

즉 이 화면은 실행 동의서가 아니라 **판정 기준 검토서**다. 화면 설계가 여기서 갈린다.

### 백엔드가 이미 주는 것 (`GET /api/test-specifications/{id}`)

| 필드 | 쓰임 |
|---|---|
| `unfoundedThresholds` | **`근거: 없음`인 임계값 목록.** 그대로 강조하면 된다 |
| `requiredConfirmation` | 승인 시 사용자가 그대로 입력해야 하는 확인 문구 |
| `risk` | `MODERATE` 이상이면 상태를 바꾸거나 장애를 일으킨다 |
| `category` | 동시성·멱등성·정합성 등 |
| `document` | 명세 원문(JSON). 불변식·예외·워크로드가 여기 있다 |
| `profileVersionActive` | **false면 Profile이 이미 바뀐 것이다.** 승인 화면에 경고가 필요하다 |
| `checksum` | 승인한 내용과 실행할 내용이 같은지 |
| `status`, `approvedBy`, `approvedAt`, `terminalReason` | 상태 흐름 |

### 화면이 만들어야 하는 것

`TEST_SPEC.md` 15절이 강조 항목을 정해 뒀다.

- **`근거: 없음` 임계값** — `unfoundedThresholds`를 그냥 나열하지 말고, 그 값이 쓰인 불변식 옆에 붙여야
  판단이 된다. "10초"라는 숫자 하나가 아니라 "무엇을 10초로 재는가"가 검토 대상이다
- **위험도 `MODERATE` 이상** — 무엇이 바뀌는지
- **장애 주입·인프라 제어 포함 여부** — 무엇이 중지되는지 (Phase 21에서 실제로 실행된다)
- **`예외` 항목** — 무엇을 정상으로 인정하기로 했는지, **누가 언제 추가했는지**
- **예상 소요 시간** — `시행 × (워크로드 + 정리)`. 리셋 1회가 sideProject 기준 약 120초라
  `시행: 20`에 `정리시점: 시행마다`면 40분이다. 승인 전에 보여야 한다

**불변식은 치환된 형태로 보여준다.** 판정 결과의 `condition`에는 이미 치환된 값이 들어 있다
(`dbStock == 10 - successQuantity`). 자리표시자가 남은 조건은 사람이 검산할 수 없다.

---

## 2. 실행과 결과 화면

### 실행 (`POST /api/test-specifications/{id}/runs`)

멱등 키를 받는다. **Target별로 활성 실행 슬롯이 하나뿐이다** — 이미 도는 run이 있으면 거부된다.
화면이 이 거부를 오류가 아니라 상태로 보여줘야 한다.

### run 상태 (`GET /api/test-spec-runs/{id}`)

```
PENDING → RUNNING → COMPLETED / FAILED
                  ↘ RECOVERY_REQUIRED
```

`RECOVERY_REQUIRED`는 특별하다. 실행 중 프로세스가 죽어서 **Target이 어떤 상태인지 모른다**는 뜻이고,
이게 풀리기 전에는 다음 실행이 차단된다. 사용자가 무엇을 해야 하는지 화면이 말해줘야 한다.

### 결과

`TestSpecRunResponse`가 다 준다: `resultOutcome`, `trialsRun` / `trialsViolated` /
`trialsInconclusive`, `cleanupVerified`, trial별 `verdicts`와 `timings`, `resets`.

**시행별 결과를 합치지 말 것.** `20회 중 3회 위반`과 `1회 위반`은 다른 사실이다 —
결함이 얼마나 재현되는지가 거기 담겨 있다. 집계 숫자와 개별 trial을 함께 보여줘야 한다.

`cleanupVerified: false`는 눈에 띄어야 한다. **확인 안 된 리셋은 다음 실행의 판정을 망가뜨린다.**

---

## 3. 트레이스 근거 — Phase 19 완료 기준과 직결

`DESIGN3.md` Phase 19의 완료 기준이 화면 요구사항이기도 하다.

> "재고가 -3"이 아니라 **"3·7·9번이 같은 시각에 예약을 읽었고 반영이 340ms 늦었다"** 가 나온다.

`InvariantVerdict.observedValues`가 지금 이런 문자열을 담는다.

```
[{traceId=t0, ...}, ..., ...] (12 spans across 3 traces)
```

- **`N spans across M traces` 부분을 잘라내지 말 것.** 시간축 판정은 전부 트레이스 단위라,
  `24 spans across 20 traces`와 `24 spans across 3 traces`는 완전히 다른 실행이다.
  요청 20건을 보냈는데 3 트레이스로 나온 통과는 통과가 아니다
- 스팬 목록은 표로 보여줄 만하다 (traceId / name / startMs / endMs / durationMs)
- 타임라인 시각화가 자연스러운 자리다. 겹침과 지연이 판정의 대상이므로 그림이 실제로 값을 한다

> **선행 작업 있음.** 지금 저장되는 것은 위 표시 문자열뿐이고 **스팬 원본은 남지 않는다**(6개째부터
> 사라진다). 타임라인을 그리려면 `StoredTrialResult`에 근거 컬럼을 추가하는 작업이 먼저다.
> `HANDOFF.md`의 "분석 경로 연결"과 같은 작업 단위다.

---

## 4. Profile 화면에 추가될 것

`features/profiles/`는 이미 있다. Phase 18·19에서 Profile에 들어온 것들이 화면에 반영되지 않았다.

- **관측 소스 3종** — `HARNESS_STATE` / `PROMETHEUS` / `TRACE`. 종류마다 필수 항목이 다르다
  (harness는 상대 경로, 나머지는 절대 URL + field별 쿼리)
- **`TRACE` 쿼리의 `${trial}` 자리 표시자** — 없으면 Profile 검증이 거부한다.
  YAML을 손으로 쓰는 사용자가 이 규칙을 모르면 거부 사유만 보게 되므로, 입력 도움말이 필요하다
- **Target 계측 요구** — TRACE 소스를 선언했는데 Target이 `X-ARL-Trial`을 스팬 속성으로 남기지
  않으면 쿼리가 아무것도 매칭하지 않는다. 화면이 이걸 미리 알려주는 편이 낫다
  (자세한 것은 `TARGET_REQUIREMENTS.md`)
- **`ProfileValidationSummary.tsx`가 이미 있다** — 검증 실패 메시지를 보여주는 자리이므로
  새 규칙들의 메시지가 여기로 잘 흘러오는지만 확인하면 될 수 있다

---

## 5. 후속 Phase에서 생길 것

지금 만들 필요 없다. 해당 Phase에서 같이 한다.

| Phase | UI |
|---|---|
| 20 (LLM 제안) | 규칙 기반 목록과 LLM 목록을 **나란히 놓고 비교**하는 화면. `근거: 없음` 강조는 승인 화면과 공유 |
| 21 (장애 주입) | 활성 장애 표시, TTL 잔여 시간, 다음 실행이 왜 거부됐는지 |
| 22 (되먹임) | 오판 신고 → 예외 초안 → 검증 → 승인 흐름. 명세 버전 이력. 저장된 명세의 자동 재실행 상태 |

---

## 6. 이미 있는 것

건드릴 필요 없다. 명세 엔진 화면은 이것들과 **별도 섹션**으로 붙는 편이 맞다 —
기존 흐름(후보 → 계획 → 배치 → 실험 → 분석)과 명세 흐름(등록 → 승인 → 실행 → 판정)은 다른 축이다.

`profiles`, `batches`, `analysis`, `chat` 네 개의 `WorkspaceView`가 있고
그 아래 `SectionNav`로 나뉜다. 명세는 다섯 번째 뷰가 되거나, `batches` 아래 섹션이 된다.
