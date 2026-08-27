@AGENTS.md

# Claude 전용 메모

## 이 파일의 역할

공통 작업 지침(`AGENTS.md`)만 모든 Claude 세션에 자동으로 불러온다.
**역할별 workflow skill은 자동으로 불러오지 않는다.** 구현용 절차가 독립 리뷰 세션에 섞이면
reviewer가 구현자의 관점을 물려받아 독립성이 사라지기 때문이다.

규칙 원본은 `.agents/` 한 벌뿐이며 Codex와 공유한다. `.claude/skills/`에는 같은 파일을 가리키는
얇은 adapter만 둔다.

## 세션 시작 시 할 일

1. 이번 세션의 역할이 무엇인지 정한다 — 구현인지, 독립 리뷰인지, 리뷰 반영인지.
2. `AGENTS.md`의 `작업 유형별 Workflow`에서 해당 skill 하나를 읽고 그 절차를 따른다.
   - 구현 → `.agents/skills/develop-with-user/SKILL.md`
   - 독립 리뷰 → `.agents/skills/independent-review/SKILL.md`
   - 리뷰 반영 → `.agents/skills/apply-review/SKILL.md`
3. 그 skill이 지시하는 순서대로 문서를 읽는다. 순서를 임의로 바꾸지 않는다.
   특히 **독립 리뷰 세션은 `HANDOFF.md`와 기존 `REVIEW.md`를 먼저 읽지 않는다.**

역할이 분명하지 않으면 `TASK.md`를 읽고 사용자에게 어떤 역할인지 확인한다.

## 문서 역할

```text
TASK.md      = 지금 무엇을 해야 하는가
HANDOFF.md   = 현재 어디까지 되어 있는가
DECISIONS.md = 어떤 설계 판단을 유지해야 하는가
```

`DESIGN.md`~`DESIGN4.md`는 확정된 계약이 아니라 설계 메모다. 다만 그것이 **코드를 정답으로 삼는다는 뜻은 아니다.**

- 설계 문서를 갱신하는 경우는 `TASK.md`, 명시적 요구사항 문서, 또는 사용자의 결정으로 **방향이 의도적으로
  바뀐 때뿐이다.** 이때는 바뀐 결정을 문서에 반영하고, 유지할 판단이면 `DECISIONS.md`에도 남긴다.
- 코드와 문서가 다르다는 사실만으로 코드를 정답으로 간주해 문서를 고치지 않는다.
  그 차이는 먼저 **요구사항 불일치나 구현 결함일 가능성**으로 다룬다. 어느 쪽이 맞는지 확인되기 전에는
  문서도 코드도 바꾸지 말고 차이를 사용자에게 알린다.

자세한 구분은 `AGENTS.md`의 `정보별 Source of Truth`를 따른다.

## 환경 메모

- 이 저장소는 Windows + OneDrive 위에 있다. `git checkout --`가 unlink 권한 오류로 실패할 수 있으므로
  파일을 되돌릴 때는 삭제 대신 내용을 직접 고친다.
- 백엔드 검증은 저장소 루트에서 `.\gradlew.bat check`. 소스가 그대로면 `up-to-date`로 끝나는데
  이는 직전 성공 실행의 입력과 현재 소스가 같다는 뜻이다. 강제 재실행은 `--rerun-tasks`.
- 프런트 검증은 **`frontend` 디렉터리에서** `npm test` / `npm run build`. 루트에는 `package.json`이 없다.
