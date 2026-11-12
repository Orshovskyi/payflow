# Експерименти

Методика описана в [ADR-0005](../docs/adr/0005-experiment-methodology.md).

## Один прогін
```bash
BUILD=1 experiments/run-experiment.sh naive 50 120     # стратегія, переказів/с, тривалість сталого навантаження (с)
OUTBOX_POLL_INTERVAL=10ms experiments/run-experiment.sh outbox 50 120
```
`BUILD=1` збирає jar-файли й образи; потрібен після кожної зміни коду. `LABEL="..."` додає довільну позначку до
рядка результатів.

## Chaos-прогони
```bash
CHAOS_MODE=crash-after-commit CHAOS_TARGET=wallet-service experiments/run-experiment.sh naive 50 100
CHAOS_MODE=crash-after-commit CHAOS_TARGET=wallet-service experiments/run-experiment.sh outbox 50 100
CHAOS_MODE=kill CHAOS_TARGET=transaction-service experiments/run-experiment.sh naive 50 100
```
- `CHAOS_MODE`: `kill` (SIGKILL у довільний момент) або `crash-after-commit` (падіння одразу після commit).
- `CHAOS_TARGET`: `wallet-service` або `transaction-service`.
- `CHAOS_EVENTS` (3), `CHAOS_FIRST_AT` (20 с від початку заміру), `CHAOS_INTERVAL` (30 с).

Тривалість заміру має покривати всі події: `CHAOS_FIRST_AT + (CHAOS_EVENTS − 1) × CHAOS_INTERVAL` плюс ~15 с
на відновлення. Час кожної події — у `chaos.log`. Обґрунтування режимів — в
[ADR-0007](../docs/adr/0007-chaos-experiments.md).

## Звірка коректності
```bash
python3 -I experiments/reconcile.py              # звіт для запущеного стеку; код виходу 1, якщо є розбіжності
python3 -m unittest discover -s experiments      # тести класифікації
```
Категорії розбіжностей описано в [ADR-0006](../docs/adr/0006-reconciliation.md). Скрипт прогону викликає звірку
автоматично після зливу.

## Результати
- `results/summary.csv` — по рядку на прогін: HTTP-затримки (Gatling), наскрізна затримка саги, лаг outbox,
  кількість переказів, перевірка інваріанту, аномалії, результати звірки (`recon_*`).
- `results/<run-id>/anomalies.csv` — перелік переказів із розбіжностями та їхні категорії.
- `results/<run-id>/` — `summary.json`, логи, HTML-звіти Gatling (`gatling-report/index.html`). У git не
  зберігаються.
- Під час і після прогону стек лишається запущеним: дашборд **PayFlow · Experiment** у Grafana
  (http://localhost:3000).

Перед прогоном для роботи закомітьте код: прогін із позначкою `-dirty` у полі `commit` не можна відтворити.
