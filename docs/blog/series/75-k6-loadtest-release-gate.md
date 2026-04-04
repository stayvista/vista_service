---
title: "StayVista 기술 개발기 75: [핵심] k6 부하테스트 기준선 - 로컬 회귀를 수치로 판정하기"
slug: "75-k6-loadtest-regression-gate"
series: "StayVista 기술 개발기"
order: 75
prev_slug: "74-local-alert-smoke-checklist"
next_slug: "76-auth-session-guardrails"
status: "publish-ready"
excerpt: "로컬 개발에서도 회귀 판정은 느낌이 아니라 수치여야 했습니다. StayVista는 도메인별 k6 시나리오와 임계치를 고정해 성능/정합성 변화를 반복 검증했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 75: [핵심] k6 부하테스트 기준선 - 로컬 회귀를 수치로 판정하기

## 한 줄 요약
k6 시나리오를 도메인별로 분리하고 임계치를 고정하면, 코드 수정 후 회귀 여부를 빠르게 판단할 수 있습니다.

## 시나리오 구성
`services/loadtest/k6`에 핵심 경로를 분리했습니다.

- 검색: `search.js`
- 가격 캘린더: `price_calendar.js`
- 예약 hold: `booking_hold.js`
- 퍼널 종합: `full_funnel.js`
- 챗/스트리밍: `chat_recommend.js`, `chat_stream_slo.js`
- 지도 nearby: `nearby.js`
- 자동완성: `autocomplete.js`

## 왜 분리했는가
한 개의 종합 시나리오만 쓰면 병목 원인이 섞여 해석이 어려웠습니다.

- 검색: 필터 조합/쿼리 비용
- 예약: 경합/락/재고 정합성
- 챗: LLM timeout/reject/fallback
- nearby: geohash 범위, 캐시, rate-limit

## 기준선 예시
프로젝트 내 README 기준으로 다음 값을 기준선으로 사용했습니다.

- `chat_llm_off_p95 < 250ms`
- `chat_stream_ttfb_ms p95 < 500ms`
- `chat_stream_complete_ms p95 < 2000ms`
- `hold_5xx_rate < 0.1%`
- `funnel_5xx_rate < 0.1%`

## 해석 규칙
### 1) 409/429와 5xx를 분리
경합/제한 경로에서는 409/429가 정책상 정상일 수 있으므로, 5xx와 같은 실패로 합치지 않았습니다.

### 2) 부하 모델을 시나리오에 맞춤
- steady 검증: `constant-arrival-rate`
- 급증 재현: `ramping-arrival-rate`
- 캐시 재현: `constant-vus`

### 3) 인증 경계를 포함
쓰기 경로는 `AUTH_TOKEN`을 포함해 실제 인증 필터(`AuthGuardFilter`)를 통과하는 경로로 테스트했습니다.

## 로컬 반복 루프
1. 기준선 시나리오 실행
2. 코드 변경 후 동일 시나리오 재실행
3. 임계치 위반 항목 확인
4. 코드/쿼리/파라미터 수정
5. 동일 시나리오로 재검증

## 기술적으로 중요한 포인트
- 절대 최고 성능보다 재현 가능한 비교 기준이 더 중요했습니다.
- 도메인별 분리 시나리오가 원인 추적 시간을 크게 줄였습니다.
- 성능 수치와 정합성 지표(충돌/과판매)를 같이 봐야 회귀를 정확히 잡을 수 있습니다.
