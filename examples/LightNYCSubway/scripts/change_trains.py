#!/usr/bin/env python3
"""Works out the ways between two stations with one change from a recorded snapshot, a second way.

    python3 examples/LightNYCSubway/scripts/change_trains.py weekday-midday "Jay St-MetroTech" "Grand Central-42 St" [--all] [--header]

The tool's planner (data/Planner.kt) also offers ways there with one change of train. Its
tests state, pair by pair, the ways it should offer. This is where those lists come from: the
same snapshot read with direct_trains.py's protobuf walker and its rules for one train, and
the rules for a change below followed from their wording. It was written without reading the
planner, so a list both arrive at is right for a reason other than being written down twice.

Each way is printed as: the first train's line in the feed, its trip, when it leaves, the
station changed at, when it gets there | the second train's line, trip, when it leaves, when
it gets to the second station, the MTA's time to change, and the lines that run from the
second train's platform there. --all also prints every way found before the last rule.

The rules, for a way from station A to station B:

C1. Only the feeds A or B is looked for in are read (direct rule 8).
C2. The first train is one direct_trains.py would board at A (rules 1-3, 7, 9, 10) that has
    a train, and is not being held on its way to A (rule 11).
C3. It may be left at any later stop that is a station, up to but not including the first
    stop at one of B's platforms or back at one of A's. Every stop from boarding to the one
    it is left at is at least 20 seconds after the one before (rule 6); the first stop where
    that fails ends the list.
C4. The second train is one direct_trains.py would board at that station X going to B (rules
    1-7, 9, 10), with an arrival at B, that does not stop at one of A's platforms on the way,
    and is not the first train's own trip. Only feeds holding a trip that stops at B are read.
C5. The change from the platform the first train is left at to the one the second leaves from
    must be in X's "changes" in stations.json (from transfers.txt; see build_stations.py), or
    be on one platform, which is 180 seconds when not listed. The second train leaves at
    least that long and 60 seconds more after the first gets there.
C6. For each first train and station X, the second train that gets there first is taken, the
    sooner to leave on a tie. Its lines are those of the second trains at X that pass C4 and
    C5, leave from the same platform and get there no more than five minutes after it: its
    own first, then in the order of X's lines.
C7. Offered: where there are direct trains (not left more than 30 seconds ago) and none has
    an arrival time, nothing is. Otherwise a way whose first train left more than 30 seconds
    ago is dropped, and so is one that does not get there five minutes or more before every
    direct train that has an arrival time. Then a way is dropped when another gets there five minutes or more before
    it, or leaves no later and gets there no later, sooner in one; on a tie, the one with
    more time to change is kept, then the one with fewer stops, then the one whose trains
    and station sort first. What is left goes soonest there first, at most two.
"""
import gzip
import importlib.util
import json
import os
import sys
import time

DT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "direct_trains.py")
spec = importlib.util.spec_from_file_location("direct_trains", DT_PATH)
dt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dt)

SPARE = 60
DEFAULT_WALK = 180


