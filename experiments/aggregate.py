"""Aggregates one experiment series from results/summary.csv into tables (Markdown) and figures (PNG).

Usage: python3 -I experiments/aggregate.py <series-name> [--out DIR]
Only rows with LABEL "<series>/<config>" and a clean commit (no "-dirty") are used.
For every configuration the median over repetitions is reported together with the min-max range.
"""
import argparse
import csv
import os
import statistics
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator, MaxNLocator, NullFormatter  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SUMMARY = os.path.join(ROOT, "experiments", "results", "summary.csv")

# Reference categorical palette, slots 1-2 (validated pair), plus text/grid tokens.
BLUE, ORANGE = "#2a78d6", "#eb6834"
TEXT, MUTED, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"

BASELINE = [("base-naive", "naive"), ("base-outbox-10ms", "outbox 10 мс"),
            ("base-outbox-50ms", "outbox 50 мс"), ("base-outbox-100ms", "outbox 100 мс")]
CHAOS = [("crash-wallet", "збій Wallet\nпісля commit"), ("crash-transaction", "збій Transaction\nпісля commit"),
         ("kill-wallet", "kill -9\nWallet"), ("kill-transaction", "kill -9\nTransaction")]
HIGH = [("high-naive", "naive"), ("high-outbox-50ms", "outbox 50 мс")]


def number(value):
    if value in ("", "None", None):
        return None
    try:
        return float(value)
    except ValueError:
        return None


def load(series, summary):
    groups = defaultdict(list)
    with open(summary) as f:
        for row in csv.DictReader(f):
            label = row.get("label", "")
            if not label.startswith(series + "/") or row.get("commit", "").endswith("-dirty"):
                continue
            groups[label.split("/", 1)[1]].append(row)
    return groups


def stat(rows, key):
    """(median, min, max, n) over repetitions, ignoring missing values."""
    values = [v for v in (number(r.get(key)) for r in rows) if v is not None]
    if not values:
        return None
    return statistics.median(values), min(values), max(values), len(values)


def fmt(s, digits=1):
    if s is None:
        return "—"
    median, low, high, _ = s
    if low == high:
        return f"{median:.{digits}f}"
    return f"{median:.{digits}f} ({low:.{digits}f}–{high:.{digits}f})"


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


def style(ax, title, ylabel):
    ax.set_facecolor(SURFACE)
    ax.set_title(title, loc="left", fontsize=12, color=TEXT, pad=12)
    ax.set_ylabel(ylabel, color=MUTED, fontsize=10)
    ax.tick_params(which="both", colors=MUTED, labelsize=9, length=0)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(GRID)


def value_label(v):
    if v == int(v):
        return f"{int(v)}"
    return f"{v:.0f}" if v >= 10 else f"{v:.1f}"


def total(rows, key):
    return int(sum(number(r[key]) or 0 for r in rows))


def per_run(rows, key):
    return " / ".join(str(int(number(r[key]) or 0)) for r in sorted(rows, key=lambda r: r["run_id"]))


def total_bars(ax, labels, series):
    """series: list of (name, color, [int or None per label]). Integer totals, labelled above each bar."""
    width = min(0.8 / len(series), 0.32)
    for i, (name, color, values) in enumerate(series):
        xs = [x + (i - (len(series) - 1) / 2) * width for x in range(len(labels))]
        ax.bar(xs, [v or 0 for v in values], width - 0.03, color=color, label=name, edgecolor=SURFACE,
               linewidth=1)
        for x, v in zip(xs, values):
            if v is not None:
                ax.annotate(str(v), (x, v), textcoords="offset points", xytext=(0, 4), ha="center",
                            fontsize=8, color=TEXT)
    ax.set_xticks(range(len(labels)), labels)
    ax.yaxis.set_major_locator(MaxNLocator(integer=True))
    ax.margins(y=0.12)
    ax.legend(frameon=False, fontsize=9, labelcolor=TEXT, loc="lower right", bbox_to_anchor=(1, 1.0),
              ncol=len(series), handlelength=1, borderaxespad=0.2)


