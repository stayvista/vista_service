# I-0961 — AI handoff 품질 지표 v1

## Goal
AI 컨시어지 고도화 이후 추천 품질을 운영에서 측정할 수 있도록 handoff 전용 지표를 정의한다.

## Metrics
- `chat_search_handoff_total{result=filters|empty}`
- `chat_search_handoff_filter_count`
- `chat_search_handoff_confidence`
- `chat_search_handoff_profile_applied_total`

## Scope
- 메트릭 대시보드 패널 추가
  - 평균 confidence
  - profile_applied 비율
  - empty handoff 비율
- 경보 초안
  - empty 비율 급증
  - confidence 급락
- 런북 업데이트
  - profile 누락 시 점검 항목(session_id/user_id 전달, redis profile hit율)

## Acceptance Criteria
- 일 단위로 handoff quality 추이를 확인할 수 있다
- profile 적용률 변화를 배포 전/후 비교할 수 있다
- empty handoff 급증 시 대응 절차가 문서화되어 있다
