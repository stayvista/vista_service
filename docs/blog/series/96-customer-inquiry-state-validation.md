---
title: "StayVista 기술 개발기 96: [심화] 고객문의 도메인 - 상태 라벨/입력 검증/오류 계약"
slug: "96-customer-inquiry-state-validation"
series: "StayVista 기술 개발기"
order: 96
prev_slug: "95-my-reservation-read-model-merge"
next_slug: "97-voucher-issue-outbox-consumer"
status: "publish-ready"
excerpt: "고객문의는 단순 CRUD보다 입력 검증과 상태 의미가 더 중요합니다. StayVista는 허용 유형, 상태 라벨, 상세 조회 실패 계약을 서비스 계층에서 명시적으로 고정했습니다."
read_time_min: 4
tags:
  - "stayvista"
  - "backend"
  - "engineering"
---



# StayVista 기술 개발기 96: [심화] 고객문의 도메인 - 상태 라벨/입력 검증/오류 계약

## 한 줄 요약
문의 도메인은 생성 API보다 "무엇을 허용하고 무엇을 거절할지"가 핵심입니다. 검증 규칙을 서비스 계층에 고정해 API 일관성을 확보했습니다.

## 스키마
`V15__customer_inquiry.sql`로 사용자 문의를 독립 테이블로 분리했습니다.

핵심 컬럼:
- `inquiry_type`
- `title`, `content`
- `status` (`RECEIVED`, `IN_PROGRESS`, `ANSWERED`)
- `answer_content`, `answered_at`

인덱스:
- `idx_customer_inquiry_user_created`
- `idx_customer_inquiry_user_status`

## 서비스 정책 (`CustomerInquiryService`)
### 허용 문의 유형
`ALLOWED_TYPES`를 고정해 자유 문자열 오염을 막았습니다.

- 주문/배송
- 결제/환불
- 쿠폰/혜택
- 예약/변경
- 기타

### 상태 라벨 매핑
응답에서 `status_label`을 같이 내려 프론트에서 중복 매핑하지 않도록 했습니다.

알 수 없는 상태값이 들어오면 `STATUS_LABELS[status] ?: status`로 원문 상태를 그대로 노출해, 신규 상태 추가 시 API가 즉시 깨지지 않게 설계했습니다.

## 입력 검증
`createInquiry`는 trim 후 아래를 검증합니다.

- 유형 허용 여부
- 제목 공백 금지, 200자 이하
- 본문 공백 금지, 5000자 이하

실패 시 모두 `DomainException(VALIDATION_ERROR)`로 반환합니다.

## 조회 실패 계약
`getInquiry`에서 사용자 소유 문의가 없으면:

- `customer_inquiry_detail_not_found_total` 증가
- `NOT_FOUND` 반환

즉 "존재하지 않음"과 "권한 없음"을 사용자 단위 조회로 통합 처리합니다.

## 엔드포인트
`CustomerInquiryController`
- `GET /v1/me/inquiries`
- `GET /v1/me/inquiries/{inquiryId}`
- `POST /v1/me/inquiries`

모든 경로는 `X-User-Id` 기반 인증 필터 경계를 전제로 동작합니다.

### limit 경계
- 컨트롤러 기본 `limit=20`
- 서비스 보정 `limit=1..100`

입력 이상치가 들어와도 조회 부하를 과도하게 키우지 않도록 했습니다.

## 생성 응답 식별자 처리
`createInquiry`는 `GeneratedKeyHolder`로 생성된 `inquiry_id`를 즉시 반환합니다.

이 방식 덕분에 생성 직후 상세 조회를 연속 호출해도 별도 조회 쿼리 없이 ID를 사용할 수 있습니다.

## 계측
- `customer_inquiry_list_total`
- `customer_inquiry_detail_total`
- `customer_inquiry_detail_not_found_total`
- `customer_inquiry_create_total`

조회/생성/실패를 분리 계측해 API 품질을 확인할 수 있게 했습니다.

## 기술적으로 중요한 포인트
- 도메인 enum/라벨을 서비스에서 고정해야 데이터와 화면 의미가 흔들리지 않습니다.
- 검증은 컨트롤러 어노테이션만으로 충분하지 않고, 비즈니스 규칙을 서비스에서 재검증해야 안전합니다.
- 실패 코드 일관성이 있어야 프론트 재시도/메시지 전략이 단순해집니다.
- 목록/상세/생성/실패를 분리 계측해야 병목 구간을 빠르게 구분할 수 있습니다.

## 남은 과제
- admin 답변 API와 상태 전이 규칙 분리
- 문의 첨부파일 모델 확장
- 문의 유형 다국어 코드화
