# B-0991 — AI Copilot Orchestrator API v1 (search-booking toolchain)

## Goal
AI 응답을 단순 문장 생성이 아닌 툴 호출 기반 오케스트레이션으로 전환해, 검색→필터→예약 진입까지 결정론적으로 연결한다.

## Scope
- 오케스트레이터 엔드포인트 추가 (`/v1/chat/copilot/orchestrate`)
- 툴 라우팅
  - `search_properties`
  - `get_property_detail`
  - `get_price_calendar`
  - `check_availability`
- 세션 상태 모델 표준화
  - destination/date_range/guests/budget/preferences/constraints
- 응답 스키마 표준화
  - `answer`
  - `actions[]` (apply_filters, open_property, retry_with_patch)
  - `evidence[]`
  - `confidence`

## Acceptance Criteria
- 동일 입력/세션 상태에서 동일 툴 시퀀스가 재현 가능하다
- 오케스트레이터 응답만으로 프론트가 추천 텍스트+실행 액션을 동시에 렌더링할 수 있다
- 툴 호출 실패 시 degrade 응답(재시도/대체 액션)이 표준 스키마로 반환된다
- request_id/trace_id 기준으로 툴 호출 체인이 로그에서 추적 가능하다
