# B-0931 — Taxonomy v1

## Goal
필터 항목을 정규화해서 유지보수 가능한 형태로 만든다.

## Tables
- property_type(code,label_ko,label_en)
- amenity(code,label_ko,label_en,group_code)
- payment_option(code,label_ko,group_code)
- theme(code,label_ko)
- brand(id,name)

- property_amenity(property_id, amenity_code)
- property_brand(property_id, brand_id)
- property_theme(property_id, theme_code)

## Acceptance Criteria
- 필터 key가 하드코딩되지 않고 facets로 제공 가능
