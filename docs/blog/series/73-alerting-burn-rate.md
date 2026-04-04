---
title: "StayVista 기술 개발기 73: [핵심] 회귀 판정식과 Burn-Rate - 노이즈를 줄이는 비율 계산"
slug: "73-alerting-burn-rate"
series: "StayVista 기술 개발기"
order: 73
prev_slug: "72-grafana-dashboard-correlation"
next_slug: "74-local-alert-smoke-checklist"
status: "publish-ready"
excerpt: "로컬 회귀 판정식은 임계치 숫자보다 분모 안정화와 다중 윈도우 조합이 더 중요했습니다. StayVista는 burn-rate 계산을 기반으로 급격한 악화와 완만한 악화를 분리해 검증했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 73: [핵심] 회귀 판정식과 Burn-Rate - 노이즈를 줄이는 비율 계산

## 한 줄 요약
판정식은 "한 번 튀는 값"에 반응하지 않으면서도, 실제 악화는 빠르게 잡아야 합니다. 이를 위해 fast/slow burn-rate를 함께 사용했습니다.

## 판정식 자산
- `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`

파일은 rules 형식이지만, 로컬 테스트에서는 회귀 판정식 모음으로 사용했습니다.

## Burn-Rate를 쓴 이유
단일 윈도우 비율만 보면 두 가지 문제가 반복됐습니다.

- 짧은 스파이크에 과민 반응
- 완만한 성능 저하를 늦게 감지

그래서 서로 다른 시간폭을 함께 계산했습니다.

- fast: 5m/1h
- slow: 30m/6h

## 분모 안정화 (`clamp_min`)
저트래픽에서 분모가 0에 가까워지면 비율이 왜곡됩니다. 규칙식에서 `clamp_min`을 넣어 이 문제를 완화했습니다.

예시 패턴:
- `sum(rate(fail_total[5m])) / clamp_min(sum(rate(req_total[5m])), 1)`

## 기술 신호와 품질 신호 분리
동일한 실패율이라도 품질 저하 원인은 다릅니다. 그래서 판정식을 두 부류로 분리했습니다.

- 기술 안정성: `chat_llm_fail_total`, `llm_reject_rate`
- 품질 신호: handoff confidence, clarify click, scope drift 계열

## 로컬 검증 절차
1. k6 시나리오로 특정 부하 패턴을 만듭니다.
2. 5분/30분/1시간 구간에서 판정식 값을 확인합니다.
3. false positive가 많으면 임계치/분모 조건을 조정합니다.
4. 동일 시나리오를 재실행해 재현성을 확인합니다.

## 기술적으로 중요한 포인트
- 비율만 보지 않고 최소 트래픽 조건을 같이 둬야 노이즈가 줄어듭니다.
- fast/slow를 같이 써야 급격한 악화와 누적 악화를 동시에 잡을 수 있습니다.
- 판정식은 고정값이 아니라, 코드 변경과 함께 갱신되는 테스트 자산으로 관리해야 합니다.
