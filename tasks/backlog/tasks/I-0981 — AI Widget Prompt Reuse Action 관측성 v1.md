# I-0981 — AI Widget Prompt Reuse Action 관측성 v1

## Goal
최근 요청 재사용을 `초안 불러오기`와 `즉시 실행`으로 분리 관측해 실제 재사용 전환 효율을 분석한다.

## Scope
- 이벤트: `ai_widget_prompt_reuse_click`
- 핵심 지표
  - `ai_widget_prompt_reuse_total`
  - `ai_widget_prompt_reuse_action_total{action}`
  - `ai_widget_prompt_reuse_autopatch_total{autopatch}`
- 분석 관점
  - `action=draft` 대비 `action=submit` 비율
  - 즉시 실행(`submit`) 경로의 자동 패치 적용률
  - 재사용 이후 검색 전환율(연계 이벤트 기준)

## Acceptance Criteria
- 재사용 클릭 시 action 태그가 포함된 지표가 누적된다
- action(`draft|submit`) 기준 분리 집계가 가능하다
- 대시보드에서 즉시 실행 도입 이후 전환 기여를 비교할 수 있다
