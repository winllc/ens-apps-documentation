#!/usr/bin/env python3
"""Splunk scripted input: pull ENS audit events from an application's API.

    ens_audit_pull.py <application base URL> <token file> [checkpoint dir]

Prints every event recorded since the last run, one JSON line each, oldest
first, and remembers the last sequence number so nothing is read twice and
nothing is missed across restarts. Also works outside Splunk, to feed any
other collector. Uses only the Python standard library.
"""

import json
import os
import re
import sys
import urllib.request

PAGE = 1000


def checkpoint_dir(argv):
    if len(argv) > 3:
        return argv[3]
    home = os.environ.get("SPLUNK_HOME")
    if home:
        return os.path.join(home, "var", "lib", "splunk", "modinputs", "ens_audit_pull")
    return os.path.join(os.path.expanduser("~"), ".ens_audit_pull")


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    base = argv[1].rstrip("/")
    with open(argv[2], encoding="utf-8") as f:
        token = f.read().strip()
    state_dir = checkpoint_dir(argv)
    os.makedirs(state_dir, exist_ok=True)
    state = os.path.join(state_dir, re.sub(r"[^A-Za-z0-9.-]+", "_", base) + ".seq")
    after = 0
    if os.path.exists(state):
        with open(state, encoding="utf-8") as f:
            after = int(f.read().strip() or 0)

    while True:
        request = urllib.request.Request(
            f"{base}/api/audit/events?after={after}&limit={PAGE}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/x-ndjson"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
        count = 0
        for line in body.splitlines():
            if not line.strip():
                continue
            seq = json.loads(line)["seq"]
            sys.stdout.write(line + "\n")
            after = max(after, seq)
            count += 1
        sys.stdout.flush()
        # Save after each page: a crash re-reads at most one page, and Splunk
        # can drop the duplicates by the events' "id".
        tmp = state + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(str(after))
        os.replace(tmp, state)
        if count < PAGE:
            return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
