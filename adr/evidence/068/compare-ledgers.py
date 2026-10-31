#!/usr/bin/env python3
"""ADR-068 D13: compare a ledger with the reference ledger, case by case and run by run.

    python3 adr/evidence/068/compare-ledgers.py <ref prefix> <new prefix> [--variant <name>=<prefix> ...] > diff.tsv

    [--jvm-label <label>]

A prefix names a ledger's files as Adr068BaselineTest writes them, renamed: <prefix>.tsv, <prefix>-semantic.tsv and
<prefix>-budget.tsv (for example adr/evidence/068/ledger-b0). A variant is a ledger of the new code with one of its
changes disabled; a case whose cells differ between the new ledger and a variant is attributed to that variant's label.
A case whose JVM observation changed is attributed to the --jvm-label (a change to JuLC's off-chain classes). The
attribution column ends with the case's D13 class, from what differs. Totals per kind of change go to standard error.
"""
import collections
import sys

LEVELS = ["NONE", "BASELINE", "PV11_SAFE", "PV11_COSTED"]


def read(path):
    rows, header = {}, None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        cols = line.split("\t")
        if header is None:
            header = cols
            continue
        rows.setdefault(cols[0], []).append(dict(zip(header, cols)))
    return rows


def ledger(prefix):
    return {key: rows[0] for key, rows in read(prefix + ".tsv").items()}


def runs(prefix, suffix):
    return {(key, row["run"]): row for key, rows in read(prefix + suffix).items() for row in rows}


def cells(row):
    return tuple(row[level] for level in LEVELS)


def short(cell):
    """An accepted cell by its FLAT size (a library provider's by its digest), a rejection by its code."""
    if cell.startswith("A:"):
        _, digest, size = cell.split(":")
        return size if size != "-" else "lib:" + digest[:8]
    return cell.split("@")[0]


def run_changes(key, sa, sb, ba, bb):
    changes = []
    for (k, run), rb in sorted(sb.items()):
        if k != key:
            continue
        ra = sa.get((k, run))
        if ra is None:
            changes.append(run + ":new")
            continue
        what = []
        if ra["JVM"] != rb["JVM"]:
            what.append("jvm")
        if any(ra[level] != rb[level] for level in LEVELS):
            what.append("uplc")
        if ra["verdicts"] != rb["verdicts"]:
            what.append("verdict " + ra["verdicts"] + ">" + rb["verdicts"])
        if (k, run) in ba and any(ba[(k, run)][level] != bb[(k, run)][level] for level in LEVELS):
            what.append("budget")
        if what:
            changes.append(run + ":" + "+".join(what))
    return changes


def d13_class(kinds, changes):
    """The D13 class a difference falls in, from what differs (ADR-068 D13)."""
    observed = [c.rsplit(":", 1)[1] for c in changes if ":" in c]
    if any(k.startswith("newly rejected") for k in kinds):
        return "policy rejection"
    if any(k.startswith("newly accepted") for k in kinds):
        return "accepted by decision"
    if any(k.startswith("diagnostic") for k in kinds):
        return "diagnostic change"
    if any("uplc" in o or "jvm" in o or "verdict" in o for o in observed):
        return "semantic fix"
    if any("budget" in o for o in observed):
        return "cost change"
    if "bytes" in kinds:
        return "semantics not observed"
    return "provenance change"


def main(args):
    ref, new, variants, jvm_label = args[0], args[1], [], None
    for i in range(2, len(args), 2):
        if args[i] == "--jvm-label":
            jvm_label = args[i + 1]
            continue
        assert args[i] == "--variant", "usage: <ref prefix> <new prefix> [--variant label=prefix ...] [--jvm-label label]"
        name, prefix = args[i + 1].split("=", 1)
        variants.append((name, ledger(prefix)))
    a, b = ledger(ref), ledger(new)
    sa, sb = runs(ref, "-semantic.tsv"), runs(new, "-semantic.tsv")
    ba, bb = runs(ref, "-budget.tsv"), runs(new, "-budget.tsv")
    by_case = collections.defaultdict(dict)
    for (key, run), row in sb.items():
        by_case[key][(key, run)] = row
    print("case\tparts\tchange\tlevels (ref>new: FLAT size, or rejection code)\tsrcmap\tattribution\truns")
    totals = collections.Counter()
    for key in sorted(set(a) | set(b)):
        if key not in a:
            totals["case added"] += 1
            print("\t".join([key, b[key]["parts"], "added",
                             " ".join(level + " " + short(c) for level, c in zip(LEVELS, cells(b[key]))), "-", "new case", "-"]))
            continue
        if key not in b:
            totals["case removed"] += 1
            print("\t".join([key, a[key]["parts"], "removed", "-", "-", "-", "-"]))
            continue
        ca, cb = cells(a[key]), cells(b[key])
        srcmap = "same" if a[key]["srcmap"] == b[key]["srcmap"] else "changed"
        changes = run_changes(key, sa, by_case[key], ba, bb)
        if ca == cb and srcmap == "same" and not changes:
            continue
        kinds = set()
        for x, y in zip(ca, cb):
            if x == y:
                continue
            if x.startswith("A:") and y.startswith("A:"):
                kinds.add("bytes")
            elif x.startswith("A:"):
                kinds.add("newly rejected " + short(y)[2:])
            elif y.startswith("A:"):
                kinds.add("newly accepted")
            else:
                kinds.add("diagnostic " + short(x)[2:] + ">" + short(y)[2:])
        if srcmap != "same":
            kinds.add("srcmap")
        if changes and not kinds:
            kinds.add("runs only")
        attribution = [name for name, v in variants if key in v and cells(v[key]) != cb]
        if jvm_label and any(":" in c and "jvm" in c.rsplit(":", 1)[1] for c in changes):
            attribution.append(jvm_label)
        attribution.append(d13_class(kinds, changes))
        change = ",".join(sorted(kinds))
        totals[change] += 1
        levels = " ".join(level + " " + short(x) + ">" + short(y) for level, x, y in zip(LEVELS, ca, cb) if x != y)
        print("\t".join([key, b[key]["parts"], change, levels or "-", srcmap, "; ".join(attribution),
                         ";".join(changes) or "-"]))
    for kind, n in sorted(totals.items()):
        print("total\t" + kind + "\t" + str(n), file=sys.stderr)


main(sys.argv[1:])
