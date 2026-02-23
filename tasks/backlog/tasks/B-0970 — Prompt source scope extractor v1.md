# B-0970 — Prompt source scope extractor v1

## Goal
사용자 메시지에서 탐색 도메인(숙소/패키지/티켓/POI)을 자동 추론해, AI 컨시어지의 검색 범위를 의도와 일치시키고 오탐 추천을 줄인다.

## Scope
- `ChatRoutingPolicy.extractSlots`에 프롬프트 기반 source type 추론 추가
- 추론 규칙
  - 숙소 계열 키워드 -> `PROPERTY`
  - 패키지/번들 키워드 -> `PACKAGE`
  - 티켓/입장권/공연 키워드 -> `TICKET`
  - 맛집/명소/주변/관광 키워드 -> `POI`
- 메시지 추론 성공 시 context `source_types`보다 우선 적용
- 단위 테스트 추가
  - context override 동작
  - 멀티 source type 추론

## Acceptance Criteria
- “서울 전시 티켓 추천” 입력 시 `slots.sourceTypes`에 `TICKET`이 포함된다
- 메시지에 명시 scope가 있으면 context scope보다 우선 적용된다
- 멀티 키워드 입력 시 복수 source type이 안정적으로 추론된다
