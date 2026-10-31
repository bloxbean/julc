#!/usr/bin/env python3
"""ADR-068 D13: compare a ledger with the reference ledger, case by case and run by run.

    python3 adr/evidence/068/compare-ledgers.py <ref prefix> <new prefix> [--variant <name>=<prefix> ...] > diff.tsv

    [--jvm-label <label>]

A prefix names a ledger's files as Adr068BaselineTest writes them, renamed: <prefix>.tsv, <prefix>-semantic.tsv and
<prefix>-budget.tsv (for example adr/evidence/068/ledger-b0). A variant is a ledger of the new code with one of its
changes disabled; a case whose cells differ between the new ledger and a variant is attributed to that variant's label.
A case whose JVM observation changed is attributed to the --jvm-label (a change to JuLC's off-chain classes). The
attribution column ends with the case's D13 class, from what differs. Where both ledgers have a
<prefix>-instantiations.tsv, its changed specializations follow as rows of part "instantiation". The evaluation
ledgers record what each run's own tests evaluated, so they are not compared row by row. Totals per kind of change
go to standard error.
"""
import collections
import os
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


def instantiations(prefix):
    """The specialization ledger (<prefix>-instantiations.tsv), keyed by case and request; empty when absent."""
    if not os.path.exists(prefix + "-instantiations.tsv"):
        return {}
    return {(key, row["request"]): row for key, rows in read(prefix + "-instantiations.tsv").items() for row in rows}


def cells(row):
    return tuple(row[level] for level in LEVELS)


def short(cell):
    """An accepted cell by its FLAT size (a library provider's by its digest), a rejection by its code."""
    if cell.startswith("A:"):
        _, digest, size = cell.split(":")
        return size if size != "-" else "lib:" + digest[:8]
    return cell.split("@")[0]


def agreements(verdicts):
    """How many of a run's levels agree with the JVM."""
    return verdicts.count("AGREE")


def run_changes(key, sa, sb, ba, bb):
    """What changed in a case's runs; a run only the reference has is reported, never skipped (review F731)."""
    changes = [run + ":removed" for (k, run) in sorted(sa) if k == key and (k, run) not in sb]
    changes += [run + ":budget-removed" for (k, run) in sorted(ba) if k == key and (k, run) not in bb and (k, run) in sb]
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
        if (k, run) in ba and (k, run) in bb and any(ba[(k, run)][level] != bb[(k, run)][level] for level in LEVELS):
            what.append("budget")
        if what:
            changes.append(run + ":" + "+".join(what))
    return changes


def semantic_class(ra_rb_changes):
    """The semantic part of a difference. A change is a fix only if more levels agree with the JVM (review F732)."""
    observed = [c.rsplit(":", 1)[1] for c in ra_rb_changes if ":" in c]
    if any(o in ("removed", "budget-removed") for o in observed):
        return "lost observation"
    transitions = [o[o.index("verdict ") + 8:].split(">") for o in observed if "verdict " in o]
    if any(agreements(new) < agreements(old) for old, new in transitions):
        return "semantic regression"
    if any(agreements(new) > agreements(old) for old, new in transitions):
        return "semantic fix"
    if any("uplc" in o or "jvm" in o or "verdict" in o for o in observed):
        return "semantic change, unresolved"
    return None


def d13_class(kinds, changes):
    """The D13 class of a difference, from what differs. Each part is kept: a diagnostic change does not hide a
    semantic one (review F732)."""
    observed = [c.rsplit(":", 1)[1] for c in changes if ":" in c]
    parts = []
    if any(k.startswith("newly rejected") for k in kinds):
        parts.append("policy rejection")
    elif any(k.startswith("newly accepted") for k in kinds):
        parts.append("accepted by decision")
    elif any(k.startswith("diagnostic ") for k in kinds):
        parts.append("diagnostic change")
    semantic = semantic_class(changes)
    if semantic:
        parts.append(semantic)
    elif not parts:
        if any("budget" in o for o in observed):
            parts.append("cost change")
        elif "bytes" in kinds:
            parts.append("semantics not observed")
        elif "diagnostics-digest" in kinds:
            parts.append("diagnostic change")
        else:
            parts.append("provenance change")
    return ", ".join(parts)


def main(args):
    ref, new, variants, variants_i, jvm_label = args[0], args[1], [], [], None
    for i in range(2, len(args), 2):
        if args[i] == "--jvm-label":
            jvm_label = args[i + 1]
            continue
        assert args[i] == "--variant", "usage: <ref prefix> <new prefix> [--variant label=prefix ...] [--jvm-label label]"
        name, prefix = args[i + 1].split("=", 1)
        variants.append((name, ledger(prefix)))
        variants_i.append((name, instantiations(prefix)))
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
        diagnostics = a[key].get("diagnostics") != b[key].get("diagnostics")
        if ca == cb and srcmap == "same" and not changes and not diagnostics:
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
        if diagnostics:
            kinds.add("diagnostics-digest")
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
    # Generic specializations (LibraryRequest.Instantiate), where both ledgers have them: one row per changed request.
    ia, ib = instantiations(ref), instantiations(new)
    for key in sorted(set(ia) | set(ib)):
        if key not in ia or key not in ib:
            totals["instantiation " + ("added" if key not in ia else "removed")] += 1
            print("\t".join([key[0] + ":" + key[1], "instantiation", "added" if key not in ia else "removed",
                             "-", "-", "-", "-"]))
            continue
        if cells(ia[key]) == cells(ib[key]):
            continue
        totals["instantiation changed"] += 1
        levels = " ".join(level + " " + short(x) + ">" + short(y)
                          for level, x, y in zip(LEVELS, cells(ia[key]), cells(ib[key])) if x != y)
        attribution = [name for name, v in variants_i if key in v and cells(v[key]) != cells(ib[key])]
        print("\t".join([key[0] + ":" + key[1], "instantiation", "changed", levels, "-",
                         "; ".join(attribution + ["specialization change"]), "-"]))
    for kind, n in sorted(totals.items()):
        print("total\t" + kind + "\t" + str(n), file=sys.stderr)


main(sys.argv[1:])
