# I-0985 — AI Widget Context Sync 관측성 v1

## Goal
검색 조건 변경 이후 AI 재추천 재진입 행동을 관측해, 조건 동기화 UX의 전환 기여도를 파악한다.

## Scope
- 이벤트: `ai_widget_context_sync_click`
- 핵심 지표
  - `ai_widget_context_sync_mode_total{mode}`
  - `ai_widget_context_sync_scope_total{mode,scope}`
- 분석 관점
  - mode별 사용 분포(`rerun_last_prompt` vs `context_only`)
  - scope별 동기화 액션 사용 패턴
  - 조건 변경 감지 노출 대비 재추천 전환율

## Acceptance Criteria
- mode별 동기화 액션 사용량을 분리 집계할 수 있다
- scope 결합 지표로 추천 범위별 조건 동기화 패턴 분석이 가능하다
- 대시보드에서 조건 변경 감지→재추천 전환 퍼널을 구성할 수 있다
