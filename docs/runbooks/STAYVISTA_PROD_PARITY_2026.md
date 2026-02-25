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
   - Copilot latency: `chat_copilot_orchestrator_latency_ms_seconds_bucket` (p95/p99)
   - Funnel: `ai_copilot_funnel_step_total`
   - Action apply success: `ai_copilot_action_apply_total`
   - Handoff quality: `chat_search_handoff_total`, `chat_search_handoff_confidence`, `chat_search_handoff_profile_applied_total`
   - Handoff detail: `ai_widget_handoff_filter_count`, `ai_widget_handoff_confidence`, `ai_widget_handoff_scope_total`
   - Handoff scope quality: `ai_widget_handoff_filter_count_by_scope`, `ai_widget_handoff_confidence_by_scope`
   - Scope event distribution: `ai_widget_source_scope_total{event=*}`
   - Clarify loop: `chat_search_handoff_clarify_suggested_total`, `ai_widget_clarify_click_total`, `chat_search_handoff_clarify_question_count`
   - Clarify impact: `ai_widget_handoff_confidence_by_clarify`, `ai_widget_handoff_filter_count_by_clarify`, `ai_widget_handoff_clarify_click_state_total`
   - Quality: `ai_copilot_quality_event_total`
   - Degrade/no-result: `chat_copilot_orchestrator_requests_total`, `chat_copilot_orchestrator_no_result_total`
3. 알람 룰 점검
   - `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`
   - `CopilotOrchestratorLatencyP95High`
   - `CopilotOrchestratorLatencyP99High`
   - `CopilotWidgetErrorRateHigh`
   - `CopilotActionApplySuccessRateLow`
   - `ChatSearchHandoffEmptyRatioHigh`
   - `ChatSearchHandoffConfidenceLow`
   - `ChatSearchHandoffProfileAppliedRatioLow`
   - `ChatSearchHandoffClarifySuggestedRatioHigh`
   - `ChatSearchHandoffClarifyCtrLow`
   - `ChatSearchHandoffScopeDriftHigh`
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
- 강제 Degrade 기준:
  - 10분 이상 `CopilotOrchestratorLatencyP99High` 경보 지속
  - 10분 이상 fallback/no-result 비율(`ai_copilot_quality_event_total`)이 `0.25` 초과
  - 15분 이상 action apply success(`ai_copilot_action_apply_total`)가 `0.85` 미만
  - 20분 이상 `ChatSearchHandoffClarifySuggestedRatioHigh` 경보 지속 + `clarify_ctr` 0.25 미만
- 강제 Degrade 조치:
  - UI에서는 `retry_with_patch`/`apply_filters` 액션만 노출
  - `open_property` 액션은 클릭형 deep-link로만 유지
  - 추천 문구는 근거/주의사항 포함 템플릿 응답으로 축소

## 8) AI handoff detail/clarify 운영 체크 (일일)
1. `avg_filter_count` (5m rate 기반) 2~6 범위 유지 여부 확인
2. `avg_confidence` 0.45 미만 구간 존재 시 시간대/route/source drill-down
3. `profile_applied_ratio` 20% 미만이면 프로필 만료율(`chat_pref_profile_total{status=miss}`) 점검
4. `scope_drift_ratio` > 0.20 이면 `source_scope` 직렬화 규칙/프론트 동기화/API validation 로그 점검
5. `clarify_suggested_ratio` > 0.65 이고 `clarify_ctr` < 0.25이면 슬롯 추출 규칙 완화
6. clarify 클릭 이후 품질 비교:
   - `confidence_clicked - confidence_not_clicked`
   - `filter_count_clicked - filter_count_not_clicked`
   - 두 지표가 연속 1일 역전 시 프롬프트/칩 문구 튜닝 티켓 생성

## 6) 롤백
1. 직전 안정 커밋으로 애플리케이션 롤백
2. 읽기 전용 API 정상성 확인: `/v1/search/properties`, `/v1/search/facets`, `/v1/prices/calendar`
3. 트래픽 단계적 복귀 후 SLI 확인

## 7) 배포 전후 회귀 판단(7일 이동평균)
1. `StayVista AI Copilot SLO` 대시보드에서 `conversion_7d_ma` 패널 확인
2. 배포 전 7일 평균 대비 배포 후 7일 평균 비교
3. 회귀 판정 기준(둘 중 하나 만족 시 회귀):
   - widget→booking_confirm 전환율 15% 이상 하락
   - degrade_ratio 2배 이상 상승
4. 회귀 시 즉시 조치:
   - Copilot 오케스트레이터 트래픽을 템플릿 경로로 강등
   - 원인 툴(`chat_copilot_orchestrator_tool_total{status="failed"}`) 상위 1개부터 복구
