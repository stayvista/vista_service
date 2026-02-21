# B-0940 — AI Search Copilot v1

## Goal
검색 화면에서 AI가 '추천 필터'와 '짧은 설명'을 제공한다.

## API
- POST /v1/ai/search/copilot
  - inputs: place_id, dates, guests, current filters, facets summary, top results summary
  - outputs: recommended_filters[], explanation, llm_used

## Rules
- LLM은 추천/설명만 담당
- 가격/재고/정책은 서버 데이터 기반(LLM 단정 금지)
- timeout/degrade: template로 fallback

## Acceptance Criteria
- LLM down이어도 UI 동작
- 추천 필터 적용 시 URL query 반영
