# U-0963 — AI handoff 보완질문 칩 UX v1

## Goal
AI 위젯에서 handoff 결과가 애매할 때, 사용자가 재입력 없이 한 번에 후속 질문을 눌러 정확도를 높일 수 있도록 보완질문 칩 UX를 제공한다.

## Scope
- handoff payload의 `clarify_questions[]` 파싱
- handoff 패널에 보완질문 칩 렌더링
- 보완질문 클릭 시 즉시 AI 재질문 실행
- 보완질문/후속질문/검색 핸드오프 전 과정에서 `source_types` 범위 연속성 유지
- 이벤트 로깅 추가
  - `ai_widget_clarify_click`

## Acceptance Criteria
- 보완질문이 있을 때 최대 3개 칩이 표시된다
- 칩 클릭 시 같은 세션 context를 유지한 채 후속 추천 요청이 전송된다
- 기존 followup 질문, 필터칩, 검색 핸드오프 동작에 회귀가 없다
