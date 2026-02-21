# B-0900 — Destination Recommendations API v1

## Goal
목적지 입력 패널의 '추천 지역/명소/숙소' 섹션을 서버가 제공한다.

## API
- `GET /v1/destinations/recommendations?city_id=...&lang=ko&limit=...`
- response:
  - `districts[]`: {id,name,blurb,rank}
  - `pois[]`: {poi_id,name,category,rank}
  - `featured_properties[]`: {property_id,name,thumb,stars,rank}

## Data
- district table + blurb (기존/추가)
- city_poi_popular
- city_featured_property (curation 가능)

## Acceptance Criteria
- 서울/부산/제주 등 샘플 도시에서 패널이 풍부하게 채워짐
