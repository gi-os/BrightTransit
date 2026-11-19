#!/usr/bin/env python3
"""Works out the direct trains between two stations from a recorded snapshot, a second way.

    python3 examples/LightNYCSubway/scripts/direct_trains.py weekday-afternoon "96 St:1" "Times Sq-42 St"
    python3 examples/LightNYCSubway/scripts/direct_trains.py weekday-afternoon 602 610

The tool's planner (data/Planner.kt) says which trains run from one station to another. Its
tests state, pair by pair, the trains it should find. This is where those lists come from:
the same snapshot (capture_feeds.py) read with nothing but a protobuf field walker, and the
planner's rules followed from their wording below. No code is shared with the tool, so a
list that both arrive at is right for a reason other than being written down twice.

A station is given by its id in stations.json, or by its name. A name that several stations
share takes one of its lines after a colon ("86 St:R"). Each train is printed as the tests
write it: its line in the feed, its trip, when it leaves, when it gets there ("--" for no
time), how many stops the ride is, and "slot" for a trip no train has yet. A star marks the
trains that are offered. Times are New York's.

The rules:

 1. A trip's stops are the ones it lists that are stations in stations.json.
 2. In the numbered lines' feed, the L's and the railway's, a trip with no train that was
    to set out before now is left out. Its id opens with when it sets out, in hundredths
    of a minute past New York's midnight. In the lettered lines' feeds such a trip is
    kept: there it is nearly always a train the feed has yet to own up to.
 3. A trip is boarded at the first of its stops, at one of the first station's platforms,
    that is not more than 30 seconds past.
 4. It is left at the first stop after that at one of the second station's platforms. A
    trip with no such stop does not go there, or is going the other way.
 5. On the railway (gtfs-si) the time of a trip's last stop is not believed.
 6. Where two stops from boarding to getting off are listed under 20 seconds apart, the
    feed has no times for that stretch, and the arrival is unknown.
 7. Two trips of one line listed at one platform for the same second and ending at the
    same stop are one train listed twice. The one that has a train is kept, or the first.
 8. Only the feeds either station is looked for in are read.

Three more rules go by where the feed last saw a train, and only bite on a snapshot that
kept the positions (weekday-midday does, weekday-afternoon does not):

 9. On the railway a trip whose position has no status is not a train yet.
10. A train seen stopped at the second stop it lists, in a sighting not dated after its
    feed, has left the first: that stop is not one of its stops any more. A sighting
    further down the list than that is not believed.
11. A train seen stopped at its first stop more than two minutes past its time there is
    being held, and puts no other train out.

Offered: a train is left out when another leaves sooner and gets there sooner, or, having no
arrival time itself, when another leaves sooner. A trip with no train, or a train being
held, never puts a moving train out. What is left is listed soonest there first and no
more than four. A train with no arrival time goes just ahead of the next train of its own
line from its own platform that has one, and last where there is none.
"""
import gzip
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SNAPSHOTS = os.path.join(HERE, "..", "src", "test", "resources", "snapshots")
STATIONS = os.path.join(HERE, "..", "src", "main", "assets", "stations.json")

SLUGS = ["gtfs", "gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw", "gtfs-l", "gtfs-si"]
BULLET = {"GS": "S", "FS": "S", "H": "S", "SI": "SIR", "SS": "SIR"}


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


def fields(b):
    """Yields (field, wire, value) for each field of a message."""
    p, end = 0, len(b)
    while p < end:
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
        yield field, wire, value


def only(b, field, wire, default):
    for f, w, v in fields(b):
        if f == field and w == wire:
            return v
    return default


