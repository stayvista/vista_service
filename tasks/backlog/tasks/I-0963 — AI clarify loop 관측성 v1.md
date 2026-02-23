# I-0963 — AI clarify loop 관측성 v1

## Goal
보완질문(clarify loop)이 실제로 추천 품질 개선에 기여하는지 운영에서 검증할 수 있도록 이벤트/지표를 추가한다.

## Metrics
- `ai_widget_clarify_click_total`
- `chat_search_handoff_clarify_question_count`
- `chat_search_handoff_clarify_suggested_total`

## Scope
- 위젯 telemetry 화이트리스트에 `ai_widget_clarify_click` 추가
- 대시보드 패널
  - handoff 보완질문 제안 비율
  - 제안 대비 클릭률(clarify CTR)
  - clarify 이후 handoff filter_count / confidence 변화
- 런북 점검 항목
  - clarify 제안 과다 시 규칙 튜닝 가이드

## Acceptance Criteria
- clarify 이벤트가 route/source 기준으로 집계된다
- handoff 품질 지표와 clarify 클릭 지표를 함께 비교할 수 있다
- 추천 품질 저하 구간에서 clarify 루프가 동작하는지 확인 가능하다
