# A-0611 — Admin: POI 데이터 운영(등록/수정/비공개) + 지도 미리보기

## Goal
운영자가 POI를 관리할 수 있어야 추천 품질을 통제할 수 있다.

## Scope
- POI CRUD(최소: name/category/lat/lng/address/active)
- 지도 미리보기(마커로 위치 확인)
- 비공개(active=false) 처리 시 추천에서 즉시 제외

## Acceptance Criteria
- 비공개 처리된 POI는 /nearby 결과에 포함되지 않음
- 좌표 변경 시 지도 미리보기 즉시 갱신
