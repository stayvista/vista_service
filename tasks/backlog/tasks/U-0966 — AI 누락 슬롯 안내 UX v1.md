# U-0966 — AI 누락 슬롯 안내 UX v1

## Goal
AI handoff 응답에 누락 슬롯이 있을 때, 사용자가 무엇을 더 입력해야 정확도가 올라가는지 위젯 패널에서 즉시 확인할 수 있게 한다.

## Scope
- handoff payload의 `clarify_required`와 `missing_slots[]`를 UI 상태로 저장
- handoff 패널에 “추가 확인 필요” 안내 영역 추가
- 누락 슬롯 라벨(도시/일정/동행/예산/선호 옵션) 매핑 적용
- 검색 handoff telemetry에 `clarify_required`, `missing_slot_count` 전송

## Acceptance Criteria
- 누락 슬롯이 존재하면 패널에 한국어 라벨로 표시된다
- 누락 슬롯이 없으면 안내 영역이 노출되지 않는다
- 검색 handoff 이벤트에서 누락 슬롯 개수와 clarify 여부가 서버로 전송된다
