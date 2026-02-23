# I-0976 — AI Widget Prompt History 관측성 v1

## Goal
최근 요청 재사용 UX가 실제 재요청률/검색 전환율 개선으로 연결되는지 관측한다.

## Metrics
- `ai_widget_prompt_reuse_click_total`
- `ai_widget_prompt_reuse_rank_total{rank}`
- `ai_widget_prompt_reuse_scope_total{scope}`
- `ai_widget_prompt_submit_total`
- `ai_widget_search_handoff_total`

## Scope
- prompt history 사용률(전체 전송 대비) 대시보드 추가
- rank(1~5)별 클릭 분포와 handoff 전환률 연계
- scope별(prompt reuse 이후) 전환 성과 비교

## Acceptance Criteria
- prompt history 사용 추이를 일별/순위별로 확인할 수 있다
- prompt history 사용 후 검색 handoff 성과를 비교할 수 있다
