# B-0521 — Eval Harness v2: 정답/근거/카드 품질 자동 채점기

## Goal
Golden set 기반으로 AI 컨시어지 품질을 정량화하고 PR 단위 회귀를 막는다.

## Scope
- golden dataset: 숙소/티켓/체험/패키지/주변/일정/비교 질문 포함
- 채점 항목:
  - slot_accuracy
  - citation_coverage (cards with >=1 source)
  - safety_violation_rate (PII/정책/환각 단정)
  - route_stability (LLM/template/clarify 라우팅 비율)
  - latency budgets (p95/p99)

## Deliverables
- `services/eval/` 모듈
  - `EvalRunner` (실행)
  - `Scorers` (slot/citation/safety/latency)
  - `ReportWriter` (HTML + JSON)
- `./gradlew :services:evalSmoke` / `:services:evalFull`

## CI
- PR: evalSmoke 30 cases
- nightly: evalFull 500+ cases

## Acceptance Criteria
- PR에서 smoke 통과가 merge gate
- 실패 시 어떤 케이스가 무엇 때문에 실패했는지 diff 출력
