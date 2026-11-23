# Сценарій відео-демонстрації (2–4 хв)

Мета відео — показати не «кнопки», а головну тезу роботи: після збою naive залишає розбіжність, а outbox — ні.
Готовий варіант без звуку: [demo.mp4](demo.mp4). Нижче — як записати те саме самостійно.

## 0. Підготовка (до запису)
1. Запусти Docker Desktop.
2. Відкрий термінал у корені проєкту і виконай:
   ```bash
   cd ~/IdeaProjects/payflow
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   docker compose --profile app down -v
   clear
   ```
   Java 21 потрібна для Gatling у chaos-прогонах, інакше буде `UnsupportedClassVersionError`.
   Якщо після останньої збірки змінювався код, один раз збери образи: `docker compose --profile app build`.
3. Збільш шрифт терміналу (`Cmd +` кілька разів) і масштаб браузера до 125–150 %.
4. Запис екрана: `Cmd + Shift + 5` → «Записати весь екран» або «Записати вибрану частину». Записуй одним
   файлом, а очікування виріжеш потім (QuickTime: `Cmd + T` для обрізання; iMovie — для склеювання).

## 1. Запуск системи (~20 с у відео)
```bash
STRATEGY=naive docker compose --profile app up -d --wait
```
Зачекай 15–20 с, поки сервіси стартують (поки що не показуй у відео), потім:
```bash
docker compose --profile app ps --format 'table {{.Service}}\t{{.Status}}'
```
Готово, коли всі шість сервісів `Up`. Перевірка: `curl localhost:8081/actuator/health` повертає `{"status":"UP"}`.

## 2. Переказ через Swagger UI (~35 с)
**Wallet Service:** http://localhost:8081/swagger-ui/index.html

1. `POST /api/v1/wallets` → **Try it out** → тіло запиту → **Execute**:
   ```json
   { "ownerId": "alice", "currency": "UAH", "initialBalance": 1000 }
   ```
   Скопіюй `id` з відповіді: це гаманець Alice.
2. Ще раз `POST /api/v1/wallets`:
   ```json
   { "ownerId": "bob", "currency": "UAH", "initialBalance": 500 }
   ```
   Скопіюй `id`: це гаманець Bob.

**Transaction Service:** http://localhost:8082/swagger-ui/index.html

3. `POST /api/v1/transfers` (підстав свої `id`):
   ```json
   { "sourceWalletId": "<id Alice>", "targetWalletId": "<id Bob>", "amount": 250, "currency": "UAH" }
   ```
   Відповідь — `202` і `"status": "PENDING"`. Скопіюй `id` переказу.
4. `GET /api/v1/transfers/{id}` з цим `id` → `"status": "COMPLETED"`.

**Знову Wallet Service:**

5. `GET /api/v1/wallets/{id}` для Alice → `"balance": 750`.

## 3. Збій у naive (~30 с у відео, ~3 хв наживо)
```bash
LABEL=demo CHAOS_MODE=crash-after-commit CHAOS_TARGET=wallet-service CHAOS_EVENTS=1 CHAOS_FIRST_AT=10 \
  experiments/run-experiment.sh naive 50 30 2>&1 \
  | grep -E '^\[|event|money_conserved|recon_(lost|inconsistent|consistency)'
```
Скрипт сам перезапускає стек, робить розігрів, навантаження 50 переказів/с, один збій Wallet одразу після commit,
злив і звірку. У відео лиши:
- рядок `event 1: crash-after-commit wallet-service ... downtime_s=5`;
- фінальний блок: `money_conserved True`, `recon_lost_result_money_moved 1`, `recon_inconsistent 1`.

`grep` лише прибирає зайве з виводу. Без нього скрипт друкує повний підсумок прогону.

