# B-0459 — Load Test (M2 Max SLO) + 대시보드/회귀

## Goal
M2 Max 기준 현실적인 SLO를 정하고, 부하/스파이크/캐시-hit 시나리오로 회귀 테스트 가능하게 만든다.

## Targets (초기 제안)
- LLM off: p95 < 250ms
- LLM on(stream): TTFB p95 < 500ms, complete p95 < 2000ms
- hard timeout: 5s

## Deliverables
- k6 scripts:
  - steady (예: 50~200 RPS)
  - spike (짧은 시간 10배)
  - cache-hit (반복 질문)
- Grafana dashboard:
  - p50/p95/p99 latency
  - llm_queue_depth, reject_rate, degrade_rate
  - cache hit rates

## Acceptance Criteria
- staging/local에서 k6 실행 문서화
- 결과 리포트(그래프/요약)가 artifacts로 남음
