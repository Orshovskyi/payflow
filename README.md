# PayFlow

Мікросервісна платіжна система для порівняння патернів забезпечення консистентності даних у розподілених грошових
переказах (магістерська робота: naive dual-write проти Transactional Outbox, choreography проти orchestration).

## Модулі
| Модуль | Порт | Відповідальність |
|---|---|---|
| `payflow-events` | — | Контракти подій Kafka (`TransferRequested`, `TransferCompleted`, `TransferFailed`) |
| `wallet-service` | 8081 | Гаманці та баланси (PostgreSQL `wallet_db`, оптимістичне блокування); виконує перекази |
| `transaction-service` | 8082 | Перекази / стан саги (PostgreSQL `transaction_db`) |

Стратегія консистентності задається властивістю `payflow.consistency.strategy` (зараз реалізовано `naive`).

## Що потрібно
- JDK 21 (на нього має вказувати `JAVA_HOME`)
- Docker

## Запуск
```bash
docker compose up -d          # PostgreSQL, Kafka, Prometheus (:9090), Grafana (:3000, admin/admin)
./mvnw verify                 # юніт-тести та інтеграційні тести на Testcontainers
./mvnw -pl wallet-service spring-boot:run
./mvnw -pl transaction-service spring-boot:run
```

Приклад переказу:
```bash
curl -XPOST localhost:8081/api/v1/wallets -H 'Content-Type: application/json' \
  -d '{"ownerId":"alice","currency":"UAH","initialBalance":100}'
curl -XPOST localhost:8082/api/v1/transfers -H 'Content-Type: application/json' \
  -d '{"sourceWalletId":"<id>","targetWalletId":"<id>","amount":30,"currency":"UAH"}'
curl localhost:8082/api/v1/transfers/<transferId>     # PENDING -> COMPLETED | FAILED
```
- Swagger UI: http://localhost:8081/swagger-ui/index.html
- Метрики: http://localhost:8081/actuator/prometheus

## Документація
- Архітектурні рішення: [`docs/adr`](docs/adr)
- Нотатки для тексту роботи і презентації: [`docs/thesis-notes`](docs/thesis-notes)
