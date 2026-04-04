# StayVista 기술 개발기 시리즈 인덱스

## 시리즈 소개
이 디렉터리는 StayVista 백엔드/검색/AI 기능 구현과 로컬 검증 과정을 기술적으로 정리한 연재 초안입니다.

핵심 축은 다음과 같습니다.
- 정합성: 동시성 제어, 멱등성, 상태 전이
- 가용성: fallback, degrade, backpressure
- 확장성: 인덱싱/검색/캐시/오케스트레이션
- 검증성: 메트릭, 임계치 규칙, 부하테스트, 회귀 게이트

## 추천 읽기 순서
1. 코어 트랜잭션/정합성: 00 -> 04 -> 10 -> 11 -> 16 -> 17 -> 18 -> 19
2. 검색/가격/입구 경험: 30 -> 31 -> 33 -> 34 -> 36 -> 39
3. AI 라우팅/안전성/개인화: 50 ~ 69
4. 로컬 계측/회귀 판정: 70 ~ 75
5. 확장 도메인 구현(인증/콘텐츠/쿠폰/주문/문의): 76 ~ 83
6. 후속 확장 심화(피드백/인덱싱/추천/상세쿼리/결제경계): 84 ~ 88
7. 공통 계층 심화(멱등/큐/트래픽가드/API계약/Facet/인증): 89 ~ 94
8. 마이페이지/문의/쿠폰/검색 요청 경계 심화: 95 ~ 100

## 전체 목차
- [00-prologue.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/00-prologue.md)
- [04-idempotency-key.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/04-idempotency-key.md)
- [10-booking-hold-concurrency.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/10-booking-hold-concurrency.md)
- [11-booking-confirm-cancel.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/11-booking-confirm-cancel.md)
- [16-db-retry-executor.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/16-db-retry-executor.md)
- [17-outbox-relay.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/17-outbox-relay.md)
- [18-queue-backpressure.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/18-queue-backpressure.md)
- [19-rate-limit-abuse.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/19-rate-limit-abuse.md)
- [30-search-v2-engine.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/30-search-v2-engine.md)
- [31-opensearch-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/31-opensearch-fallback.md)
- [33-facet-engine-server-driven.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/33-facet-engine-server-driven.md)
- [34-price-calendar-fallback-fx.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/34-price-calendar-fallback-fx.md)
- [36-search-copilot-filters.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/36-search-copilot-filters.md)
- [39-autocomplete-degrade.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/39-autocomplete-degrade.md)
- [50-chat-routing-policy.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/50-chat-routing-policy.md)
- [51-llm-execution-gate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/51-llm-execution-gate.md)
- [52-llm-budget-controller.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/52-llm-budget-controller.md)
- [53-llm-model-registry-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/53-llm-model-registry-fallback.md)
- [54-structured-output-repair.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/54-structured-output-repair.md)
- [55-memory-preference-rerank.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/55-memory-preference-rerank.md)
- [56-citation-verifier-grounding.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/56-citation-verifier-grounding.md)
- [57-chat-safety-guardrails.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/57-chat-safety-guardrails.md)
- [58-pii-redaction-pipeline.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/58-pii-redaction-pipeline.md)
- [59-shadow-run-evaluation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/59-shadow-run-evaluation.md)
- [60-semantic-cache-singleflight.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/60-semantic-cache-singleflight.md)
- [61-prompt-registry-experiment-rollout.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/61-prompt-registry-experiment-rollout.md)
- [62-copilot-orchestrator.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/62-copilot-orchestrator.md)
- [63-handoff-advisor.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/63-handoff-advisor.md)
- [64-rag-index-builder-incremental.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/64-rag-index-builder-incremental.md)
- [65-hybrid-ranker-curation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/65-hybrid-ranker-curation.md)
- [66-chat-streaming-sse-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/66-chat-streaming-sse-contract.md)
- [67-llm-client-timeout-streaming.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/67-llm-client-timeout-streaming.md)
- [68-widget-session-snapshot-hardening.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/68-widget-session-snapshot-hardening.md)
- [69-llm-health-ready-probe.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/69-llm-health-ready-probe.md)
- [70-observability-slo.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/70-observability-slo.md)
- [71-telemetry-event-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/71-telemetry-event-contract.md)
- [72-grafana-dashboard-correlation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/72-grafana-dashboard-correlation.md)
- [73-alerting-burn-rate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/73-alerting-burn-rate.md)
- [74-staging-alert-smoke-release-runbook.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/74-staging-alert-smoke-release-runbook.md)
- [75-k6-loadtest-release-gate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/75-k6-loadtest-release-gate.md)
- [76-auth-session-guardrails.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/76-auth-session-guardrails.md)
- [77-locale-fx-price-pipeline.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/77-locale-fx-price-pipeline.md)
- [78-home-property-content-backing.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/78-home-property-content-backing.md)
- [79-promotion-coupon-claim-concurrency.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/79-promotion-coupon-claim-concurrency.md)
- [80-ticket-order-voucher-flow.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/80-ticket-order-voucher-flow.md)
- [81-package-saga-compensation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/81-package-saga-compensation.md)
- [82-poi-nearby-geohash-rate-limit.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/82-poi-nearby-geohash-rate-limit.md)
- [83-my-reservation-inquiry-apis.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/83-my-reservation-inquiry-apis.md)
- [84-autocomplete-feedback-loop.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/84-autocomplete-feedback-loop.md)
- [85-search-index-sync-outbox-projection.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/85-search-index-sync-outbox-projection.md)
- [86-destination-recommendation-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/86-destination-recommendation-fallback.md)
- [87-catalog-roomtype-review-queries.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/87-catalog-roomtype-review-queries.md)
- [88-payment-gateway-failure-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/88-payment-gateway-failure-contract.md)
- [89-idempotency-engine-deep-dive.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/89-idempotency-engine-deep-dive.md)
- [90-queue-admit-token-lua.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/90-queue-admit-token-lua.md)
- [91-traffic-guard-rate-limit-cost.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/91-traffic-guard-rate-limit-cost.md)
- [92-api-envelope-error-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/92-api-envelope-error-contract.md)
- [93-search-facet-taxonomy-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/93-search-facet-taxonomy-fallback.md)
- [94-auth-password-session-hardening.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/94-auth-password-session-hardening.md)
- [95-my-reservation-read-model-merge.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/95-my-reservation-read-model-merge.md)
- [96-customer-inquiry-state-validation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/96-customer-inquiry-state-validation.md)
- [97-voucher-issue-outbox-consumer.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/97-voucher-issue-outbox-consumer.md)
- [98-promotion-claim-race-control.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/98-promotion-claim-race-control.md)
- [99-domain-support-outbox-helpers.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/99-domain-support-outbox-helpers.md)
- [100-search-request-normalization-placeid.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/100-search-request-normalization-placeid.md)

## 발행 관리
발행 전 점검 항목은 [PUBLISH_CHECKLIST.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/PUBLISH_CHECKLIST.md)를 사용합니다.
실제 발행 순서는 [PUBLISH_QUEUE.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/PUBLISH_QUEUE.md)를 기준으로 관리합니다.