def grouped_dots(ax, labels, series):
    """Log-scale latency: a dot per median and a line for the min-max range. Bars would be misleading on a
    log axis, which has no zero for a bar to start from."""
    offset = 0.12
    for i, (name, color, stats) in enumerate(series):
        xs = [x + (i - (len(series) - 1) / 2) * 2 * offset for x in range(len(labels))]
        for x, s in zip(xs, stats):
            if s is None:
                continue
            ax.vlines(x, s[1], s[2], color=color, linewidth=2)
            ax.annotate(value_label(s[0]), (x, s[0]), textcoords="offset points", xytext=(9, -3),
                        fontsize=8, color=TEXT)
        present = [(x, s[0]) for x, s in zip(xs, stats) if s is not None]
        ax.plot([x for x, _ in present], [m for _, m in present], "o", color=color, markersize=8,
                markeredgecolor=SURFACE, markeredgewidth=2, linestyle="none", label=name)
    ax.set_xticks(range(len(labels)), labels)
    ax.set_xlim(-0.5, len(labels) - 0.5)
    ax.set_yscale("log")
    ax.yaxis.set_major_locator(LogLocator(base=10, subs=(1, 2, 5)))
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    ax.yaxis.set_minor_formatter(NullFormatter())
    ax.legend(frameon=False, fontsize=9, labelcolor=TEXT, loc="lower right", bbox_to_anchor=(1, 1.0),
              ncol=len(series), handlelength=1, borderaxespad=0.2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("series")
    parser.add_argument("--out", default=os.path.join(ROOT, "docs", "thesis-notes"))
    parser.add_argument("--summary", default=SUMMARY)
    args = parser.parse_args()
    groups = load(args.series, args.summary)
    if not groups:
        raise SystemExit(f"No clean rows for series {args.series} in {args.summary}")
    figures = os.path.join(args.out, "figures")
    os.makedirs(figures, exist_ok=True)
    plt.rcParams.update({"font.family": "sans-serif", "figure.facecolor": SURFACE})

    commits = sorted({r["commit"] for rows in groups.values() for r in rows})
    md = [f"# Результати серії `{args.series}`", "",
          f"Згенеровано `experiments/aggregate.py` з `{os.path.relpath(args.summary, ROOT)}`. "
          f"Коміт(и): {', '.join(f'`{c}`' for c in commits)}. "
          "Значення — медіана за повторами, у дужках — мінімум–максимум; n — кількість повторів.", ""]

    # --- Baseline latency
    rows = []
    for key, name in BASELINE + [(k, f"{n}, 200/с") for k, n in HIGH]:
        g = groups.get(key, [])
        if not g:
            continue
        rows.append([name, len(g), g[0]["target_rate"], fmt(stat(g, "e2e_p50_ms")), fmt(stat(g, "e2e_p95_ms")),
                     fmt(stat(g, "e2e_p99_ms")), fmt(stat(g, "http_p99_ms"), 0), fmt(stat(g, "outbox_lag_p99_ms")),
                     fmt(stat(g, "http_requests_ko"), 0), fmt(stat(g, "recon_inconsistent"), 0)])
    md += ["## 1. Затримка без відмов", "",
           table(["Конфігурація", "n", "Переказів/с", "Сага p50, мс", "Сага p95, мс", "Сага p99, мс",
                  "HTTP p99, мс", "Лаг outbox p99, мс", "HTTP-помилки", "Розбіжності"], rows), ""]

    keys = [k for k, _ in BASELINE if k in groups]
    if keys:
        fig, ax = plt.subplots(figsize=(8, 4.2))
        grouped_dots(ax, [n for k, n in BASELINE if k in groups],
                     [("p50", BLUE, [stat(groups[k], "e2e_p50_ms") for k in keys]),
                      ("p99", ORANGE, [stat(groups[k], "e2e_p99_ms") for k in keys])])
        style(ax, "Наскрізна затримка саги без відмов (50 переказів/с)", "мс, логарифмічна шкала")
        ax.grid(axis="y", which="major", color=GRID, linewidth=0.8)
        fig.tight_layout()
        fig.savefig(os.path.join(figures, "latency-baseline.png"), dpi=200)
        md += ["![Затримка без відмов](figures/latency-baseline.png)", ""]

    # --- Chaos correctness. Loss counts are small integers, so a median over three runs would hide a single
    # loss (0, 1, 0 -> 0). Every run is shown instead, together with the total over all faults injected.
    rows = []
    for key, name in CHAOS:
        for strategy in ("naive", "outbox"):
            g = groups.get(f"{key}-{strategy}", [])
            if not g:
                continue
            rows.append([name.replace("\n", " "), strategy, len(g), total(g, "chaos_events"),
                         f"**{total(g, 'recon_inconsistent')}** ({per_run(g, 'recon_inconsistent')})",
                         total(g, "recon_lost_request"), total(g, "recon_lost_result_money_moved"),
                         fmt(stat(g, "recon_consistency_rate"), 5),
                         "так" if all(r["money_conserved"] == "True" for r in g) else "**ні**",
                         fmt(stat(g, "http_requests_ko"), 0), fmt(stat(g, "e2e_p99_ms"), 0)])
    md += ["## 2. Коректність під час відмов", "",
           "Кількості розбіжностей — **сума за всі повтори**, у дужках — значення кожного прогону. "
           "Медіана тут не підходить: при трьох повторах вона ховає поодиноку втрату (0, 1, 0 → 0). "
           "`consistency_rate`, HTTP-помилки й затримка — медіана (мін–макс).", "",
           table(["Сценарій", "Стратегія", "n", "Відмов усього", "Розбіжності", "lost_request",
                  "lost_result_money_moved", "consistency_rate", "Сума збережена", "HTTP-помилки",
                  "Сага p99, мс"], rows), ""]

    present = [(k, n) for k, n in CHAOS if f"{k}-naive" in groups or f"{k}-outbox" in groups]
    if present:
        faults = sorted({total(groups[f"{k}-{s}"], "chaos_events")
                         for k, _ in present for s in ("naive", "outbox") if f"{k}-{s}" in groups})
        fig, ax = plt.subplots(figsize=(8, 4.2))
        total_bars(ax, [n for _, n in present], [
            (s, c, [total(groups[f"{k}-{s}"], "recon_inconsistent") if f"{k}-{s}" in groups else None
                    for k, _ in present]) for s, c in (("naive", BLUE), ("outbox", ORANGE))])
        style(ax, f"Розбіжні перекази після відмов ({' / '.join(map(str, faults))} відмов на сценарій)",
              "переказів, сума за всі повтори")
        fig.tight_layout()
        fig.savefig(os.path.join(figures, "chaos-inconsistencies.png"), dpi=200)
        md += ["![Розбіжності після відмов](figures/chaos-inconsistencies.png)", ""]

    path = os.path.join(args.out, f"results-{args.series}.md")
    with open(path, "w") as f:
        f.write("\n".join(md) + "\n")
    print(f"Wrote {path} and figures in {figures}")


if __name__ == "__main__":
    main()
