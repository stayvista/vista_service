---
title: "StayVista 기술 개발기 72: [핵심] 메트릭 상관분석 패턴 - 증상에서 원인까지 빠르게 좁히는 방법"
slug: "72-grafana-dashboard-correlation"
series: "StayVista 기술 개발기"
order: 72
prev_slug: "71-telemetry-event-contract"
next_slug: "73-alerting-burn-rate"
status: "publish-ready"
excerpt: "회귀 분석의 핵심은 차트를 많이 보는 것이 아니라, 증상 지표와 원인 지표를 고정된 순서로 연결해 보는 것입니다. StayVista는 도메인별 상관분석 경로를 명시해 디버깅 시간을 줄였습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 72: [핵심] 메트릭 상관분석 패턴 - 증상에서 원인까지 빠르게 좁히는 방법

## 한 줄 요약
같은 지표라도 "어떤 순서로" 보느냐에 따라 원인 도달 시간이 크게 달라집니다. 그래서 도메인별 상관분석 순서를 고정했습니다.

## 상관분석 규칙
분석 순서를 다음처럼 통일했습니다.

1. 사용자 체감 증상 지표 확인
2. 같은 시간 구간의 실패/거절 지표 확인
3. 마지막으로 원인 후보(큐, fallback, 충돌) 지표 확인

## 도메인별 상관분석 예시
### 1) Chat 응답 지연
- 증상: `chat_ttfb_ms`, `chat_stream_duration_ms`
- 중간 원인: `chat_llm_fail_total`, `llm_reject_rate`
- 근본 원인 후보: `chat_llm_budget_decision_total`, `llm_timeout_count`

### 2) 검색 품질 저하
- 증상: `search_latency_ms`, 결과 수 감소
- 중간 원인: `search_opensearch_errors_total`, `search_opensearch_empty_fallback_total`
- 근본 원인 후보: OpenSearch fallback 비율 증가, 필터 과다 적용(`search_filter_usage_total`)

### 3) 예약 실패 증가
- 증상: hold/confirm 실패율 증가
- 중간 원인: `booking_overbooked_total`, `booking_confirm_inventory_conflict_total`
- 근본 원인 후보: 재고 차감 SQL 충돌, hold 만료 시점 경계

## 시간 구간을 고정한 이유
지표마다 윈도우가 다르면 잘못된 인과관계를 보기 쉽습니다. 로컬 분석에서는 다음 기준을 고정했습니다.

- 즉시 반응 확인: 5분
- 완만한 변화 확인: 30분
- 누적 변화 확인: 1시간

## 구현 자산
- 지표 수집: 각 도메인 서비스의 `MeterRegistry` 기록
- 부하 생성: `services/loadtest/k6/*.js`
- 분석 기준식: `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`

파일 이름에 `alert`가 남아 있어도, 실제 활용 목적은 로컬 회귀 판정식 재사용입니다.

## 기술적으로 중요한 포인트
- 지연 지표만 보면 원인을 놓치기 쉽고, 실패 지표만 보면 체감 문제를 놓칩니다.
- 증상/원인 지표를 쌍으로 고정해야 같은 실험을 반복했을 때 같은 결론이 나옵니다.
- 분석 순서를 문서로 남기면, 이후 아티클/테스트에서도 같은 기준으로 비교할 수 있습니다.
