#!/usr/bin/env python3
"""Check the integrity chain of an ENS audit trail (ens-audit/1).

Reads NDJSON: an export from an application's audit page, its audit file, or
what the pull API returns. It needs nothing but the events themselves, so an
auditor can run it without access to the application.

    verify_chain.py audit-export.ndjson [more.ndjson ...]
    curl -s -H "Authorization: Bearer $TOKEN" "$URL/api/audit/events?after=0&limit=1000" | verify_chain.py -

Exit status 0 when the chain is intact, 1 when it is broken, 2 on bad input.
"""

import hashlib
import json
import re
import sys

ZERO = "0" * 64
HASH_SUFFIX = re.compile(r',"hash":"([0-9a-f]{64})"\}$')


def lines(paths):
    for path in paths:
        stream = sys.stdin if path == "-" else open(path, encoding="utf-8")
        with stream:
            for number, line in enumerate(stream, 1):
                line = line.rstrip("\r\n")
                if line.strip():
                    yield path, number, line


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if argv else 2

    events = []
    for path, number, line in lines(argv):
        match = HASH_SUFFIX.search(line)
        try:
            event = json.loads(line)
        except ValueError as e:
            print(f"{path}:{number}: not JSON: {e}", file=sys.stderr)
            return 2
        if event.get("schema") != "ens-audit/1" or not match:
            print(f"{path}:{number}: not an ens-audit/1 event with a hash", file=sys.stderr)
            return 2
        body = line[: match.start()] + "}"
        events.append((event["seq"], event, body, match.group(1), f"{path}:{number}"))

    if not events:
        print("No events.", file=sys.stderr)
        return 2

    # Exports are newest first; files and the pull API are oldest first.
    events.sort(key=lambda e: e[0])
    previous = None
    for seq, event, body, stored, where in events:
        if previous is not None:
            if seq == previous[0]:
                continue  # the same event twice (overlapping exports)
            if seq != previous[0] + 1:
                print(f"BROKEN: events {previous[0] + 1}..{seq - 1} are missing (before {where})")
                return 1
            if event["prevHash"] != previous[1]:
                print(f"BROKEN at seq {seq} ({where}): prevHash does not match the hash of seq {previous[0]}")
                return 1
        computed = hashlib.sha256((event["prevHash"] + "\n" + body).encode("utf-8")).hexdigest()
        if computed != stored:
            print(f"BROKEN at seq {seq} ({where}): the event was changed after it was recorded")
            return 1
        previous = (seq, stored)

    first = events[0][0]
    product = events[0][1].get("product")
    if first == 1 and events[0][1]["prevHash"] == ZERO:
        anchor = "from the first event"
    else:
        anchor = f"anchored at seq {first} (earlier events were pruned or not included)"
    print(f"INTACT: {product} seq {first}..{previous[0]}, {len(events)} events, {anchor}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
