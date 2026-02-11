# API Surface (v1)

> 모든 응답은 `{"request_id": "...", "data": ...}` 또는 `{"request_id": "...", "error": {...}}` (B-0005)

## Public (User)

### Search
- `GET /v1/search/properties`

### Property(Catalog)
- `GET /v1/properties/{propertyId}`
- `GET /v1/properties`
- `GET /v1/properties/{propertyId}/room-types`

### Booking
- `POST /v1/bookings/holds` *(Idempotency-Key required)*
- `POST /v1/bookings/{bookingId}/confirm` *(Idempotency-Key required)*
- `POST /v1/bookings/{bookingId}/cancel` *(Idempotency-Key required)*

### Ticket/Experience
- `GET /v1/tickets/products`
- `GET /v1/tickets/products/{productId}`
- `GET /v1/tickets/events?product_id=...&date=...`
- `POST /v1/tickets/orders/holds` *(Idempotency-Key required)*
- `POST /v1/tickets/orders/{orderId}/confirm` *(Idempotency-Key required)*
- `GET /v1/tickets/orders/{orderId}/vouchers` *(X-User-Id required)*

### Packages
- `GET /v1/packages`
- `GET /v1/packages/{packageId}`
- `POST /v1/packages/{packageId}/holds` *(Idempotency-Key required)*
- `POST /v1/packages/{packageId}/confirm` *(Idempotency-Key required)*
- `GET /v1/admin/packages/orders?status=...&limit=...`

### Waiting room
- `POST /v1/queue/join`
- `GET /v1/queue/status?ticket=...`

### Geo
- `GET /v1/geo/pois/nearby`
- `GET /v1/poi/nearby`
- `GET /v1/poi/{poiId}`

### Chat
- `POST /v1/chat/recommend`
- `POST /v1/chat/recommend:stream` *(SSE: meta/token/done)*
- `POST /v1/chat/preferences/feedback`

## Admin

### Catalog
- `POST /v1/admin/properties`
- `PATCH /v1/admin/properties/{propertyId}`
- `POST /v1/admin/properties/{propertyId}/room-types`
- `PATCH /v1/admin/room-types/{roomTypeId}`

### Inventory
- `PUT /v1/admin/room-types/{roomTypeId}/inventory`

### Ticket
- `POST /v1/admin/tickets/products`
- `POST /v1/admin/tickets/products/{productId}/events`
- `PUT /v1/admin/tickets/events/{eventId}/inventory`

### Voucher
- `POST /v1/admin/vouchers/validate` *(or redeem endpoint)*
  - request: `{ "voucher_id": "vch_..." }` 또는 `{ "qr_payload": "..." }`

### Search Ops
- `POST /v1/admin/search/reindex?limit=...`
- `GET /v1/admin/poi?limit=...&offset=...&keyword=...`
- `GET /v1/admin/poi/{poiId}`
- `POST /v1/admin/poi`
- `PATCH /v1/admin/poi/{poiId}`
- `POST /v1/admin/poi/geohash/backfill?limit=...`
- `POST /v1/admin/chat/rag/reindex?mode=full|incremental&limit=...`
- `GET /v1/admin/chat/prompts?prompt_key=...`
- `POST /v1/admin/chat/prompts`
- `POST /v1/admin/chat/prompts/rollback`
- `GET /v1/admin/chat/experiments/chat-core`
- `POST /v1/admin/chat/experiments/chat-core`
- `GET /v1/admin/chat/curation/rules`
- `POST /v1/admin/chat/curation/rules`
- `PATCH /v1/admin/chat/curation/rules/{ruleId}`
- `DELETE /v1/admin/chat/curation/rules/{ruleId}`

## OpenAPI
- `contracts/openapi-v1.yaml` is the source of truth for request/response shapes.
