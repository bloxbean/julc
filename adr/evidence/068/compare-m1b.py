#!/usr/bin/env python3
"""ADR-068 M1b: byte identity against the reference, and every rewrite against the source it replaced.

    python3 adr/evidence/068/compare-m1b.py <off> <enforce> <rewritten> <gate tsv> <M0 recording> <M1b recording> <out dir> <gate tsv of M0's recording>

<off> and <enforce> are adr068Baseline output directories over M0's recordings, gate off and -Padr068.gate=enforce;
<rewritten> the same task over a recording of this head, gate off; <gate tsv> javacGateCorpus's per-case file for that
recording. Writes m1b-rewrites.tsv and m1b-diagnostic-changes.tsv to <out dir> and prints the totals.
"""
import collections
import gzip
import os
import re
import sys

REF = "adr/evidence/068/"
LEVELS = ["NONE", "BASELINE", "PV11_SAFE", "PV11_COSTED"]


def rows(path):
    with (gzip.open(path, "rt") if path.endswith(".gz") else open(path)) as lines:
        for line in lines:
            if not line.startswith(("#", "case\t", "evaluation\t")):
                yield line.rstrip("\n").split("\t")


def ledger(path):
    return {f[0]: f for f in rows(path)}


def corpus(path):
    return {f[0]: (f[1], (f[2] if len(f) > 2 else "").split(",")) for f in rows(path)}


def cells(f):
    return tuple(f[8:12])


def size(f, level):
    return int(f[8 + level].split(":")[2])


def code(cell):
    parts = cell.split("@")[0][2:].split("/")
    return "/".join(parts[:2]) if parts[0] == "uncoded" else parts[0]


def source(recording, case):
    for line in open(f"{recording}/cases/{case}/case.txt"):
        if line.startswith("source: "):
            return open(f"{recording}/sources/{line.split()[1]}.java").read()
    return ""


def identity(ref, off, enf):
    shared = ref.keys() & off.keys()
    differ = [k for k in shared if ref[k][1:] != off[k][1:]]
    print(f"B1 {len(ref)} cases; gate off {len(off)}; shared {len(shared)}; shared rows differing {len(differ)}")
    print(f"sources M1b changed: {len(ref.keys() - off.keys())}")
    classes = collections.Counter()
    for key, row in off.items():
        gated = enf[key]
        if cells(row) == cells(gated):
            state = row[10][:2]
            classes[{"A:": "accepted, byte-identical with the gate on", "R:": "JuLC rejects it either way, same cell"}
                    .get(state, "unreplayable")] += 1
        elif not gated[10].startswith("R:JULC008"):
            classes["VIOLATION"] += 1
        elif row[10].startswith("A:"):
            classes["accepted with the gate off, javac rejects (javac rejection)"] += 1
        else:
            classes["rejected either way, javac first (diagnostic change)"] += 1
    for name, count in classes.most_common():
        print(f"{count}\t{name}")


def evaluations(path):
    by_program = collections.defaultdict(list)
    for f in rows(path):
        by_program[f[1].split(":")[0]].append(f)
    return by_program


def budget(old_row, new_row, old_evals, new_evals):
    total = [0, 0, 0, 0, 0]
    for level in range(4):
        old = {(e[6], e[3], e[4]): e[11] for e in old_evals.get(old_row[8 + level].split(":")[1][:16], [])}
        new = {(e[6], e[3], e[4]): e[11] for e in new_evals.get(new_row[8 + level].split(":")[1][:16], [])}
        for key in old.keys() & new.keys():
            before, after = old[key].split("/"), new[key].split("/")
            if before[0].isdigit() and after[0].isdigit():
                total[0] += 1
                total[1] += int(before[0])
                total[2] += int(after[0])
                total[3] += int(before[1])
                total[4] += int(after[1])
    return total


# The generated IfLoopContinuationTest shapes: the same program, with xs.size() as the long it is in Java.
SIZE_REWRITES = [("Action action = new Only(mode); return switch (action)", "Action selected = new Only(mode); return switch (selected)"),
                 ("xs.size().compareTo(BigInteger.ZERO) > 0", "xs.size() > 0"), ("xs.size().signum() > 0", "xs.size() > 0"),
                 ("j.compareTo(xs.size())", "j.compareTo(BigInteger.valueOf(xs.size()))"),
                 ("i.compareTo(xs.size())", "i.compareTo(BigInteger.valueOf(xs.size()))"),
                 ("total.compareTo(xs.size())", "total.compareTo(BigInteger.valueOf(xs.size()))"),
                 ("xs.get(j)", "xs.get(j.longValue())"), ("xs.get(i)", "xs.get(i.longValue())")]


def group(test):
    if "IfLoopContinuationTest" in test:
        return "generated loops: xs.size() is a long"
    if re.search("Hygiene|LedgerType|ContainsPolicy|HighLevel", test):
        return "explicit decode at a typed call"
    if "LoopDesugarTest" in test:
        return "equals for == on Data"
    return "ContextsLib.trace for Builtins.trace"