def board(feeds, platforms, slugs, origin, destination, now):
    """Direct rules 1-3, 7, 9, 10 with `origin` as the first station. With a destination (rule 4), the trips that
    reach it, with `off` and the arrival; without, every trip boarded at the origin. Each is a dict with the
    trip's adjusted stops, `on` and `held`."""
    starts = {p["stop"] for p in origin["platforms"]}
    ends = {p["stop"] for p in destination["platforms"]} if destination else set()
    found = []
    for slug in dt.SLUGS:
        if slug not in slugs:
            continue
        built, listing, positions = feeds[slug]
        for tid, line, train, listed in listing:
            at = positions.get(tid)
            if slug == "gtfs-si" and at is not None and at[1] is None:
                train = False
            if not train and slug in dt.KEEPS_AN_EMPTY_TRIP and dt.sets_out(tid, listed, now) < now:
                continue
            stops = [(s, w) for s, w in listed if s[:-1] in platforms]
            if slug == "gtfs-si" and stops:
                stops[-1] = (stops[-1][0], 0)
            held = False
            if train and at is not None and at[1] == dt.STOPPED and at[2] <= built:
                if len(stops) > 1 and stops[1][0] == at[0]:
                    stops = stops[1:]
                # Rule 11 as direct_trains.py words it: stopped at the first stop it lists, short of the
                # station it is boarded at, more than two minutes past its time there.
                held = bool(stops) and stops[0][0] == at[0] and 0 < stops[0][1] <= now - 120 and stops[0][0][:-1] not in starts
            on = next((i for i, (s, w) in enumerate(stops) if s[:-1] in starts and w >= now - 30), None)
            if on is None:
                continue
            t = dict(slug=slug, id=tid, line=line, train=train, held=held, stops=stops, on=on, at=stops[on][0],
                     leaves=stops[on][1], last=stops[-1][0], has_position=(at is not None and at[1] is not None))
            if destination is not None:
                off = next((i for i in range(on + 1, len(stops)) if stops[i][0][:-1] in ends), None)
                if off is None:
                    continue
                timed = all(stops[i + 1][1] - stops[i][1] >= 20 for i in range(on, off))
                t["off"] = off
                t["arrives"] = stops[off][1] if timed else 0
            found.append(t)
    seen, kept = set(), []
    for t in sorted(found, key=lambda t: not t["train"]):
        twin = (dt.BULLET.get(t["line"], t["line"]), t["at"], t["leaves"], t["last"])
        if twin not in seen:
            seen.add(twin)
            kept.append(t)
    return kept


def plain(route):
    r = dt.BULLET.get(route, route)
    return r[:-1] if len(r) > 1 and r.endswith("X") else r


