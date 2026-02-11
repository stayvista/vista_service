# U-0613 — 지도 상호작용 최적화: debounce + abort + search-this-area

## Goal
지도 드래그/줌은 요청 폭주를 유발한다. UX는 즉각적이되 서버/DB를 보호하도록 호출을 제어한다.

## Requirements
- viewport 변경 시 자동 호출 금지(기본)
- 지도 이동 후 버튼 `이 영역에서 재검색(Search this area)`를 노출해 사용자가 트리거
- 옵션: auto-search 모드(실험용)에서는 debounce 400ms 적용

## Networking
- in-flight 요청은 AbortController로 취소
- 동일 파라미터 중복 호출은 react-query dedup으로 제거

## Acceptance Criteria
- 드래그 연속 10초 동안 네트워크 요청이 폭발하지 않음(버튼 트리거 방식)
- auto-search 모드에서도 초당 2회 이하로 제한(debounce)
