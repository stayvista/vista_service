# STAYVISTA_PROD_PARITY_2026 Runbook

## 1) Release Gate
- Search API: `p95 < 250ms`, `p99 < 800ms`
- Price Calendar API: `p95 < 150ms`
- Booking Confirm API (결제 제외): `p95 < 400ms`
- `5xx < 0.5%`
- 핵심 E2E 플로우 통과

## 2) SLI/SLO 확인 절차
1. Search parity dashboard 확인
   - `services/loadtest/grafana/search_parity_dashboard.json`
2. Chat/AI SLO dashboard 확인
   - `services/loadtest/grafana/chat_slo_dashboard.json`
3. 알람 룰 점검
   - `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`
4. 필터/Facet 메트릭 확인
   - `search_facets_requests_total`
   - `search_facets_latency_ms`
   - `search_facets_empty_group_count`

## 3) 부하 테스트 실행
사전 조건:
- 로컬/스테이징 API 실행
- `k6` 설치

Autocomplete (200 rps 목표):
```bash
k6 run services/loadtest/k6/autocomplete.js
```

Price calendar (50 rps 목표):
```bash
k6 run services/loadtest/k6/price_calendar.js
```

Search (100 rps 목표):
```bash
k6 run services/loadtest/k6/search.js
```

Booking funnel:
```bash
k6 run services/loadtest/k6/full_funnel.js
```

## 4) 필터 누락/빈 화면 대응
증상:
- 좌측 필터 섹션이 비어 보임
- 도시 인기 필터/지역/브랜드/명소가 일부 누락

점검:
1. `/v1/search/facets?place_id=city:Seoul` 응답에서 facet 항목이 내려오는지 확인
2. city 파라미터가 `서울`/`Seoul` 불일치인지 확인 (도시 정규화 적용됨)
3. `db/migration/V11__search_parity_filters_expansion.sql` 반영 여부 확인
4. 데이터가 부족하면 `scripts/seed_local.sql` 재시드

재시드:
```bash
./scripts/seed_local.sh
```

## 5) 장애 우회(Degrade)
- AI Copilot 실패 시: 기본 facet 기반 추천만 노출
- 환율 조회 실패 시: 정적 fallback rate로 변환
- Price calendar 집계 비어있으면 property 기반 fallback 사용

## 6) 롤백
1. 직전 안정 커밋으로 애플리케이션 롤백
2. 읽기 전용 API 정상성 확인: `/v1/search/properties`, `/v1/search/facets`, `/v1/prices/calendar`
3. 트래픽 단계적 복귀 후 SLI 확인
