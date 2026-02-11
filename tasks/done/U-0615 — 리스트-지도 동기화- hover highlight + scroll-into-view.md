# U-0615 — 리스트/지도 동기화: hover highlight + scroll-into-view

## Goal
지도와 리스트가 “하나의 탐색 경험”처럼 동작하게 한다.

## Spec
- 리스트 hover → 지도 마커 강조(색/크기/halo)
- 지도 마커 hover → 리스트 카드 강조
- 클릭 시:
  - 리스트는 scroll-into-view
  - 지도는 flyTo + 선택 마커 고정

## Acceptance Criteria
- 100개 결과에서도 hover/선택이 부드럽게 동작(프레임 드랍 최소)
