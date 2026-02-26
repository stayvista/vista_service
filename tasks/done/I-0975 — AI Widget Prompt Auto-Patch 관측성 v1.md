# I-0975 — AI Widget Prompt Auto-Patch 관측성 v1

## Goal
프롬프트 자동 보정이 실제 추천 전환율 개선으로 이어지는지 계량적으로 확인한다.

## Metrics
- `ai_widget_prompt_autopatch_total`
- `ai_widget_prompt_autopatch_count_total{count}`
- `ai_widget_prompt_autopatch_field_count`
- `ai_widget_search_handoff_total`
- `ai_widget_view_results_total`

## Scope
- autopatch 사용률(전체 프롬프트 대비) 대시보드 추가
- 자동 보정 필드 수(1/2/3)별 handoff 전환율 비교
- autopatch 사용 후 결과보기/재요청 비율 추적

## Acceptance Criteria
- autopatch 사용량과 강도 분포를 일별로 확인할 수 있다
- autopatch 강도별 검색 전환 성과를 비교할 수 있다
