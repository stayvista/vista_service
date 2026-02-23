# I-0965 — AI source scope drift 알람 v1

## Goal
추천 source scope와 실제 검색 전환 scope가 크게 어긋나는 상황을 조기에 탐지해, AI handoff 품질 저하를 운영 알람으로 감지한다.

## Scope
- 대시보드 비율 지표 정의
  - `ai_widget_handoff_scope_total` vs `ai_widget_source_scope_total{event=ai_widget_search_handoff}`
- Scope drift 비율 계산 룰 정의
  - 예: 15분 이동 윈도우에서 드리프트 비율 20% 초과
- Alert 룰/런북 추가
  - 원인 분류: 파싱 실패, fallback 과다, UI 동기화 누락

## Acceptance Criteria
- handoff scope와 검색 handoff scope mismatch를 지표로 확인 가능하다
- 드리프트 임계치 초과 시 운영 알람이 발생한다
- 런북에 확인 커맨드/API/대응 절차가 포함된다
