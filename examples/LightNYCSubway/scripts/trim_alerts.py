#!/usr/bin/env python3
"""Cuts a real MTA subway alerts feed down to a test fixture.

    curl -o /tmp/subway-alerts.pb \\
      'https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts'
    python3 examples/LightNYCSubway/scripts/trim_alerts.py /tmp/subway-alerts.pb

The live feed is about 560 KB and 235 alerts, nearly all of them planned work for days to
come. The fixture keeps the feed header and, byte for byte, the alerts that are in effect at
the moment the feed was built, plus a few that are not (so tests can show they are left out)
and any that name a shuttle or an express, whose route ids differ from what riders see.

Nothing is re-encoded: each kept alert is the exact bytes the MTA sent, descriptions, HTML
copies and MTA extensions included, so the reader is tested against the real thing.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "test", "resources", "subway-alerts.pb")

NOT_IN_EFFECT_KEPT = 8
ODD_ROUTES = {"GS", "FS", "H", "SI", "6X", "7X"}
ODD_KEPT_EACH = 2


def varint(b, p):
    shift = 0
    result = 0
    while True:
        c = b[p]
        p += 1
        result |= (c & 0x7F) << shift
        if not c & 0x80:
            return result, p
        shift += 7


def fields(b, p=0, end=None):
    """Yields (field, wire, value, start, end) for each field of a message."""
    end = len(b) if end is None else end
    while p < end:
        start = p
        tag, p = varint(b, p)
        field, wire = tag >> 3, tag & 7
        if wire == 0:
            value, p = varint(b, p)
        elif wire == 1:
            value, p = b[p:p + 8], p + 8
        elif wire == 5:
            value, p = b[p:p + 4], p + 4
        elif wire == 2:
            n, p = varint(b, p)
            value, p = b[p:p + n], p + n
        else:
            raise ValueError("wire type %d" % wire)
        yield field, wire, value, start, p


def describe(entity):
    """(periods, routes) of the alert inside one FeedEntity."""
    periods, routes = [], set()
    for f, w, v, _, _ in fields(entity):
        if f != 5 or w != 2:
            continue
        for f2, w2, v2, _, _ in fields(v):
            if f2 == 1 and w2 == 2:
                start = end = 0
                for f3, _, v3, _, _ in fields(v2):
                    if f3 == 1:
                        start = v3
                    elif f3 == 2:
                        end = v3
                periods.append((start, end))
            elif f2 == 5 and w2 == 2:
                for f3, w3, v3, _, _ in fields(v2):
                    if f3 == 2 and w3 == 2:
                        routes.add(v3.decode())
    return periods, routes


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    data = open(sys.argv[1], "rb").read()

    header = b""
    timestamp = 0
    entities = []
    for f, w, v, start, end in fields(data):
        if f == 1 and w == 2:
            header = data[start:end]
            for f2, _, v2, _, _ in fields(v):
                if f2 == 3:
                    timestamp = v2
        elif f == 2 and w == 2:
            entities.append((data[start:end], *describe(v)))
    if not timestamp:
        sys.exit("no feed timestamp: is this the alerts feed?")

    def in_effect(periods):
        return any(s <= timestamp and (e == 0 or timestamp < e) for s, e in periods)

    kept = []
    spare = NOT_IN_EFFECT_KEPT
    odd = {r: ODD_KEPT_EACH for r in ODD_ROUTES}
    counts = {"in effect": 0, "not in effect": 0, "odd route": 0}
    for raw, periods, routes in entities:
        if in_effect(periods):
            kept.append(raw)
            counts["in effect"] += 1
        elif any(odd.get(r, 0) > 0 for r in routes):
            for r in routes:
                if odd.get(r, 0) > 0:
                    odd[r] -= 1
            kept.append(raw)
            counts["odd route"] += 1
        elif spare > 0:
            spare -= 1
            kept.append(raw)
            counts["not in effect"] += 1

    with open(OUT, "wb") as f:
        f.write(header)
        for raw in kept:
            f.write(raw)
    size = len(header) + sum(len(r) for r in kept)
    print(f"feed built at {timestamp}: kept {len(kept)} of {len(entities)} alerts {counts}, "
          f"{size} bytes -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
