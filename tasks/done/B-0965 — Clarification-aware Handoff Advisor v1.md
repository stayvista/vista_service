# B-0965 — Clarification-aware Handoff Advisor v2

## Goal
AI handoff 추천 신뢰도가 낮거나 조건이 부족할 때, 사용자가 바로 보완할 수 있는 질문(`clarify_questions`)을 함께 제공해 대화 성공률을 높인다.

## Scope
- `ChatSearchHandoffAdvisor`에 보완 질문 생성 로직 추가
  - 도시/일정/동행/예산/필수옵션 누락 기반 질문 생성
  - 신뢰도 낮은 경우 우선순위 질문 fallback
  - intent별 도메인 질문 보강
    - `BUSINESS`: 체크인 시간/교통 우선순위 질문
    - `FOOD`: 결제 정책(무료취소/후지불) 보완 질문
- 응답 payload 확장
  - `context_used.search_handoff.clarify_questions[]`
  - `context_used.search_handoff.clarify_required`
  - `context_used.search_handoff.missing_slots[]`
- 관측 메트릭 추가
  - `chat_search_handoff_clarify_question_count`
  - `chat_search_handoff_clarify_suggested_total`
  - `chat_search_handoff_clarify_required_total{required}`
  - `chat_search_handoff_missing_slot_count`

## Acceptance Criteria
- handoff confidence가 낮거나 핵심 조건 누락이면 보완 질문이 1개 이상 반환된다
- 보완 질문은 중복 없이 최대 3개로 제한된다
- 의도 분류가 가능한 입력에서는 intent 특화 보완질문이 우선 노출된다
- 핵심 누락 슬롯(city/days/companions/budget/preferences)이 응답에 명시된다
- 기존 filter 추천/summary 응답과 회귀 없이 공존한다
