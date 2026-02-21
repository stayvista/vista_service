# B-0901 — Locale/Currency v1

## Goal
접속 국가/통화를 자동 설정하고, 우상단에서 변경 가능하게 한다.

## API
- GET /v1/locale -> {country,currency,language,source}
- POST /v1/locale -> {country,currency} (manual override)

## Storage
- session_locale(session_id, country, currency)
- user_locale(user_id, country, currency)

## Acceptance Criteria
- 최초 접속 시 자동 설정
- 변경 후 검색/가격 캘린더가 즉시 반영
