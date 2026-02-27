# U-0962 — AI handoff 검색 패치 자동 반영 v1

## Goal
AI 도우미가 제안한 도시/일정/동행 정보를 “조건으로 숙소 검색” 버튼 클릭 시 실제 검색 입력값에 자동 반영한다.

## Scope
- handoff payload에서 검색 패치 파싱
  - 신규: `search_patch.city/days/companions`
  - 구버전 호환: 루트 `city/days/companions` fallback
- 검색 실행 시 현재 검색 컨텍스트에 patch 적용
  - 도시 변경: `city`, `place_id`, `place_label` 동기화
  - 일정 반영: `days` 기반 `check_out` 보정
  - 동행 반영: companions preset으로 guest state 보정
- 위젯 UI에 “적용 예정” 문구 표시

## Acceptance Criteria
- 현재 검색이 서울이어도 대화에서 부산을 명시하면 검색 진입 시 부산으로 이동한다
- 일정/동행 패치가 유효한 범위에서만 적용된다(비정상 값 무시)
- 기존 handoff 필터칩 선택/해제 동작에 회귀가 없다
