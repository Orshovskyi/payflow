# Експерименти

Методика описана в [ADR-0005](../docs/adr/0005-experiment-methodology.md).

## Один прогін
```bash
BUILD=1 experiments/run-experiment.sh naive 50 120     # стратегія, переказів/с, тривалість сталого навантаження (с)
OUTBOX_POLL_INTERVAL=10ms experiments/run-experiment.sh outbox 50 120
```
`BUILD=1` збирає jar-файли й образи; потрібен після кожної зміни коду. `LABEL="..."` додає довільну позначку до
рядка результатів.

## Результати
- `results/summary.csv` — по рядку на прогін: HTTP-затримки (Gatling), наскрізна затримка саги, лаг outbox,
  кількість переказів, перевірка інваріанту, аномалії.
- `results/<run-id>/` — `summary.json`, логи, HTML-звіти Gatling (`gatling-report/index.html`). У git не
  зберігаються.
- Під час і після прогону стек лишається запущеним: дашборд **PayFlow · Experiment** у Grafana
  (http://localhost:3000).

Перед прогоном для роботи закомітьте код: прогін із позначкою `-dirty` у полі `commit` не можна відтворити.
