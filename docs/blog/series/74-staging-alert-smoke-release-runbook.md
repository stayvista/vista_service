---
title: "StayVista 기술 개발기 74: [핵심] 로컬 메트릭 스모크 검증 - 판정식 실행 가능성 확인"
slug: "74-local-alert-smoke-checklist"
series: "StayVista 기술 개발기"
order: 74
prev_slug: "73-alerting-burn-rate"
next_slug: "75-k6-loadtest-regression-gate"
status: "publish-ready"
excerpt: "판정식 파일을 작성해도 실제 데이터에서 계산되지 않으면 의미가 없습니다. StayVista는 로컬 스모크 스크립트로 식 실행 가능성과 라벨 정합성을 먼저 검증했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 74: [핵심] 로컬 메트릭 스모크 검증 - 판정식 실행 가능성 확인

## 한 줄 요약
판정식에서 가장 흔한 실패는 "문법은 맞지만 실제로 값이 안 나오는 식"입니다. 그래서 로컬 스모크 검증 단계를 고정했습니다.

## 사용 자산
- 판정식 파일: `services/loadtest/alerts/chat_slo_burn_rate_rules.yml`
- 스모크 스크립트: `services/loadtest/alerts/staging_alert_smoke.sh`

스크립트 이름에 `staging`이 포함돼 있어도, 실제로는 로컬 Prometheus(`http://127.0.0.1:39090`) 대상으로 바로 실행합니다.

## 스모크 검증에서 확인한 항목
1. Prometheus 연결 가능 여부
2. 핵심 burn-rate 식 질의 가능 여부
3. latency 식 질의 가능 여부
4. active rules 응답 구조 확인

실패 예시는 대부분 아래 두 가지였습니다.

- 메트릭 이름 변경 누락
- 태그 불일치로 쿼리 결과가 0만 반환

## k6와 결합한 검증 방식
스모크만으로는 판정식 민감도를 알기 어렵기 때문에, k6 부하를 같이 사용했습니다.

- `chat_stream_slo.js`로 실패/지연 패턴 주입
- 동일 구간에서 판정식 수치 변화를 확인
- 과민/둔감 구간 조정 후 재실행

## 기술적으로 중요한 포인트
- 식이 계산되는지 확인하기 전에는 임계치 튜닝을 시작하면 안 됩니다.
- 라벨 키/값 정합성을 먼저 확인해야 의미 있는 비교가 가능합니다.
- 판정식 파일과 스모크 스크립트를 함께 유지해야 변경 누락이 줄어듭니다.
