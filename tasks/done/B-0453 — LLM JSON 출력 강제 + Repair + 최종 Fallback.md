# B-0453 — LLM JSON 출력 강제 + Repair + 최종 Fallback

## Goal
LLM의 응답을 프론트가 바로 렌더 가능한 구조화 JSON으로 강제하고, 파싱 실패가 장애로 이어지지 않게 repair/fallback을 완비한다.

## Output Contract (v2)
- `assistant_text: string`
- `cards: array`
  - `{type, id, title, price?, city?, why, sources:[{doc_id,title,url?,snippet?}]}`
- `followups: string[]`
- `context_used: object`
- `llm_used: boolean`
- `debug?: object` (dev only)

## Flow
1) LLM 호출: system prompt에 **JSON only** + schema 제시
2) strict parse/validate (`StructuredChatParser`)
3) 실패 시 repair prompt 1회 후 재파싱
4) 그래도 실패 → `TemplateResponder` (RAG 결과 기반)로 응답 생성

## Observability
- `structured_parse_fail_count`
- `structured_repair_success_rate`
- `fallback_due_to_parse_rate`

## Tests
- 정상 JSON 응답 파싱
- 잘못된 JSON → repair → 성공
- 잘못된 JSON → repair 실패 → fallback

## Acceptance Criteria
- 200회 호출에서 schema 위반으로 5xx 발생 0건
- 실패해도 API 스키마는 항상 유효
