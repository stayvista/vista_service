---
title: "StayVista 기술 개발기 79: [확장] 프로모션 쿠폰 발급 - 중복 발급과 수량 초과를 막는 트랜잭션 설계"
slug: "79-promotion-coupon-claim-concurrency"
series: "StayVista 기술 개발기"
order: 79
prev_slug: "78-home-property-content-backing"
next_slug: "80-ticket-order-voucher-flow"
status: "publish-ready"
excerpt: "쿠폰 발급은 단순 INSERT가 아니라 재고형 문제입니다. StayVista는 UNIQUE 제약, 조건부 카운터 업데이트, 기존 발급 조회를 조합해 중복 발급과 초과 발급을 방지했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 79: [확장] 프로모션 쿠폰 발급 - 중복 발급과 수량 초과를 막는 트랜잭션 설계

## 한 줄 요약
쿠폰 발급은 사용자 기준 멱등성과 캠페인 기준 수량 제어를 동시에 만족해야 합니다.

## 데이터 모델
`V12__home_promotions_coupon.sql`에서 두 테이블로 분리했습니다.

- `promotion_campaign`
  - `issue_limit`, `issued_count`, `starts_at`, `ends_at`, `status`

- `promotion_coupon_claim`
  - `UNIQUE(campaign_id, user_id)`
  - `UNIQUE(coupon_code)`

핵심은 "사용자 중복"과 "캠페인 총량"을 각각 다른 제약으로 막는 점입니다.

## 발급 흐름 (`PromotionService.claimCampaign`)
1. 사용자 검증 (`ensureUserExists`)
2. 캠페인 조회 및 상태/기간 검증
3. 기존 발급 조회 (`findClaim`) 후 있으면 기존 쿠폰 반환
4. 신규 claim insert 시도
   - `DuplicateKeyException`이면 재조회 후 기존 쿠폰 반환
5. `promotion_campaign` 카운터 조건부 업데이트
   - `issued_count < issue_limit`일 때만 `issued_count + 1`
6. outbox 이벤트 `coupon_claimed` 적재

이 흐름 전체는 `@Transactional`로 감싸서, 중간 실패 시 롤백됩니다.

## 조회 API 설계
`listCampaigns(section, city, exclude_country, limit)`는 아래를 처리합니다.

- section 정규화(대문자)
- city 필터
- `exclude_country` 기반 글로벌 카드 제외
- 상태/기간 조건 + 우선순위 정렬

즉 홈 화면 카드와 쿠폰 발급 가능 여부를 같은 소스에서 계산합니다.

## 기술적으로 중요한 포인트
### 1) "이미 발급"을 오류가 아닌 정상 응답으로 처리했습니다
동일 사용자의 반복 클릭은 예외가 아니라 정상 흐름으로 간주하고 `already_claimed=true`를 반환합니다.

### 2) 카운터 업데이트를 조건부로 묶었습니다
총량 소진 시점에서 race가 발생해도 `UPDATE ... issued_count < issue_limit`이 최종 방어선이 됩니다.

### 3) 발급 이벤트를 outbox로 분리했습니다
쿠폰 발급 트랜잭션에서 후속 처리(알림/정산)를 직접 호출하지 않습니다.

## 검증 근거
- `src/test/kotlin/com/devoceanblue/stayvista/domain/promotion/PromotionServiceTest.kt`
  - `section`, `exclude_country`, `limit` 정규화 검증

## 로컬 지표
- `promotion_campaign_list_total`
- `promotion_claim_total{result=success|already_claimed|sold_out|inactive|out_of_window}`

이 분포를 보면 실패 원인이 비즈니스 정책인지 트래픽/경합인지 구분하기 쉽습니다.

## 남은 과제
- 다중 캠페인 동시 발급 시 우선순위 충돌 정책
- 쿠폰 사용/회수(consume/revert) 라이프사이클 API
- 대량 발급 구간에서 배치형 reserve 전략 검토
