"""Aggregates a series of the three-service saga (ADR-0008) from results/summary-v2.csv: choreography vs orchestration.

Usage: python3 -I experiments/aggregate_saga.py <series-name> [--out DIR] [--summary FILE]
Only rows with LABEL "<series>/<config>" and a clean commit (no "-dirty") are used. Latency is the median over
repetitions with the min-max range; loss counts are summed over repetitions (a median would hide a single loss).
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # sibling module, also under python3 -I
import aggregate as base  # noqa: E402
from aggregate import BLUE, ORANGE, fmt, load, per_run, plt, stat, style, table, total, total_bars

SUMMARY = os.path.join(base.ROOT, "experiments", "results", "summary-v2.csv")
STYLES = [("choreo", "choreography", BLUE), ("orch", "orchestration", ORANGE)]
LOAD = [("base", "50 переказів/с"), ("high", "200 переказів/с")]
CHAOS = [("crash-wallet", "збій Wallet\nпісля commit"), ("crash-ledger", "збій Ledger\nпісля commit"),
         ("crash-transaction", "збій Transaction\nпісля commit"), ("kill-transaction", "kill -9\nTransaction")]
NAIVE = [("naive-crash-wallet", "naive, збій Wallet після commit")]
# Stuck or wrong transfers, split by what happened to the money.
LOSS_COLUMNS = [("recon_funds_held", "кошти заморожені"), ("recon_lost_request", "lost_request"),
                ("recon_lost_result_money_moved", "lost_result_money_moved"),
                ("recon_lost_result_compensated", "lost_result_compensated"),
                ("recon_lost_result_rejected", "lost_result_rejected"),
                ("recon_compensation_violation", "порушення компенсації")]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("series")
    parser.add_argument("--out", default=os.path.join(base.ROOT, "docs", "thesis-notes"))
    parser.add_argument("--summary", default=SUMMARY)
    args = parser.parse_args()
    groups = load(args.series, args.summary)
    if not groups:
        raise SystemExit(f"No clean rows for series {args.series} in {args.summary}")
    figures = os.path.join(args.out, "figures")
    os.makedirs(figures, exist_ok=True)
    plt.rcParams.update({"font.family": "sans-serif", "figure.facecolor": base.SURFACE})

    commits = sorted({r["commit"] for rows in groups.values() for r in rows})
    md = [f"# Результати серії `{args.series}`: choreography проти orchestration", "",
          f"Згенеровано `experiments/aggregate_saga.py` з `{os.path.relpath(args.summary, base.ROOT)}`. "
          f"Коміт(и): {', '.join(f'`{c}`' for c in commits)}. Доставка — outbox, якщо не вказано інше. "
          "Затримка — медіана за повторами (мінімум–максимум); n — кількість повторів.", ""]

    # --- 1. Latency and messages without failures
    rows = []
    for load_key, load_name in LOAD:
        for key, name, _ in STYLES:
            g = groups.get(f"{load_key}-{key}", [])
            if not g:
                continue
            rows.append([name, load_name, len(g), fmt(stat(g, "e2e_completed_p50_ms")),
                         fmt(stat(g, "e2e_completed_p99_ms")), fmt(stat(g, "e2e_compensated_p50_ms")),
                         fmt(stat(g, "e2e_compensated_p99_ms")), fmt(stat(g, "messages_per_transfer"), 2),
                         fmt(stat(g, "http_p99_ms"), 0), total(g, "recon_inconsistent")])
    md += ["## 1. Затримка саги і кількість повідомлень без відмов", "",
           table(["Координація", "Навантаження", "n", "Успішні p50, мс", "Успішні p99, мс", "Компенсовані p50, мс",
                  "Компенсовані p99, мс", "Повідомлень на переказ", "HTTP p99, мс", "Розбіжності (сума)"], rows), ""]

    present = [(k, n) for k, n in LOAD if any(f"{k}-{s}" in groups for s, _, _ in STYLES)]
    if present:
        fig, ax = plt.subplots(figsize=(8, 4.2))
        _median_bars(ax, [n for _, n in present], [
            (name, color, [stat(groups.get(f"{k}-{key}", []), "e2e_completed_p50_ms") for k, _ in present])
            for key, name, color in STYLES])
        style(ax, "Медіана наскрізної затримки успішного переказу", "мс (мін–макс за повторами)")
        fig.tight_layout()
        fig.savefig(os.path.join(figures, "saga-latency.png"), dpi=200)
        md += ["![Затримка саги](figures/saga-latency.png)", ""]

    # --- 2. Correctness under failures
    rows = []
    for key, name in CHAOS + NAIVE:
        for style_key, style_name, _ in STYLES:
            g = groups.get(f"{key}-{style_key}", [])
            if not g:
                continue
            rows.append([name.replace("\n", " "), style_name, len(g), total(g, "chaos_events"),
                         f"{total(g, 'recon_inconsistent')} ({per_run(g, 'recon_inconsistent')})"]
                        + [total(g, c) for c, _ in LOSS_COLUMNS]
                        + ["так" if all(r["money_conserved"] == "True" for r in g) else "**ні**",
                           fmt(stat(g, "http_requests_ko"), 0), fmt(stat(g, "e2e_p99_ms"), 0)])
    md += ["## 2. Коректність під час відмов", "",
           "Кількості — сума за всі повтори, у дужках — значення кожного прогону.", "",
           table(["Сценарій", "Координація", "n", "Відмов усього", "Розбіжності"] + [n for _, n in LOSS_COLUMNS]
                 + ["Гроші збережено", "HTTP-помилки", "Сага p99, мс"], rows), ""]

    # The naive run is the control group: with outbox every bar is expected to be zero, which alone says little.
    present = [(k, n) for k, n in CHAOS + [("naive-crash-wallet", "naive: збій Wallet\nпісля commit")]
               if any(f"{k}-{s}" in groups for s, _, _ in STYLES)]
    if present:
        fig, ax = plt.subplots(figsize=(8, 4.2))
        total_bars(ax, [n for _, n in present], [
            (name, color, [total(groups[f"{k}-{key}"], "recon_inconsistent") if f"{k}-{key}" in groups else None
                           for k, _ in present]) for key, name, color in STYLES])
        ax.set_ylim(bottom=0, top=max(ax.get_ylim()[1], 1))
        style(ax, "Розбіжні перекази після відмов: outbox і naive", "переказів, сума за всі повтори")
        fig.tight_layout()
        fig.savefig(os.path.join(figures, "saga-chaos.png"), dpi=200)
        md += ["![Розбіжності після відмов](figures/saga-chaos.png)", ""]

    path = os.path.join(args.out, f"results-{args.series}.md")
    with open(path, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"Wrote {path} and figures in {figures}")


def _median_bars(ax, labels, series):
    """Bars of the median with the min-max range as a thin line; a linear axis, so bars start at zero."""
    width = min(0.8 / len(series), 0.32)
    for i, (name, color, stats) in enumerate(series):
        xs = [x + (i - (len(series) - 1) / 2) * width for x in range(len(labels))]
        ax.bar(xs, [s[0] if s else 0 for s in stats], width - 0.03, color=color, label=name,
               edgecolor=base.SURFACE, linewidth=1)
        for x, s in zip(xs, stats):
            if s is None:
                continue
            ax.vlines(x, s[1], s[2], color=base.MUTED, linewidth=1)
            ax.annotate(f"{s[0]:.0f}", (x, s[2]), textcoords="offset points", xytext=(0, 4), ha="center",
                        fontsize=8, color=base.TEXT)
    ax.set_xticks(range(len(labels)), labels)
    ax.margins(y=0.12)
    ax.legend(frameon=False, fontsize=9, labelcolor=base.TEXT, loc="lower right", bbox_to_anchor=(1, 1.0),
              ncol=len(series), handlelength=1, borderaxespad=0.2)


if __name__ == "__main__":
    main()
