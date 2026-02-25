# I-0962 — AI handoff detail telemetry 대시보드 v1

## Goal
AI 컨시어지 핸드오프 품질을 “사용 여부” 수준이 아니라 “추천 강도/개인화 효과” 단위로 관측한다.

## Metrics
- `ai_widget_handoff_filter_count`
- `ai_widget_handoff_confidence`
- `ai_widget_handoff_profile_applied_total{applied=true|false}`
- `ai_widget_handoff_scope_total{scope}`

## Scope
- telemetry ingest payload 확장
  - `filter_count` (0~12)
  - `handoff_confidence` (0~1)
  - `handoff_profile_applied` (bool)
  - `source_type_scope` (PROPERTY/TICKET/PACKAGE/POI 조합)
- 위젯 handoff 이벤트 전송 시 상세 필드 포함
- 대시보드 패널 추가
  - 평균/분위 confidence
  - 평균 filter count
  - profile applied 비율
  - source scope별 handoff 분포
- 런북에 임계치 기반 점검 항목 추가

## Acceptance Criteria
- handoff 이벤트에서 상세 필드가 누락/범위오류면 validation으로 차단된다
- 배포 후 confidence/filter_count/profile_applied 추세를 일 단위로 확인할 수 있다
- scope별 handoff 품질 비교가 가능하다
- 기존 `ai_widget_event_total` 퍼널 지표와 함께 drill-down 분석이 가능하다
