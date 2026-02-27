# U-0964 — AI source scope 연속성 & search_patch 호환 파싱 v1

## Goal
AI 위젯의 대화/보완질문/후속질문/검색 핸드오프 전 과정에서 source scope를 일관되게 유지하고, 백엔드 handoff 스키마 변경(search_patch 도입)에 안전하게 호환한다.

## Scope
- `source_types` 정규화 로직 추가 (허용 타입/중복 제거/fallback)
- 사용자 프롬프트(텍스트 입력)에서 source scope 키워드 자동 추론
  - 숙소/패키지/티켓/주변(POI)
  - 추론 성공 시 active scope 즉시 전환
- source scope 문자열(`PROPERTY,POI` 등) 생성 유틸 추가
- telemetry 전송 시 source scope 필드 포함
- handoff payload 파싱 시 신규/구버전 동시 지원
  - 신규: `search_handoff.search_patch.city/days/companions`
  - 구버전: `search_handoff.city/days/companions`
- handoff payload의 `recommended_source_types[]` 파싱 및 위젯 active scope 자동 동기화
- handoff 패널에 “추천 범위(숙소/티켓/패키지/주변 추천)” 표시
- handoff payload의 `clarify_required` + `missing_slots[]` 파싱
- handoff 패널에 “추가 확인 필요(누락 슬롯)” 안내 표시

## Acceptance Criteria
- 빠른 프롬프트/보완질문/후속질문 클릭 후에도 source scope가 예상대로 유지된다
- 텍스트 입력 프롬프트에 “티켓/패키지/숙소/맛집” 키워드가 포함되면 source scope가 자동 전환된다
- handoff payload가 신규/구버전 중 어떤 형태여도 검색 패치가 정상 적용된다
- source scope가 비정상 값이면 UI에서 fallback 스코프로 안전하게 동작한다
- handoff 응답에 추천 source scope가 있으면 다음 질문/검색에 자동으로 반영된다
- handoff 응답에 누락 슬롯이 포함되면 UI에서 명확히 안내되며 검색 handoff telemetry에 반영된다
