# PayFlow

Мікросервісна платіжна система для порівняння патернів забезпечення консистентності даних у розподілених грошових
переказах (магістерська робота: naive dual-write проти Transactional Outbox, choreography проти orchestration).

## Модулі
| Модуль | Порт | Відповідальність |
|---|---|---|
| `wallet-service` | 8081 | Гаманці та баланси (PostgreSQL `wallet_db`, оптимістичне блокування) |
| `transaction-service` | 8082 | Перекази / стан саги (PostgreSQL `transaction_db`) |

## Що потрібно
- JDK 21 (на нього має вказувати `JAVA_HOME`)
- Docker

## Запуск
```bash
docker compose up -d          # PostgreSQL, Kafka, Prometheus (:9090), Grafana (:3000, admin/admin)
./mvnw verify                 # юніт-тести та інтеграційні тести на Testcontainers
./mvnw -pl wallet-service spring-boot:run
```
- Swagger UI: http://localhost:8081/swagger-ui/index.html
- Метрики: http://localhost:8081/actuator/prometheus

## Документація
- Архітектурні рішення: [`docs/adr`](docs/adr)
- Нотатки для тексту роботи і презентації: [`docs/thesis-notes`](docs/thesis-notes)