## 4. Той самий збій в outbox (~30 с + ~20 с Grafana)
```bash
LABEL=demo CHAOS_MODE=crash-after-commit CHAOS_TARGET=wallet-service CHAOS_EVENTS=1 CHAOS_FIRST_AT=10 \
  experiments/run-experiment.sh outbox 50 30 2>&1 \
  | grep -E '^\[|event|money_conserved|recon_(lost|inconsistent|consistency)'
```
У відео: `event 1: ...` і фінальний блок, де всі `recon_lost_*` і `recon_inconsistent` дорівнюють 0, а
`recon_consistency_rate` — 1.0.

**Одразу після цього** відкрий Grafana (стек лишається запущеним):
http://localhost:3000/d/payflow-experiment?orgId=1&from=now-5m&to=now

Покажи панелі «End-to-end saga latency» і «Outbox: lag and backlog»: там видно сплеск під час падіння Wallet і
повернення до норми. Вхід не потрібен, бо анонімний перегляд увімкнено.

## 5. Підсумок серії (~20 с)
```bash
awk -F, '$3 ~ /^series-20261112\/(crash|kill)/ {sub(/series-20261112\//,"",$3); f[$3]+=$8; d[$3]+=$37} END {for (k in f) printf "%-26s faults=%-3d inconsistent=%d\n", k, f[k], d[k]}' experiments/results/summary.csv | sort
```
Команда підсумовує сирі дані серії `series-20261112`: скільки відмов внесено і скільки розбіжних переказів
лишилося. Очікуваний вивід:
```
crash-transaction-naive    faults=9   inconsistent=9
crash-transaction-outbox   faults=9   inconsistent=0
crash-wallet-naive         faults=9   inconsistent=9
crash-wallet-outbox        faults=9   inconsistent=0
kill-transaction-naive     faults=9   inconsistent=0
kill-transaction-outbox    faults=9   inconsistent=0
kill-wallet-naive          faults=9   inconsistent=1
kill-wallet-outbox         faults=9   inconsistent=0
```

## 6. Після запису
```bash
docker compose --profile app down -v
git checkout -- experiments/results/summary.csv
```
Друга команда прибирає рядки демо-прогонів (`label=demo`), які скрипт дописав у `summary.csv`. Якщо в цьому
файлі були інші незакомічені зміни, спершу перевір `git diff experiments/results/summary.csv`.

## Текст під голос
| Кадр | Текст |
|---|---|
| Початок | «Покажу роботу PayFlow і головну відмінність двох стратегій доставки подій» |
| 1 | «Уся система піднімається однією командою: два сервіси, PostgreSQL, Kafka, Prometheus і Grafana» |
| 2 | «Переказ асинхронний: API одразу повертає PENDING, сага через Kafka доводить його до COMPLETED, баланс змінився» |
| 3 | «Wallet падає одразу після commit, ще до відправки події. Гроші переказано і сума збережена, але звірка знаходить переказ, де клієнт бачить PENDING» |
| 4 | «Той самий збій з outbox: подія лежала в таблиці outbox разом зі станом і після перезапуску дійшла. Розбіжностей нуль» |
| 4, Grafana | «На графіках видно сплеск затримки, поки Wallet перезапускався, і повне відновлення» |
| 5 | «У серії з 42 прогонів naive дав розбіжність після кожного збою після commit, outbox — жодної» |

## Що важливо не зіпсувати
- Числа в кадрі 5 беруться із серії `series-20261112`, а не з демо-прогонів: демо — ілюстрація, а не результат.
- Якщо демо-прогін naive дасть не рівно одну розбіжність, нічого не підганяй: скажи те, що бачиш на екрані.
- Кадр 2 можна записати й після кадрів 3–4, на стеку, що лишився запущеним. Тоді в Swagger з'явиться ще
  `chaos-controller`, бо chaos-прогони вмикають його. Щоб його не було, перед кадром 2 виконай
  `STRATEGY=naive CHAOS_ENABLED=false docker compose --profile app up -d --wait wallet-service transaction-service`.
