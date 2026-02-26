# I-0971 — AI Widget Feedback 품질 지표 v1

## Goal
AI 컨시어지 고도화 이후 답변 만족도와 재요청 패턴을 계량화해 품질 개선 우선순위를 결정한다.

## Metrics
- `ai_widget_answer_feedback_total{feedback}`
- `ai_widget_regenerate_click_total`
- `ai_widget_search_blocked_total`
- `ai_widget_scope_hint_click_total`

## Scope
- 대시보드 패널
  - 긍정/부정 피드백 비율
  - 재생성 비율(재요청률)
  - 검색 차단 비율(누락 슬롯 발생률)
- 알람 초안
  - 부정 피드백 급증
  - 재생성 비율 임계 초과

## Acceptance Criteria
- 일 단위로 피드백 분포와 재생성률을 확인할 수 있다
- 검색 차단 비율로 슬롯 수집 UX 병목을 파악할 수 있다
- 알람 발생 시 담당 Runbook으로 연결된다
