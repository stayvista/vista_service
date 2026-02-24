# I-0984 — AI Widget Context Insert 관측성 v1

## Goal
사용자가 어떤 검색조건을 자주 삽입하는지 관측해 프롬프트/칩 배치를 최적화한다.

## Scope
- 이벤트: `ai_widget_context_insert_click`
- 핵심 지표
  - `ai_widget_context_insert_field_total{field}`
  - `ai_widget_context_insert_scope_total{field,scope}`
- 분석 관점
  - field별 클릭 분포(도시/일정/인원/예산/범위)
  - scope별(field+scope) 삽입 패턴
  - 컨텍스트 삽입 이후 추천 요청 전환율

## Acceptance Criteria
- field별 삽입 사용량을 분리 집계할 수 있다
- scope 결합 지표로 실제 추천 범위별 입력 패턴 분석이 가능하다
- 대시보드에서 삽입→추천요청 전환 퍼널을 구성할 수 있다
