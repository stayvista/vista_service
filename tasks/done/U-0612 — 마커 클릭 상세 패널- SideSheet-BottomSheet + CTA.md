# U-0612 — 마커 클릭 상세 패널: SideSheet/BottomSheet + CTA

## Goal
지도 마커 클릭 시 상세 패널을 열어 “왜 추천/어디/어떤 곳”인지 빠르게 확인하고 전환(숙소/티켓)으로 연결한다.

## UI Spec
- Desktop: 우측 지도 위에 SideSheet
- Mobile: BottomSheet(드래그 확장)
- 내용:
  - title, category, 거리, 주소(있으면)
  - 사진 carousel(있으면)
  - '길찾기'(외부지도 링크), '저장', '티켓 보기/숙소 보기'(연관 상품이 있을 때)

## Data
- `GET /v1/poi/{poiId}` 호출로 상세 로딩(필요 시)
- 목록 응답에 `preview` 필드가 있으면 클릭 즉시 렌더 후 상세는 lazy-load

## Acceptance Criteria
- 패널 열기/닫기 애니메이션
- 상세 로딩 실패 시 graceful 메시지 + 재시도 버튼
