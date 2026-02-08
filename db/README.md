# services (Kotlin / Spring Boot)

멀티모듈 지향:
- apps: api-gateway, catalog-service, booking-service, search-service, ticketing-service, package-service, geo-service, chatbot-service
- libs: common (logging, error, tracing, idempotency, outbox)

DB 마이그레이션: `db/migrations` (Flyway)
로컬 인프라: `compose.yaml`
