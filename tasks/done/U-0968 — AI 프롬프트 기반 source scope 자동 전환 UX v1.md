# U-0968 — AI 프롬프트 기반 source scope 자동 전환 UX v1

## Goal
사용자가 챗 입력창에 목적(티켓/패키지/숙소/맛집)을 직접 입력하면, AI 위젯이 source scope를 자동 전환해 추천 정확도를 높인다.

## Scope
- 텍스트 입력 제출 시 프롬프트 키워드 기반 source scope 추론
- 추론 성공 시 `activeSourceTypes` 즉시 동기화
- `ai_widget_prompt_submit` telemetry에 전환된 scope 반영
- 추론 실패 시 기존 active scope 유지 (fallback)

## Acceptance Criteria
- “티켓 추천해줘” 입력 시 prompt submit이 `TICKET` scope로 전송된다
- “숙소랑 티켓 추천” 입력 시 복수 scope가 유지된다
- 키워드 없는 입력은 기존 scope를 유지한다
- UI 동작이 실패해도 검색/추천 플로우는 중단되지 않는다