def main():
    args = [a for a in sys.argv[1:] if a not in ("--all", "--header")]
    show_all = "--all" in sys.argv[1:]
    if len(args) != 3:
        sys.exit(__doc__)
    os.environ["TZ"] = "America/New_York"
    time.tzset()
    with open(dt.STATIONS) as f:
        stations = json.load(f)["stations"]
    platforms = {p["stop"] for s in stations for p in s["platforms"]}
    owner = {p["stop"]: s for s in stations for p in s["platforms"]}
    feeds = {}
    for slug in dt.SLUGS:
        with gzip.open(os.path.join(dt.SNAPSHOTS, args[0], slug + ".pb.gz")) as f:
            feeds[slug] = dt.trips(f.read())
    now = max(feed[0] for feed in feeds.values())
    A, B = dt.pick(stations, args[1]), dt.pick(stations, args[2])
    if "--header" in sys.argv[1:]:
        print("## %s %s -> %s %s" % (A["id"], A["name"], B["id"], B["name"]))
    a_stops = {p["stop"] for p in A["platforms"]}
    b_stops = {p["stop"] for p in B["platforms"]}

    # C1: first trains
    slugs1 = dt.looked_for_in(A) | dt.looked_for_in(B)
    firsts = []
    for t in board(feeds, platforms, slugs1, A, None, now):
        if not t["train"] or t["held"]:
            continue
        if t["slug"] == "gtfs-si" and not t["has_position"]:
            continue
        firsts.append(t)

    # C4: feeds holding a trip stopping at one of B's platforms
    slugs2 = {slug for slug in dt.SLUGS if any(s[:-1] in b_stops for _, _, _, listed in feeds[slug][1] for s, _ in listed)}
    seconds_at = {}

    def seconds_for(X):
        if X["id"] not in seconds_at:
            out = []
            for u in board(feeds, platforms, slugs2, X, B, now):
                if u["arrives"] <= 0:
                    continue
                if any(u["stops"][i][0][:-1] in a_stops for i in range(u["on"], u["off"] + 1)):
                    continue
                out.append(u)
            seconds_at[X["id"]] = out
        return seconds_at[X["id"]]

    def walk_of(X, p1, p2):
        for c in X.get("changes", []):
            if c["from"] == p1 and c["to"] == p2:
                return c["seconds"]
        return DEFAULT_WALK if p1 == p2 else None

    def order_key(X, route):
        routes = [plain(r) for r in X["routes"]]
        r = plain(route)
        return routes.index(r) if r in routes else len(routes)

    cands = []
    for f in firsts:
        stops = f["stops"]
        reach = {}  # X id -> list of (idx)
        for i in range(f["on"] + 1, len(stops)):
            s, w = stops[i]
            if s[:-1] in b_stops or s[:-1] in a_stops:
                break
            if w - stops[i - 1][1] < 20:
                break
            reach.setdefault(owner[s[:-1]]["id"], (owner[s[:-1]], []))[1].append(i)
        for xid, (X, idxs) in reach.items():
            best = None
            for i in idxs:
                p1, a1 = stops[i][0][:-1], stops[i][1]
                for u in seconds_for(X):
                    if u["slug"] == f["slug"] and u["id"] == f["id"]:
                        continue
                    p2 = u["at"][:-1]
                    walk = walk_of(X, p1, p2)
                    if walk is None or u["leaves"] - a1 < walk + SPARE:
                        continue
                    k = (u["arrives"], u["leaves"])
                    if best is None or k < best[0]:
                        best = (k, i, u, walk)
            if best is None:
                continue
            _, i, u, walk = best
            p2 = u["at"][:-1]
            p1 = stops[i][0][:-1]
            others = []
            for v in seconds_for(X):
                if v["slug"] == f["slug"] and v["id"] == f["id"]:
                    continue
                if v["at"][:-1] != p2 or v["arrives"] > u["arrives"] + 300:
                    continue
                w = walk_of(X, p1, p2)
                if w is None or v["leaves"] - stops[i][1] < w + SPARE:
                    continue
                others.append(v)
            rest = sorted((r for r in {v["line"] for v in others} if r != u["line"]), key=lambda r: (order_key(X, r), r))
            names = [u["line"]] + rest
            cands.append(dict(f=f, u=u, X=X, a1=stops[i][1], walk=walk, lines=names,
                              nstops=(i - f["on"]) + (u["off"] - u["on"]),
                              key="((%s, %s), %s, (%s, %s))" % (f["slug"], f["id"], X["id"], u["slug"], u["id"])))

    def say(c):
        f, u = c["f"], c["u"]
        return "%s %s %s %s %s | %s %s %s %s walk=%d lines=%s" % (
            f["line"], f["id"], dt.clock(f["leaves"]), c["X"]["name"], dt.clock(c["a1"]),
            u["line"], u["id"], dt.clock(u["leaves"]), dt.clock(u["arrives"]), c["walk"], ",".join(c["lines"]))

    def a2(c): return c["u"]["arrives"]
    def lv(c): return c["f"]["leaves"]
    def sortkey(c): return (a2(c), lv(c), c["key"])

    # C7
    ways = [c for c in cands if lv(c) >= now - 30]
    directs = [t for t in dt.direct(feeds, platforms, A, B, now) if t["leaves"] >= now - 30]
    arrivals = [t["arrives"] for t in directs if t["arrives"] > 0]
    if directs and not arrivals:
        ways = []
    if arrivals:
        soonest = min(arrivals)
        ways = [c for c in ways if a2(c) + 300 <= soonest]

    def beats(o, c):
        if lv(o) == lv(c) and a2(o) == a2(c):
            tie = lambda x: (-(x["u"]["leaves"] - x["a1"]), x["nstops"], x["key"])
            return tie(o) < tie(c)
        return lv(o) <= lv(c) and a2(o) <= a2(c) and (lv(o) < lv(c) or a2(o) < a2(c))

    def much_sooner(o, c):
        return a2(o) + 300 <= a2(c)

    left = [c for c in ways if not any(beats(o, c) or much_sooner(o, c) for o in ways if o is not c)]
    for c in sorted(left, key=sortkey)[:2]:
        print(say(c))
    if show_all:
        print("candidates:")
        for c in sorted(cands, key=sortkey):
            print(say(c))


if __name__ == "__main__":
    main()
