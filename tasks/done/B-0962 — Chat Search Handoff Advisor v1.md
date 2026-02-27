# B-0962 — Chat Search Handoff Advisor v3

## Goal
AI 컨시어지 응답을 “문장형 답변”에서 끝내지 않고, 검색으로 즉시 전환 가능한 필터 추천 결과(`search_handoff`)를 서버에서 생성한다.

## Scope
- `ChatSearchHandoffAdvisor` 신규 추가
- 입력 문장 + 슬롯(`intent`, `companions`, `budget`, `days`) 기반 필터 추천 규칙 구현
  - `amenities`, `themes`, `payment_options`, `family_options`, `beach_options`, `max_price`
- RAG POI hit 기반 `nearby_attractions` 필터 자동 생성
  - `doc_id=poi:{id}` 파싱
  - 요청 도시와 POI 도시가 다르면 필터 제외
- 사용자 선호 프로필(`PreferenceProfileSnapshot`) 기반 개인화 필터 부스팅
- handoff confidence 계산 로직 추가
- 추천 필터 중복 제거 및 상한 제한(최대 6개)
- intent/검색 hit를 기반으로 `recommended_source_types` 계산
- `ChatService` 응답의 `context_used.search_handoff`에 summary + recommended_filters + recommended_source_types 포함
- 단위 테스트 추가(가족/미식/무힌트 fallback 시나리오)
- 메트릭 추가
  - `chat_search_handoff_total{result}`
  - `chat_search_handoff_filter_count`
  - `chat_search_handoff_confidence`
  - `chat_search_handoff_source_type_count`
  - `chat_search_handoff_profile_applied_total`

## Response Contract
- `context_used.search_handoff.summary`: 추천 설명 문구
- `context_used.search_handoff.confidence`: 추천 신뢰도(0~1)
- `context_used.search_handoff.profile_applied`: 프로필 개인화 반영 여부
- `context_used.search_handoff.rationale[]`: 상위 근거 문장
- `context_used.search_handoff.clarify_questions[]`: 조건 보완용 추천 질문(최대 3개)
- `context_used.search_handoff.search_patch`
  - `city`
  - `days`
  - `companions`
- `context_used.search_handoff.city`: 대화에서 확정된 검색 도시(있을 때)
- `context_used.search_handoff.days`: 대화에서 확정된 일정 일수(있을 때)
- `context_used.search_handoff.companions`: 대화에서 확정된 동행 타입(있을 때)
- `context_used.search_handoff.recommended_filters[]`
  - `key`
  - `value`
  - `label`
  - `reason`
  - `source` (`rule` | `profile`)
- `context_used.search_handoff.recommended_source_types[]`
  - `PROPERTY`, `PACKAGE`, `TICKET`, `POI`
  - 우선순위 기반 최대 3개 반환

## Acceptance Criteria
- 동일 입력에서 추천 필터가 안정적으로 재현된다(비결정성 없음)
- 동일 도시에 대한 POI hit가 있으면 `nearby_attractions` 필터가 생성된다
- 다른 도시 POI hit는 handoff 필터에 포함되지 않는다
- 추천 필터가 비어도 fallback summary는 항상 제공된다
- 프로필 신호가 충분할 때 개인화 필터가 응답에 반영된다
- 추천 payload에 confidence/profile_applied/rationale가 일관되게 포함된다
- 추천 payload에 source scope 후보(`recommended_source_types`)가 포함된다
- 컨시어지 UI는 해당 payload를 이용해 필터칩 표시 및 검색 핸드오프를 수행할 수 있다
