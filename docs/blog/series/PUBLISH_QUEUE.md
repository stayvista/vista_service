# StayVista 기술 개발기 발행 큐

## 현재 상태
- 기준일: 2026-03-02
- `publish-ready`: 40편
- `draft`: 0편

## Ready 배치 A (코어 정합성)
1. [00-prologue.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/00-prologue.md)
목적: 시리즈 전체 기술 원칙과 관점을 먼저 고정합니다.
2. [04-idempotency-key.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/04-idempotency-key.md)
목적: 예약/결제 API 멱등성의 기준선을 제시합니다.
3. [10-booking-hold-concurrency.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/10-booking-hold-concurrency.md)
목적: 과판매 방지의 핵심인 조건부 UPDATE 패턴을 설명합니다.
4. [11-booking-confirm-cancel.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/11-booking-confirm-cancel.md)
목적: 예약 상태 전이와 재고 복구의 정합성 원칙을 정리합니다.
5. [16-db-retry-executor.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/16-db-retry-executor.md)
목적: deadlock/lock wait 대응을 운영 가능한 규칙으로 보여줍니다.
6. [17-outbox-relay.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/17-outbox-relay.md)
목적: DB 정합성과 이벤트 발행을 동시에 지키는 구조를 설명합니다.
7. [18-queue-backpressure.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/18-queue-backpressure.md)
목적: 폭주 상황에서 쓰기 경로를 보호하는 큐 전략을 제시합니다.
8. [19-rate-limit-abuse.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/19-rate-limit-abuse.md)
목적: 단순 제한을 넘어 abuse 대응까지 포함한 트래픽 제어를 설명합니다.

## Ready 배치 B (검색/입구 경험)
1. [30-search-v2-engine.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/30-search-v2-engine.md)
목적: 검색 쿼리 엔진의 핵심 필터/정렬/가용성 구조를 설명합니다.
2. [31-opensearch-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/31-opensearch-fallback.md)
목적: OpenSearch 우선 + DB fallback 이중 경로 운영 전략을 설명합니다.
3. [33-facet-engine-server-driven.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/33-facet-engine-server-driven.md)
목적: 서버 주도 facet 구성이 왜 운영에 유리한지 정리합니다.
4. [34-price-calendar-fallback-fx.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/34-price-calendar-fallback-fx.md)
목적: 가격 캘린더의 place 정규화, fallback, FX 변환 구조를 설명합니다.
5. [36-search-copilot-filters.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/36-search-copilot-filters.md)
목적: 검색 코파일럿의 heuristic+LLM degrade 전략을 설명합니다.
6. [39-autocomplete-degrade.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/39-autocomplete-degrade.md)
목적: 자동완성 장애 흡수와 성능/가용성 균형 전략을 설명합니다.

## Ready 배치 C (AI 라우팅/실행 안정성)
1. [50-chat-routing-policy.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/50-chat-routing-policy.md)
목적: TEMPLATE/LLM/CLARIFY 라우팅 기준과 비용/품질 균형을 설명합니다.
2. [51-llm-execution-gate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/51-llm-execution-gate.md)
목적: LLM 동시성 제한과 queue reject 처리의 운영 원칙을 설명합니다.
3. [52-llm-budget-controller.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/52-llm-budget-controller.md)
목적: p99 보호를 위한 adaptive degrade 및 budget 모드 제어를 설명합니다.
4. [53-llm-model-registry-fallback.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/53-llm-model-registry-fallback.md)
목적: 모델 전환/장애 대응을 설정 기반으로 제어하는 구조를 설명합니다.
5. [54-structured-output-repair.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/54-structured-output-repair.md)
목적: strict parser + repair + fallback으로 출력 계약을 지키는 방법을 설명합니다.
6. [55-memory-preference-rerank.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/55-memory-preference-rerank.md)
목적: 메모리/취향 프로필을 후처리 rerank에 반영하는 패턴을 설명합니다.

## Ready 배치 D (AI 신뢰성/실험 운영)
1. [56-citation-verifier-grounding.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/56-citation-verifier-grounding.md)
목적: 근거 없는 확신 응답 차단 정책을 설명합니다.
2. [57-chat-safety-guardrails.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/57-chat-safety-guardrails.md)
목적: 입력/출력 안전정책의 다층 방어 구조를 설명합니다.
3. [58-pii-redaction-pipeline.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/58-pii-redaction-pipeline.md)
목적: PII 마스킹 파이프라인과 저장 경로 통제를 설명합니다.
4. [59-shadow-run-evaluation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/59-shadow-run-evaluation.md)
목적: shadow run 기반 품질 검증 전략을 설명합니다.
5. [60-semantic-cache-singleflight.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/60-semantic-cache-singleflight.md)
목적: semantic cache와 singleflight를 통한 중복 계산 제거를 설명합니다.
6. [61-prompt-registry-experiment-rollout.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/61-prompt-registry-experiment-rollout.md)
목적: 프롬프트 버전/롤아웃/롤백 운영 절차를 설명합니다.

## Ready 배치 E (AI 제품화/관측/릴리즈)
1. [62-copilot-orchestrator.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/62-copilot-orchestrator.md)
2. [63-handoff-advisor.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/63-handoff-advisor.md)
3. [64-rag-index-builder-incremental.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/64-rag-index-builder-incremental.md)
4. [65-hybrid-ranker-curation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/65-hybrid-ranker-curation.md)
5. [66-chat-streaming-sse-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/66-chat-streaming-sse-contract.md)
6. [67-llm-client-timeout-streaming.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/67-llm-client-timeout-streaming.md)
7. [68-widget-session-snapshot-hardening.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/68-widget-session-snapshot-hardening.md)
8. [69-llm-health-ready-probe.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/69-llm-health-ready-probe.md)
9. [70-observability-slo.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/70-observability-slo.md)
10. [71-telemetry-event-contract.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/71-telemetry-event-contract.md)
11. [72-grafana-dashboard-correlation.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/72-grafana-dashboard-correlation.md)
12. [73-alerting-burn-rate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/73-alerting-burn-rate.md)
13. [74-staging-alert-smoke-release-runbook.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/74-staging-alert-smoke-release-runbook.md)
14. [75-k6-loadtest-release-gate.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/75-k6-loadtest-release-gate.md)

## 발행 운영 규칙
- Ready 글부터 주 2편씩 순차 발행합니다.
- 발행 직전 [PUBLISH_CHECKLIST.md](/Users/seungyoonkim/dev/side_projects/stayvista/docs/blog/series/PUBLISH_CHECKLIST.md)를 반드시 통과합니다.
- 발행 후 24시간 내 조회/체류/공유 지표를 보고 다음 주차 제목/도입을 조정합니다.
