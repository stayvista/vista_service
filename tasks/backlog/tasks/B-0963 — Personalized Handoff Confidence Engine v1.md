# B-0963 — Personalized Handoff Confidence Engine v1

## Goal
AI 컨시어지가 단순 규칙 추천을 넘어 사용자 선호 프로필을 반영해 필터를 제안하고, 추천 신뢰도/근거를 함께 제공하도록 고도화한다.

## Scope
- `PreferenceProfileSnapshot`를 `ChatSearchHandoffAdvisor` 입력으로 연결
- profile tag weight 기반 필터 매핑(가족/커플/맛집/자연/비즈니스/반려 등)
- 추천 필터별 source(`rule`/`profile`) 태깅
- 추천 confidence(0~1) 계산 및 응답 payload 포함
- rationale(상위 근거 1~3개) 생성 및 응답 payload 포함
- 메트릭/테스트 보강

## Response Contract
- `context_used.search_handoff.confidence`
- `context_used.search_handoff.profile_applied`
- `context_used.search_handoff.rationale[]`
- `context_used.search_handoff.recommended_filters[].source`

## Acceptance Criteria
- 프로필 강한 신호(weight >= 2)가 있으면 handoff 필터에 개인화 추천이 포함된다
- 동일 입력에서 confidence가 안정적으로 계산된다
- 메트릭에서 profile 적용 비율과 평균 confidence를 확인할 수 있다
- 기존 handoff 규칙/검색 핸드오프 동작에 회귀가 없다
