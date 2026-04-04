---
title: "StayVista 기술 개발기 97: [심화] 바우처 발급 소비자 - Outbox 재처리와 순번 멱등"
slug: "97-voucher-issue-outbox-consumer"
series: "StayVista 기술 개발기"
order: 97
prev_slug: "96-customer-inquiry-state-validation"
next_slug: "98-promotion-claim-race-control"
status: "publish-ready"
excerpt: "티켓 바우처 발급은 비동기 소비자에서 중복 처리 위험이 큽니다. StayVista는 `existingCount` 재개 전략과 `(order_id, sequence_no)` 유니크 제약으로 재처리 안전성을 확보했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 97: [심화] 바우처 발급 소비자 - Outbox 재처리와 순번 멱등

## 한 줄 요약
비동기 발급 경로는 "한 번만 처리"를 보장하기 어렵습니다. 그래서 소비자 로직과 DB 제약을 같이 설계해야 합니다.

## 처리 대상
`TicketVoucherIssueJob`는 outbox에서 아래 이벤트를 읽습니다.

- `event_type='VoucherIssueRequested'`
- `status IN ('PUBLISHED', 'FAILED')`

즉 실패 이력을 다시 읽어 재처리할 수 있게 설계되어 있습니다.

## 스케줄/배치 경계
- `@Scheduled(fixedDelay = 5000, initialDelay = 12000)`
- 1회 최대 `LIMIT 100`건 처리

짧은 고정 주기와 배치 상한을 함께 두어, 로컬 테스트 환경에서도 소비 폭주를 제어합니다.

## 처리 흐름
1. payload 파싱 (`order_id`, `user_id`, `event_id`, `quantity`)
2. payload 유효성 검증(모두 > 0)
3. 현재 발급 수 조회 `COUNT(voucher WHERE order_id=?)`
4. 부족한 순번만 추가 INSERT
5. 성공 시 outbox status `CONSUMED`로 전이

`CONSUMED` 전이 시 `published_at=COALESCE(published_at, NOW(3))`를 함께 기록해, 최초 발행 시각이 비어 있는 이벤트도 타임라인을 복구합니다.

## 멱등 핵심
`voucher` 테이블에는 다음 유니크 키가 있습니다.

- `uk_voucher_order_seq (order_id, sequence_no)`

그리고 INSERT는 `ON DUPLICATE KEY UPDATE id=id`를 사용합니다.

즉 같은 순번이 재처리돼도 중복 row가 생기지 않습니다.

## 부분 성공 재개 전략
이미 1번 바우처가 있는 상태에서 목표 `quantity=2`라면,
`existingCount=1`부터 시작해 2번만 추가 발급합니다.

이 방식 덕분에 중간 실패 후 재실행해도 누락분만 채울 수 있습니다.

## 실패 처리 경계
`processOne`에서 예외가 발생하면 해당 row는 `CONSUMED`로 바꾸지 않고 `failed` 메트릭만 증가시킵니다.

즉 상태값이 `PUBLISHED/FAILED`에 남아 다음 주기에 다시 집계 대상이 되므로, 일시 오류에 대해 자연 재시도 효과를 얻을 수 있습니다.

## 테스트 근거
`TicketVoucherIssueJobTest`
- 신규 요청: 2매 발급 + outbox `CONSUMED`
- 기존 1매 + FAILED 이벤트: 재실행 후 총 2매 유지(중복 없음)

## 계측
- `voucher_issue_total{result=success|failed|skipped}`

성공/실패/건너뜀을 분리해 소비자 안정성을 확인할 수 있습니다.

## 기술적으로 중요한 포인트
- 비동기 소비자는 "at-least-once"를 가정하고 설계해야 합니다.
- 재처리 안전성은 코드 분기만으로 부족하고 DB 유니크 제약이 함께 있어야 합니다.
- 상태 전이를 `CONSUMED`로 명확히 남겨야 재처리 범위를 제어할 수 있습니다.
- 스케줄 주기와 배치 크기를 함께 제한해야 지연/폭주 상황에서 예측 가능한 처리량을 유지할 수 있습니다.

## 남은 과제
- dead-letter 분리
- payload schema version 추가
- 소비 지연/적체 지표 보강
