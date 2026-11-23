# PayFlow

Мікросервісна платіжна система для порівняння патернів забезпечення консистентності даних у розподілених грошових
переказах (магістерська робота: naive dual-write проти Transactional Outbox, choreography проти orchestration).

## Модулі
| Модуль | Порт | Відповідальність |
|---|---|---|
| `payflow-events` | — | Контракти повідомлень саги: події, команди й відповіді (ADR-0008) |
| `payflow-outbox` | — | Доставка повідомлень: `EventPublisher` (naive або outbox), outbox-relay, оголошення топіків |
| `payflow-chaos` | — | Точка внесення збою для експериментів (ADR-0007) |
| `wallet-service` | 8081 | Гаманці, баланси й утримання коштів (PostgreSQL `wallet_db`); резервує, зараховує, повертає кошти |
| `transaction-service` | 8082 | Перекази й стан саги; в режимі orchestration — оркестратор (PostgreSQL `transaction_db`) |
| `ledger-service` | 8083 | Журнал подвійного запису й ліміт на операцію (PostgreSQL `ledger_db`) |

Сага переказу: резервування коштів (Wallet) → перевірка й проведення (Ledger) → зарахування отримувачу або
повернення коштів джерелу (компенсація). Дві незалежні осі задаються властивостями, однаковими для всіх сервісів:
- `payflow.consistency.strategy` — доставка повідомлень: `naive` (за замовчуванням) або `outbox`;
- `payflow.saga.coordination` — координація саги: `choreography` (за замовчуванням) або `orchestration`.

Наприклад: `PAYFLOW_SAGA_COORDINATION=orchestration PAYFLOW_CONSISTENCY_STRATEGY=outbox ./mvnw -pl wallet-service spring-boot:run`.

## Що потрібно
- JDK 21 (на нього має вказувати `JAVA_HOME`)
- Docker

## Запуск
```bash
docker compose up -d          # лише інфраструктура: PostgreSQL, Kafka, Prometheus (:9090), Grafana (:3000, admin/admin)
./mvnw verify                 # юніт-тести та інтеграційні тести на Testcontainers
./mvnw -pl wallet-service spring-boot:run
./mvnw -pl transaction-service spring-boot:run
./mvnw -pl ledger-service spring-boot:run
```

Приклад переказу:
```bash
curl -XPOST localhost:8081/api/v1/wallets -H 'Content-Type: application/json' \
  -d '{"ownerId":"alice","currency":"UAH","initialBalance":100}'
curl -XPOST localhost:8082/api/v1/transfers -H 'Content-Type: application/json' \
  -d '{"sourceWalletId":"<id>","targetWalletId":"<id>","amount":30,"currency":"UAH"}'
curl localhost:8082/api/v1/transfers/<transferId>     # PENDING -> COMPLETED | FAILED
curl localhost:8083/api/v1/ledger/transfers/<transferId>   # рішення Ledger і проведення
```
Сума понад ліміт (`payflow.ledger.max-transfer-amount`, за замовчуванням 9) відхиляється Ledger, і кошти
повертаються джерелу: переказ закінчується `FAILED` з причиною `LIMIT_EXCEEDED`.
- Swagger UI: http://localhost:8081/swagger-ui/index.html
- Метрики: http://localhost:8081/actuator/prometheus

## Експерименти
```bash
BUILD=1 experiments/run-experiment.sh outbox 50 120
COORDINATION=orchestration experiments/run-experiment.sh outbox 50 120
```
Сервіси працюють у контейнерах з однаковими лімітами; результати потрапляють у `experiments/results/summary-v2.csv`,
графіки — у Grafana (дашборд **PayFlow · Experiment**). Деталі — [`experiments/README.md`](experiments/README.md).

## Документація
- Архітектурні рішення: [`docs/adr`](docs/adr)
- Нотатки для тексту роботи і презентації: [`docs/thesis-notes`](docs/thesis-notes)
