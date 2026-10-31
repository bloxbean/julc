#!/usr/bin/env python3
"""Compares what the tests evaluated in two corpus recordings (ADR-068 I1, D13).

Usage: compare-evaluations.py <ref recording> <new recording> [--exclude <file>]...

Replaying a recorded evaluation needs its script to compile to the recorded digest, so a script that changed loses its
evaluations exactly where they matter (review F730). This compares by what does not depend on the script: an evaluation
is identified by the test that made it, the VM (provider, target, cost configuration), the budget limit and the
arguments. Its observation is the outcome, the value, the traces and the failure.

Rows go to standard output, one per identity whose observations differ or that only one recording has; nothing is left
out silently. Totals go to standard error. An --exclude file lists tests, one per line, whose evaluations draw fresh
inputs on each run; its exclusions are counted.

One test can evaluate several scripts with the same arguments, so an identity can hold several evaluations. Evaluations
whose script and observation both recordings have are set aside, and what remains is compared as a multiset. When one
evaluation remains on each side, the old and the new script are an exact pair, and their budgets are compared; when
more remain, they match only as a group, so the totals count the two apart.
"""
import collections
import os
import sys


def read_evaluation(path):
    fields, args, configs = {}, [], []
    with open(os.path.join(path, "evaluation.txt")) as f:
        for line in f:
            key, sep, value = line.rstrip("\n").partition(": ")
            if not sep:
                continue
            if key == "arg":
                args.append(value)
            elif key == "config":
                configs.append(value)
            else:
                fields[key] = value
    origins = []
    for name in sorted(os.listdir(path)):
        if name.startswith("origin-"):
            with open(os.path.join(path, name)) as f:
                origins.append(f.read().strip())
    return fields, args, configs, origins


def load(root, excluded, totals, label):
    """identity -> list of (observation, program digest, consumed)."""
    identities = collections.defaultdict(list)
    base = os.path.join(root, "evaluations")
    for name in sorted(os.listdir(base)):
        fields, args, configs, origins = read_evaluation(os.path.join(base, name))
        totals[label + " evaluations"] += 1
        for origin in origins:
            if origin in excluded:
                totals[label + " excluded (test, evaluation) pairs"] += 1
                continue
            identity = (origin, fields.get("provider", "-"), fields.get("target", "-"), tuple(configs),
                        fields.get("budget", "-"), tuple(args))
            observation = tuple(fields.get(k, "-") for k in ("outcome", "value", "traces", "error"))
            identities[identity].append((observation, fields.get("program", "-").split(" ")[0],
                                         fields.get("consumed", "-")))
    return identities


def short(observations):
    return ";".join(sorted({"/".join(x[:16] for x in o) for o in observations})) or "-"


def main(args):
    ref, new, excluded = args[0], args[1], set()
    for i in range(2, len(args), 2):
        assert args[i] == "--exclude", "usage: <ref recording> <new recording> [--exclude <file>]..."
        with open(args[i + 1]) as f:
            excluded.update(l.strip() for l in f if l.strip() and not l.startswith("#"))
    totals = collections.Counter()
    a, b = load(ref, excluded, totals, "ref"), load(new, excluded, totals, "new")
    print("kind\ttest\tprovider\ttarget\tbudget limit\targs\tref observations\tnew observations")
    for identity in sorted(set(a) | set(b)):
        origin, provider, target, _, _, arguments = identity
        ra, rb = a.get(identity, []), b.get(identity, [])
        if not ra or not rb:
            kind = "only in new" if ra == [] else "only in ref"
            totals[kind] += 1
            print("\t".join([kind, origin, provider, target, identity[4], str(len(arguments)),
                             short(r[0] for r in ra), short(r[0] for r in rb)]))
            continue
        ca = collections.Counter((r[1], r[0]) for r in ra)
        cb = collections.Counter((r[1], r[0]) for r in rb)
        common = ca & cb
        totals["same script, same observation"] += sum(common.values())
        rest_a, rest_b = list((ca - common).elements()), list((cb - common).elements())
        if not rest_a and not rest_b:
            continue
        if collections.Counter(o for _, o in rest_a) != collections.Counter(o for _, o in rest_b):
            # Scripts can merge or split (two forms now compile alike), which changes only how often an observation
            # occurs. An observation one recording never has, for this identity, is a difference.
            same_set = {r[0] for r in ra} == {r[0] for r in rb}
            kind = "same observations, count differs" if same_set else "differs"
            totals[kind] += 1
            print("\t".join([kind, origin, provider, target, identity[4], str(len(arguments)),
                             short(sorted({o for _, o in rest_a})), short(sorted({o for _, o in rest_b}))]))
            continue
        if len(rest_a) == 1:
            totals["changed script, same observation: exact pair"] += 1
            consumed_a = next(r[2] for r in ra if (r[1], r[0]) == rest_a[0])
            consumed_b = next(r[2] for r in rb if (r[1], r[0]) == rest_b[0])
            x, y = [[int(n) for n in c.split(" ")] for c in (consumed_a, consumed_b)]
            if any(q > p for p, q in zip(x, y)):
                totals["exact pairs whose budget grew"] += 1
                print("\t".join(["budget grew", origin, provider, target, identity[4], str(len(arguments)), " ".join(map(str, x)),
                                 " ".join(map(str, y))]))
            elif x != y:
                totals["exact pairs whose budget shrank"] += 1
        else:
            totals["changed scripts, same observations: group of " + ("2-9" if len(rest_a) < 10 else "10+")] += 1
            totals["evaluations in groups"] += len(rest_a)
    for kind, n in sorted(totals.items()):
        print("total\t" + kind + "\t" + str(n), file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1:])