def rewrites(off, enf, new, gate, m0, m1, out):
    old_corpus, new_corpus = corpus(sys.argv[1] + "/corpus-b0.tsv"), corpus(sys.argv[3] + "/corpus-b0.tsv")
    accepted = {f[0]: f[2] == "accepted" for f in rows(gate)}
    old_cases = [k for k, f in off.items()
                 if f[10].startswith("A:") and enf[k][10].startswith("R:") and "recorded" in old_corpus[k][0]]
    print(f"in-repo cases accepted at B1 that javac rejects: {len(old_cases)}")
    java, gate_off = collections.defaultdict(dict), collections.defaultdict(dict)
    for key, row in new.items():
        if row[10].startswith("A:"):
            for origin in new_corpus[key][1]:
                (java if accepted.get(key) else gate_off)[origin].setdefault(cells(row), key)
    classes = collections.Counter()
    differing = []
    for key in old_cases:
        origins = old_corpus[key][1]
        if key in new and not accepted.get(key):
            classes["source unchanged, compiled with the gate off"] += 1
        elif any(cells(off[key]) in java[o] for o in origins):
            classes["rewritten, javac accepts, FLAT identical at every level"] += 1
        elif any(cells(off[key]) in gate_off[o] for o in origins):
            classes["made Java as far as it can be, javac still rejects it, gate off, FLAT identical at every level"] += 1
        else:
            classes["rewritten, javac accepts, FLAT differs"] += 1
            differing.append(key)
    for name, count in classes.most_common():
        print(f"{count}\t{name}")

    left = [k for k, row in new.items() if k not in off and row[10].startswith("A:") and accepted.get(k)]
    by_text = {source(m1, k): k for k in left if "IfLoopContinuationTest" in new_corpus[k][1][0]}
    by_test = collections.defaultdict(list)
    for key in left:
        for origin in new_corpus[key][1]:
            by_test[origin].append(key)
    old_evals, new_evals = evaluations(REF + "ledger-b0-evaluations.tsv.gz"), evaluations(sys.argv[3] + "/ledger-b0-evaluations.tsv")
    groups = collections.defaultdict(lambda: [0] * 14)
    with open(os.path.join(out, "m1b-rewrites.tsv"), "w") as file:
        file.write("# ADR-068 M1b: every rewrite whose FLAT differs from B1's for its dialect original. Generated by"
                   " compare-m1b.py; do not edit.\n")
        file.write("group\ttest\told case\tnew case\t" + "\t".join(f"{n} old\t{n} new" for n in LEVELS)
                   + "\tevaluations\tcpu old\tcpu new\tmemory old\tmemory new\n")
        for key in sorted(differing, key=lambda k: old_corpus[k][1][0]):
            test = old_corpus[key][1][0]
            if "IfLoopContinuationTest" in test:
                text = source(m0, key)
                for before, after in SIZE_REWRITES:
                    text = text.replace(before, after)
                match = by_text[text]
            else:
                match = min(by_test[test], key=lambda k: abs(size(new[k], 2) - size(off[key], 2)))
            total = budget(off[key], new[match], old_evals, new_evals)
            name = group(test)
            sizes = [v for level in range(4) for v in (size(off[key], level), size(new[match], level))]
            row = groups[name]
            row[0] += 1
            for i, value in enumerate(sizes + total):
                row[i + 1] += value
            file.write("\t".join(map(str, [name, test.replace("org.julclang.", ""), key[:16], match[:16]] + sizes + total)) + "\n")
    print("group\tcases\tNONE\tBASELINE\tPV11_SAFE\tPV11_COSTED\tevaluations\tcpu\tmemory")
    for name, r in groups.items():
        print(name, r[0], f"{r[1]}->{r[2]}", f"{r[3]}->{r[4]}", f"{r[5]}->{r[6]}", f"{r[7]}->{r[8]}", r[9],
              f"{r[10]}->{r[11]}", f"{r[12]}->{r[13]}", sep="\t")


def diagnostic_changes(off, enf, m0_gate, out):
    keys = {f[0]: f for f in rows(m0_gate)}
    changes = collections.Counter()
    for key, row in off.items():
        if row[10].startswith("R:") and cells(row) != cells(enf[key]):
            found = re.search(r"\((compiler\.err\.[a-z0-9.\-]+)\)", keys[key][4])
            changes[code(row[10]), found.group(1) if found else keys[key][3].split(",")[0]] += 1
    with open(os.path.join(out, "m1b-diagnostic-changes.tsv"), "w") as file:
        file.write("# ADR-068 M1b: negative cases of the frozen corpus javac now rejects first (D13's diagnostic change),"
                   " by B1's code and javac's first key. Generated by compare-m1b.py; do not edit.\nb1\tjavac\tcases\n")
        for (before, after), count in sorted(changes.items(), key=lambda item: (item[0][0], -item[1])):
            file.write(f"{before}\t{after}\t{count}\n")


def main():
    off, enf, new = (ledger(sys.argv[i] + "/ledger-b0.tsv") for i in (1, 2, 3))
    identity(ledger(REF + "ledger-b1.tsv"), off, enf)
    rewrites(off, enf, new, sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7])
    diagnostic_changes(off, enf, sys.argv[8], sys.argv[7])


main()
