# B-0930 — Facet Engine v1 (server-driven)

## Goal
도시별로 다른 인기 검색 조건/지역 설명/주변 명소를 서버가 내려줘 UI가 하드코딩 없이 렌더링.

## API
- GET /v1/search/facets?place_id=...

## Response (MVP)
- popular_filters[]
- districts[{id,name,blurb,count}]
- nearby_attractions[{poi_id,name,count}]
- brands[]
- amenity_groups[]

## Acceptance Criteria
- 서울/부산/제주에서 서로 다른 facet 제공
