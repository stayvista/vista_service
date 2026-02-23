# U-0965 — AI 추천 범위 자동 동기화 UX v1

## Goal
AI handoff에서 반환된 `recommended_source_types`를 위젯의 다음 질문/후속 질문/검색 CTA에 자동 적용해, 대화 의도와 검색 범위가 어긋나지 않도록 한다.

## Scope
- handoff 파싱 결과에 `recommended_source_types[]` 노출
- 위젯 active source scope를 handoff 추천 범위로 자동 전환
- “추천 범위” 안내 문구를 handoff 패널에 표시
- 검색 handoff telemetry(`ai_widget_search_handoff`)는 자동 전환된 scope를 사용

## Acceptance Criteria
- 맛집/명소 의도 대화 후 다음 질문에서도 POI 중심 scope가 유지된다
- 숙소 중심 의도 대화 후 검색 CTA telemetry에 PROPERTY scope가 포함된다
- handoff 추천 범위가 없거나 invalid일 때 기존 scope/fallback이 유지된다
