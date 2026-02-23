# I-0968 — AI source scope intent-match observability v1

## Goal
프롬프트 의도와 실제 source scope가 얼마나 일치하는지 계측해, AI 컨시어지 추천 범위 오차를 지속적으로 줄인다.

## Scope
- 지표 설계
  - prompt submit scope 분포
  - handoff recommended scope 분포
  - clarify action 클릭 후 scope 전환 분포
- 슬롯/도메인 편중 점검 항목 추가
  - TICKET 요청 대비 PROPERTY 과다 노출
  - POI 요청 대비 PACKAGE 과다 노출
- 런북 점검 절차
  - extractor 키워드 규칙 회귀
  - source scope normalize 계약 불일치 점검

## Acceptance Criteria
- scope 분포를 이벤트 기준으로 대시보드에서 확인할 수 있다
- 의도-범위 불일치가 특정 임계치 이상일 때 추적 가능한 근거가 남는다
- 런북에 점검/완화 절차가 문서화된다
