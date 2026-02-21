# B-0902 — FX Rates v1

## Goal
가격을 선택 통화로 표시하기 위한 환율 서비스.

## API
- GET /v1/fx?base=USD&quote=KRW
- internal FxService.convert(amount, from, to)

## Data
- fx_rate(base, quote, rate, as_of)
- refresh job(DEV: seed)

## Acceptance Criteria
- KRW/USD/JPY/EUR 변환이 검색/캘린더에 반영
