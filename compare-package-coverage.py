#!/usr/bin/env python3
"""Compare per-package coverage against a committed baseline.

Called by coverage-ratchet.sh, which owns the human-facing output. This script
only classifies, so the shell has nothing to parse out of prose:

    REGRESSED <package> <baseline> <current>
    ADDED     <package> <current>
    DEPARTED  <package> <baseline>
    RAISED    <package> <baseline> <current>

Usage: compare-package-coverage.py <baseline-file> <tolerance> <out-file>
       with `<package> <pct>` lines on stdin.

`<out-file>` receives the baseline as it should be after this run: every current
package at max(baseline, current), so a rise ratchets up and a within-tolerance
dip keeps the higher number. Departed packages are dropped, which is what stops
the file rotting. The caller decides whether to install it.

Exits non-zero only on malformed input. A regression is reported on stdout, not
by exit status, so the caller can print every offender before failing.
"""

import sys


def parse(lines, source):
    """Parse `<package> <pct>` lines into a dict.

    Anything unparseable is fatal rather than skipped, including a duplicate
    package: a file this gate cannot read exactly is a gate enforcing nothing,
    and a duplicate would silently keep only the last value — lowering a floor
    without showing as a change in the diff.
    """
    out = {}
    for lineno, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            sys.exit(f"{source}:{lineno}: expected '<package> <pct>', got {line!r}")
        name, pct = parts
        if name in out:
            sys.exit(f"{source}:{lineno}: duplicate entry for {name}")
        try:
            out[name] = float(pct)
        except ValueError:
            sys.exit(f"{source}:{lineno}: {pct!r} is not a percentage")
    return out


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    baseline_file, tolerance, out_file = sys.argv[1], float(sys.argv[2]), sys.argv[3]

    # Read stdin first, then the baseline. The producer (a JaCoCo XML pass) is
    # still writing when this process exits, so bailing on a malformed baseline
    # before draining stdin leaves it with a closed pipe and prints a
    # BrokenPipeError over the actual error message.
    current = parse(sys.stdin, "<report>")
    with open(baseline_file) as f:
        baseline = parse(f, baseline_file)

    if not baseline:
        sys.exit(f"{baseline_file}: no package entries — the gate would be inert")
    if not current:
        sys.exit("<report>: no package entries — nothing to compare")

    regressed, added, departed, raised = [], [], [], []
    for name, now in sorted(current.items()):
        was = baseline.get(name)
        if was is None:
            added.append((name, now))
        elif now + tolerance < was:
            regressed.append((name, was, now))
        elif now > was:
            raised.append((name, was, now))

    for name in sorted(set(baseline) - set(current)):
        departed.append((name, baseline[name]))

    for name, was, now in regressed:
        print(f"REGRESSED {name} {was:.2f} {now:.2f}")
    for name, now in added:
        print(f"ADDED {name} {now:.2f}")
    for name, was in departed:
        print(f"DEPARTED {name} {was:.2f}")
    for name, was, now in raised:
        print(f"RAISED {name} {was:.2f} {now:.2f}")

    ratcheted = {n: max(v, baseline.get(n, v)) for n, v in current.items()}
    with open(out_file, "w") as out:
        for name in sorted(ratcheted):
            out.write(f"{name} {ratcheted[name]:.2f}\n")



def self_test():
    """Verify the classifier against the cases cu-135 was filed for.

    Runs on every ratchet invocation, because a gate whose own logic has rotted
    is the failure this whole script exists to prevent — and the repo rule is
    that a check must be proven able to fail (see RoomSchemaTest).
    """
    import io
    import tempfile

    def classify(baseline, current, tolerance=0.50):
        with tempfile.TemporaryDirectory() as d:
            bl = f"{d}/baseline.txt"
            out = f"{d}/out.txt"
            with open(bl, "w") as f:
                for name, pct in baseline.items():
                    f.write(f"{name} {pct:.2f}\n")
            stdin = io.StringIO("".join(f"{n} {p:.2f}\n" for n, p in current.items()))
            saved, sys.stdin = sys.stdin, stdin
            captured, sys.stdout = sys.stdout, io.StringIO()
            try:
                sys.argv = ["self-test", bl, str(tolerance), out]
                main()
                verdicts = sys.stdout.getvalue().splitlines()
            finally:
                sys.stdin, printed = saved, sys.stdout
                sys.stdout = captured
            with open(out) as f:
                written = dict(
                    (line.split()[0], float(line.split()[1])) for line in f if line.strip()
                )
            return verdicts, written

    failures = []

    def expect(label, condition, detail=""):
        if not condition:
            failures.append(f"{label}{': ' + detail if detail else ''}")

    # The whole point of the gate: one package rots while the total is fine.
    verdicts, _ = classify({"a": 50.0, "b": 10.0}, {"a": 50.0, "b": 2.0})
    expect("a package drop is REGRESSED", verdicts == ["REGRESSED b 10.00 2.00"], str(verdicts))

    # Jitter inside the tolerance is not a regression, and must not lower the floor.
    verdicts, written = classify({"a": 50.0}, {"a": 49.7})
    expect("a dip inside tolerance passes", verdicts == [], str(verdicts))
    expect("...and keeps the higher floor", written == {"a": 50.0}, str(written))

    # A rise ratchets up.
    verdicts, written = classify({"a": 50.0}, {"a": 55.0})
    expect("a rise is RAISED", verdicts == ["RAISED a 50.00 55.00"], str(verdicts))
    expect("...and lifts the floor", written == {"a": 55.0}, str(written))

    # A new package is recorded, never silently admitted.
    verdicts, written = classify({"a": 50.0}, {"a": 50.0, "new": 0.0})
    expect("a new package is ADDED", verdicts == ["ADDED new 0.00"], str(verdicts))
    expect("...and seeded", written == {"a": 50.0, "new": 0.0}, str(written))

    # A departed package is pruned so the file cannot rot.
    verdicts, written = classify({"a": 50.0, "gone": 90.0}, {"a": 50.0})
    expect("a departed package is DEPARTED", verdicts == ["DEPARTED gone 90.00"], str(verdicts))
    expect("...and dropped", written == {"a": 50.0}, str(written))

    # Exactly at the tolerance edge is not a failure; just past it is.
    verdicts, _ = classify({"a": 50.0}, {"a": 49.50})
    expect("a drop of exactly the tolerance passes", verdicts == [], str(verdicts))
    verdicts, _ = classify({"a": 50.0}, {"a": 49.49})
    expect("a drop past the tolerance fails", len(verdicts) == 1, str(verdicts))

    if failures:
        print("compare-package-coverage self-test FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        sys.exit(1)
    print(f"compare-package-coverage: self-test passed ({7} behaviours)")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
    else:
        main()
