# B-0612 — POI 상세 API + 외부 링크(지도) + 연관 상품 힌트

## Goal
지도 마커 클릭 후 상세 패널을 채울 정보를 제공한다.

## API
- `GET /v1/poi/{id}`
- response:
  - `id, name, category, lat, lng, address?, description?, images[]?`
  - `links`: `{naver?, google?, osm?}` (외부 지도 링크)
  - `related`: `{properties[], products[]}` (있다면)

## Acceptance Criteria
- 존재하지 않는 id는 404
- images가 없으면 빈 배열(프론트 단순 처리)
