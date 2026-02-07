# services

멀티모듈 백엔드 스캐폴딩입니다.

## 실행
- 인프라: `cd services/docker && docker compose up -d`
- 개별 앱 예시: `cd services && ./gradlew :apps:booking:bootRun --args='--spring.profiles.active=local'`

## 모듈
- apps: gateway, catalog, booking, search, ticket, geo, chatbot
- libs: common-web, common-db, common-observability
- db/migrations: Flyway SQL