def trips(data):
    """The feed's time, each trip as (id, line, has a train, [(stop, time)]), and each position by trip as (stop, status, time)."""
    built = 0
    out = []
    seen = {}
    for f, w, v in fields(data):
        if f == 1 and w == 2:
            built = only(v, 3, 0, 0)
        elif f == 2 and w == 2:
            vehicle = only(v, 4, 2, None)
            if vehicle is not None:
                tid = only(only(vehicle, 1, 2, b""), 1, 2, b"").decode()
                stop = only(vehicle, 7, 2, b"").decode()
                if tid and stop:
                    seen[tid] = (stop, only(vehicle, 4, 0, None), only(vehicle, 5, 0, 0))
            update = only(v, 3, 2, None)
            if update is None:
                continue
            tid, line, train, stops = "", "", True, []
            for f2, w2, v2 in fields(update):
                if f2 == 1 and w2 == 2:
                    tid = only(v2, 1, 2, b"").decode()
                    line = only(v2, 5, 2, b"").decode()
                    extension = only(v2, 1001, 2, None)
                    # A feed with none of the MTA's extensions cannot say, and its trains are taken as running.
                    train = extension is None or only(extension, 2, 0, 0) != 0
                elif f2 == 2 and w2 == 2:
                    stop = only(v2, 4, 2, b"").decode()
                    arrival = only(only(v2, 2, 2, b""), 2, 0, 0)
                    departure = only(only(v2, 3, 2, b""), 2, 0, 0)
                    when = arrival if arrival > 0 else departure
                    if stop and when > 0:
                        stops.append((stop, when))
            if line and stops:
                out.append((tid, line, train, stops))
    return built, out, seen


def feeds_of(route, stop):
    r = route.upper()
    if r in "1234567":
        return ["gtfs"]
    if r in ("A", "C", "E"):
        return ["gtfs-ace"]
    if r in ("B", "D", "F", "M"):
        return ["gtfs-bdfm"]
    if r in ("J", "Z"):
        return ["gtfs-jz"]
    if r in ("N", "Q", "R", "W"):
        return ["gtfs-nqrw"]
    if r == "S":
        return ["gtfs"] if stop[0].isdigit() else ["gtfs-ace", "gtfs-bdfm"]
    return {"G": ["gtfs-g"], "L": ["gtfs-l"], "SIR": ["gtfs-si"]}.get(r, [])


def looked_for_in(station):
    slugs = {slug for p in station["platforms"] for r in p["routes"] for slug in feeds_of(r, p["stop"])}
    if slugs & {"gtfs-ace", "gtfs-bdfm"}:
        slugs |= {"gtfs-ace", "gtfs-bdfm"}
    return slugs


def sets_out(tid, listed, now):
    """When a trip sets out by its id, on the day of [now], or by its first listed stop when the id has no time."""
    head = tid.split("_")[0]
    if "_" not in tid or not head.isdigit():
        return listed[0][1]
    day = time.localtime(now)
    midnight = time.mktime((day.tm_year, day.tm_mon, day.tm_mday, 0, 0, 0, 0, 0, -1))
    return int(midnight) + int(head) * 6 // 10


KEEPS_AN_EMPTY_TRIP = ("gtfs", "gtfs-l", "gtfs-si")
STOPPED = 1


