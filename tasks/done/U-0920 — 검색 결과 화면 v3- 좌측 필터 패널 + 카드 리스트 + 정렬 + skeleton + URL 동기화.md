# U-0920 — Search Results v3 (Agoda-like)

## Goal
검색 결과 화면을 Agoda처럼 '좌측 필터 + 리스트'로 고도화한다.

## UI
- 상단: mini searchbar(목적지/날짜/인원)
- 좌측: 필터(카테고리 그룹 + count)
- 중앙: 카드 리스트(사진, 가격, 점수, 배지)
- 정렬: best match / price / rating / distance

## UX
- 필터 변경 시 URL query 갱신
- in-flight 요청 cancel(AbortController)
- skeleton 로딩

## Acceptance Criteria
- 최소 10개 필터가 동작
- 공유 링크로 동일 결과 재현
