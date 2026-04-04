---
title: "StayVista 기술 개발기 19: [핵심] Rate Limit + Abuse 방어 - 단순 쿼터를 넘어선 트래픽 제어"
slug: "19-rate-limit-abuse"
series: "StayVista 기술 개발기"
order: 19
prev_slug: "18-queue-backpressure"
next_slug: "30-search-v2-engine"
status: "publish-ready"
excerpt: "Rate limit는 숫자 제한이 아니라 시스템 보호 정책입니다. 엔드포인트 성격별 정책과 bot 대응을 같이 설계해야 실효가 납니다."
read_time_min: 2
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 19: [핵심] Rate Limit + Abuse 방어 - 단순 쿼터를 넘어선 트래픽 제어

## 한 줄 요약
Rate limit는 숫자 제한이 아니라 시스템 보호 정책입니다. 엔드포인트 성격별 정책과 bot 대응을 같이 설계해야 실효가 납니다.

## 기본 설계
`TrafficGuardFilter`가 요청 초기 단계에서 정책을 적용합니다.

엔드포인트 그룹별 기본 한도(분당):
- search: 60
- autocomplete: 120
- booking_hold: 10
- booking_confirm: 5
- package_hold: 10
- package_confirm: 5
- chat: 40
- telemetry: 180

## 단순 횟수 제한을 넘는 부분
### 1) 요청 코스트 모델
같은 1회 요청이라도 비용이 다릅니다.

- bot 요청은 cost 가중치 추가
- autocomplete 1글자 질의는 추가 cost
- chat 대형 payload는 추가 cost

즉 "요청 수"가 아니라 "요청 비용 합"으로 제어합니다.

### 2) bot strict 정책
User-Agent 시그니처로 bot 의심 트래픽을 분리해 더 엄격한 한도를 적용합니다.
민감 경로에서는 추가 burst 제한까지 겁니다.

### 3) nearby 전용 제한기
`/v1/poi/nearby`는 일반 rate limiter와 별개로 token bucket을 사용합니다.

- refill/burst 파라미터 별도 관리
- 429 시 `retry_after_ms` 디테일 반환

## 응답 계약
제한 초과 시:
- HTTP 429
- `Retry-After` 헤더
- 표준 에러 envelope (`RATE_LIMITED`)

이 계약이 있어야 클라이언트가 지수 백오프를 적용할 수 있습니다.

## 로컬 검증 지표
필수 지표:
- `rate_limited_total{endpoint_group,reason}`
- `abuse_block_total{policy}`
- nearby 전용 `rate_limited_count{endpoint_group="nearby"}`

함께 볼 지표:
- 그룹별 성공률/latency
- 429 비율 변화와 비즈니스 KPI(예약완료율) 상관

## 기술적으로 중요한 균형점
### 1) 너무 강한 제한의 부작용
- 정상 사용자까지 차단
- 전환율 하락

### 2) 너무 약한 제한의 부작용
- DB/LLM/검색 엔진 보호 실패
- tail latency 급등

따라서 limit 숫자는 코드가 아니라 실험 파라미터입니다.
실제 트래픽과 지연/오류율 변화를 보면서 조정해야 합니다.

## 개선 방향
- principal 분해 개선(디바이스 fingerprint, 세션 상태 반영)
- 429 reason 세분화로 디버깅 품질 향상
- endpoint별 adaptive rate limit 실험

결론적으로 rate limit는 보안 기능이면서 동시에 성능 기능입니다.
정확한 트래픽 분류와 파라미터 튜닝 없이는 기대 효과를 얻기 어렵습니다.