def direct(feeds, platforms, origin, destination, now):
    starts = {p["stop"] for p in origin["platforms"]}
    ends = {p["stop"] for p in destination["platforms"]}
    found = []
    for slug in SLUGS:
        if slug not in looked_for_in(origin) | looked_for_in(destination):
            continue
        built, listing, positions = feeds[slug]
        for tid, line, train, listed in listing:
            at = positions.get(tid)
            if slug == "gtfs-si" and at is not None and at[1] is None:
                train = False
            if not train and slug in KEEPS_AN_EMPTY_TRIP and sets_out(tid, listed, now) < now:
                continue
            stops = [(stop, when) for stop, when in listed if stop[:-1] in platforms]
            if slug == "gtfs-si" and stops:
                stops[-1] = (stops[-1][0], 0)
            held = False
            if train and at is not None and at[1] == STOPPED and at[2] <= built:
                if len(stops) > 1 and stops[1][0] == at[0]:
                    stops = stops[1:]
                held = bool(stops) and stops[0][0] == at[0] and 0 < stops[0][1] <= now - 120 and not (stops[0][0][:-1] in starts)
            on = next((i for i, (stop, when) in enumerate(stops) if stop[:-1] in starts and when >= now - 30), None)
            if on is None:
                continue
            off = next((i for i in range(on + 1, len(stops)) if stops[i][0][:-1] in ends), None)
            if off is None:
                continue
            timed = all(stops[i + 1][1] - stops[i][1] >= 20 for i in range(on, off))
            found.append(dict(slug=slug, id=tid, line=line, train=train, held=held, leaves=stops[on][1], at=stops[on][0],
                              arrives=stops[off][1] if timed else 0, stops=off - on, last=stops[-1][0]))
    seen, kept = set(), []
    for t in sorted(found, key=lambda t: not t["train"]):
        twin = (BULLET.get(t["line"], t["line"]), t["at"], t["leaves"], t["last"])
        if twin not in seen:
            seen.add(twin)
            kept.append(t)
    return sorted(kept, key=soonest_there)


def soonest_there(t):
    return (t["arrives"] if t["arrives"] > 0 else float("inf"), t["leaves"], t["id"])


def offered(found, after=0):
    def sure(t):
        return t["train"] and not t["held"]

    def beats(other, t):
        if sure(t) and not sure(other):
            return False
        return other["leaves"] < t["leaves"] and (t["arrives"] == 0 or 0 < other["arrives"] < t["arrives"])

    def there(t):
        if t["arrives"] > 0:
            return t["arrives"]
        behind = [u["arrives"] for u in found if u["arrives"] > 0 and u["line"] == t["line"] and u["at"] == t["at"] and u["leaves"] > t["leaves"]]
        return min(behind) if behind else float("inf")

    found = [t for t in found if t["leaves"] > after]
    return sorted([t for t in found if not any(beats(u, t) for u in found)], key=lambda t: (there(t), t["leaves"], t["id"]))[:4]


def clock(when):
    return time.strftime("%H:%M:%S", time.localtime(when)) if when else "--"


def said(t):
    return "%s %s %s %s %d%s" % (t["line"], t["id"], clock(t["leaves"]), clock(t["arrives"]), t["stops"], "" if t["train"] else " slot")


def pick(stations, wanted):
    name, _, line = wanted.partition(":")
    found = [s for s in stations if s["id"] == wanted or (s["name"] == name and (not line or line in s["routes"]))]
    if len(found) != 1:
        sys.exit("%r is %d stations: %s" % (wanted, len(found), ", ".join("%s (%s)" % (s["name"], " ".join(s["routes"])) for s in found)))
    return found[0]


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__.split("\n\n")[1])
    os.environ["TZ"] = "America/New_York"
    time.tzset()
    with open(STATIONS) as f:
        stations = json.load(f)["stations"]
    platforms = {p["stop"] for s in stations for p in s["platforms"]}
    feeds = {}
    for slug in SLUGS:
        with gzip.open(os.path.join(SNAPSHOTS, sys.argv[1], slug + ".pb.gz")) as f:
            feeds[slug] = trips(f.read())
    # The snapshot's own time is when the last of its feeds was built, as in the tests.
    now = max(feed[0] for feed in feeds.values())
    origin, destination = pick(stations, sys.argv[2]), pick(stations, sys.argv[3])
    found = direct(feeds, platforms, origin, destination, now)
    chosen = [t["id"] for t in offered(found)]
    print("%s (%s) to %s (%s) at %s: %d trains" % (
        origin["name"], " ".join(origin["routes"]), destination["name"], " ".join(destination["routes"]), clock(now), len(found)))
    for t in found:
        print("%s %s" % ("*" if t["id"] in chosen else " ", said(t)))


if __name__ == "__main__":
    main()
