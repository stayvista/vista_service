---
title: "StayVista 기술 개발기 83: [확장] My 페이지 API - 예약 통합 조회와 고객문의 모델링"
slug: "83-my-reservation-inquiry-apis"
series: "StayVista 기술 개발기"
order: 83
prev_slug: "82-poi-nearby-geohash-rate-limit"
next_slug: "84-autocomplete-feedback-loop"
status: "publish-ready"
excerpt: "사용자 마이페이지는 도메인별 테이블을 그대로 노출하면 사용성이 떨어집니다. StayVista는 booking/ticket/package를 통합 정렬해 제공하고, 문의 도메인은 별도 테이블과 상태 라벨로 분리했습니다."
read_time_min: 3
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 83: [확장] My 페이지 API - 예약 통합 조회와 고객문의 모델링

## 한 줄 요약
사용자 관점의 "내 예약"은 도메인 통합 뷰이고, "고객 문의"는 별도 상태 머신입니다. 두 영역을 분리하면 API와 화면이 모두 단순해집니다.

## 예약 통합 조회 (`MyReservationService`)
`GET /v1/me/reservations`는 3개 테이블을 읽어 하나의 리스트로 합칩니다.

- `booking`
- `ticket_order`
- `package_order`

동작 흐름:
1. 사용자 존재 확인
2. 도메인별 count 집계
3. 도메인별 상세 아이템 로드
4. `created_at` 기준 통합 정렬
5. 상위 `limit`만 반환

ID 포맷은 도메인별 prefix를 유지합니다.
- booking: `bkg_`
- ticket order: `tord_`
- package order: `pkg_`

## 응답 모델링 포인트
`MyReservationItem` 공통 스키마에 다음을 포함합니다.

- `type`, `reservation_id`, `status`
- `title`, `subtitle`
- `amount(currency, amount_total)`
- `created_at`, `expires_at`, `confirmed_at`

그리고 도메인별 식별자는 선택 필드로 둬서, 프론트가 카드 타입별 액션을 분기할 수 있게 했습니다.

## 고객문의 도메인 (`CustomerInquiryService`)
`V15__customer_inquiry.sql`로 문의 테이블을 분리했습니다.

- `inquiry_type`
- `title`, `content`
- `status` (`RECEIVED`, `IN_PROGRESS`, `ANSWERED`)
- `answer_content`, `answered_at`

API:
- `GET /v1/me/inquiries`
- `GET /v1/me/inquiries/{inquiryId}`
- `POST /v1/me/inquiries`

## 입력 검증
문의 생성 시 아래를 검증합니다.

- 허용 문의 유형(`주문/배송`, `결제/환불`, `쿠폰/혜택`, `예약/변경`, `기타`)
- 제목 공백 금지, 최대 200자
- 본문 공백 금지, 최대 5000자

잘못된 값은 `VALIDATION_ERROR`로 일관되게 반환합니다.

## 인증 경계
`AuthGuardFilter`에서 `/v1/me/**`는 인증 필수입니다.

즉 마이페이지 관련 API는 컨트롤러에서 별도 인증 체크를 넣지 않아도 됩니다.

## 기술적으로 중요한 포인트
### 1) 통합 조회는 read model로 처리했습니다
쓰기 모델(booking/ticket/package)을 강제로 합치지 않고, 읽기 시점 조합으로 해결했습니다.

### 2) 문의 상태 라벨을 서버에서 함께 제공합니다
`status_label`을 응답에 포함해 프론트가 상태 매핑 테이블을 중복 관리하지 않도록 했습니다.

### 3) count와 list를 분리 계산했습니다
상단 요약(count)과 리스트(limit)를 각각 계산해 UX 응답 목적을 분리했습니다.

## 관련 검증
- `AuthGuardFilterTest`에서 `/v1/me/session` 인증 경계 검증
- 예약 통합/문의 도메인은 서비스 단위 SQL 검증 중심으로 확장 가능

## 남은 과제
- 문의 답변(admin) API 추가
- 예약 통합 조회에 cursor pagination 도입
- 상태 변경 이력(audit trail) 저장
