#!/usr/bin/env python3
"""Records one train feed over time, and cuts a short replay out of a recording as a test fixture.

    python3 examples/LightNYCSubway/scripts/cut_replay.py record RECORDING gtfs-l 30
    python3 examples/LightNYCSubway/scripts/cut_replay.py cut RECORDING gtfs-l l-skip-stop 1794512599 17
    python3 examples/LightNYCSubway/scripts/cut_replay.py cut RECORDING gtfs 1-cut-short 1794513200 22 --trip 085350_1..N

A snapshot (capture_feeds.py) is every train at one moment. A replay is one feed, download
after download, which is the only way to test following a train: its id carried over, its
stops counted down, its position, and what the feed does to it on the way.

"record" downloads the feed once a minute, the given number of times, into
RECORDING/<unix time>/<feed>.pb, exactly as the MTA sent it.

"cut" takes that many downloads in a row from a recording, starting with the one taken at the
given unix time, and writes them to src/test/resources/replays/<name>.gz: one gzipped
file holding the downloads in order, each behind its length as four bytes, high byte first.
One download is so like the next that together they pack to half the size.

A whole feed is kept byte for byte. With --trip, only the feed header and the entities of the
one trip whose id starts that way are kept, also byte for byte: its stops and its position.
That is how a train from the numbered lines' feed, which is forty kilobytes a download, fits
in a fixture. Nothing is re-encoded, and the file is gzipped with no name or time in it, so
the same recording always gives the same file.

The first two replays in the repository were cut from one recording, made on Thursday 12
November 2026 from 2:39 PM, one download a minute for 82 minutes:

  l-skip-stop   The whole L feed, seventeen downloads from 2:43 PM. At 2:49 the train that
                set out from Canarsie as 086350_L..N was made to skip six stops, DeKalb Av to
                Graham Av, to close a gap. The same minutes hold a train held at Graham Av, a
                train finishing its run at 8 Av, and a timetable slot becoming a train.
  1-cut-short   One uptown 1 train, 085350_1..N15R, twenty-two downloads from 2:53 PM. Its id
                lost its ending, two of its stops were taken away, and twice it was missing
                from a download, on its way to Van Cortlandt Park-242 St.

The rest were cut from a recording made on Monday 16 November 2026 from 11:10 AM, one
download every half minute for 45 minutes, in folders laid out the same way. Each is one
train (--trip), and a thing a feed did to it that morning:

  shuttle-no-train   One Franklin Av shuttle trip, 067150_FS.S01R, eleven downloads from
                     11:10:58 AM. Due out at 11:11:30, it had no train until it was all but
                     at its last stop, and the feed took its stops off the list as their
                     times passed all the same.
  q-slot-then-train  The Q trip 068050_Q..N36R, 28 downloads from 11:19:28. No train until
                     11:26:51: the feed walked the empty trip four stops down its timetable,
                     then gave it a train listed from the top again.
  b-stop-given-back  The B 069500_B..N65R, 18 from 11:43:58. Kings Hwy came off its list
                     minutes before the train got there, and went back on it.
  j-every-stop       The J 065700_J..S13R, 27 from 11:14:28, Crescent St to Chauncey St:
                     "stopped" in every download, as every lettered-line train is.
  b-over-the-bridge  The B 062750_B..S65R, 22 from 11:19:58: five minutes on the Manhattan
                     Bridge, named as stopped at a stop it had left.
  n-named-far-ahead  The N 068800_N..N33R, 20 from 11:43:28: named as stopped four stops
                     further on than it was, the stops between listed a second apart.
  l-gone-mid-run     The L 064450_L..N, 46 from 11:10:58: in two downloads and then in none,
                     with fourteen stops still to make.
  5-held             The 5 064050_5..N46R, 42 from 11:32:28: thirteen minutes at 149
                     St-Hostos, its later times standing still the whole while.
  7-held-then-off    The 7 066600_7..S, 9 from 11:10:58: held at Mets-Willets Point with
                     seven stops listed for times already gone, then on its way.
  4-namesakes        064700_4..S01R and 064700_4..S06R (--trip 064700_4..S), 12 from
                     11:10:58: two trains whose ids differ only in the ending, the first of
                     them finishing its run.
  shuttle-starting   The 42 St shuttle 067700_GS.N04R, 17 from 11:10:58: a train waiting at
                     Grand Central with a time to leave and no position.
  j-held             The J 070000_J..N41R, 14 from 11:45:58: five minutes at Delancey
                     St-Essex St, the feed moving its time there on with the clock.
  4-at-its-terminal  The 4 071400_4..N06R, 2 from 11:34:28: standing at Crown Hts-Utica Av
                     twenty minutes before it was due out.
  g-given-back-skipping
                     The G 067100_G..N14R, 9 from 11:40:28: Bedford-Nostrand Avs off its
                     list, then first on it again with the four stops after it gone.
  m-train-named-late The M trip 069750_M..S24R, 20 from 11:36:58: no train until the feed
                     named one at Elmhurst Av, five stops into its run and a minute early.
  h-named-too-far-down
                     The Rockaway Park shuttle 070200_H..N21R, 21 from 11:41:28: first
                     given a train listed from Beach 90 St, and half a minute later from
                     Beach 105 St, two stops back.
  e-held             The E 064450_E..N66R, 28 from 11:26:58: twelve minutes at Sutphin Blvd,
                     the feed moving its times on with the clock.

A replay already there is never written over, since tests are pinned to what it holds.
"""
import gzip
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "test", "resources", "replays")

# The slash has to stay percent-encoded, as in Feeds.url.
URL = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2F"


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
        if p > end:
            raise ValueError("field runs past the end")
        yield field, wire, value, start, p


def trip_id(entity):
    """The trip id an entity is about, whether it holds a trip update (3) or a position (4)."""
    for f, w, v, _, _ in fields(entity):
        if f in (3, 4) and w == 2:
            for f2, w2, v2, _, _ in fields(v):
                if f2 == 1 and w2 == 2:
                    for f3, w3, v3, _, _ in fields(v2):
                        if f3 == 1 and w3 == 2:
                            return v3.decode("utf-8", "replace")
    return ""


def only(data, trip):
    """The feed header and the entities of the trip whose id starts with [trip], as they were sent."""
    kept = []
    for f, w, v, start, end in fields(data):
        if f == 1 and w == 2:
            kept.append(data[start:end])
        elif f == 2 and w == 2 and trip_id(v).startswith(trip):
            kept.append(data[start:end])
    return b"".join(kept)


def record(folder, slug, rounds):
    for n in range(rounds):
        began = time.time()
        request = urllib.request.Request(URL + slug, headers={"Accept": "application/x-protobuf"})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read()
        taken = os.path.join(folder, str(int(began)))
        os.makedirs(taken, exist_ok=True)
        with open(os.path.join(taken, slug + ".pb"), "wb") as f:
            f.write(data)
        print(f"{n + 1:3} of {rounds}: {len(data)} bytes")
        if n + 1 < rounds:
            time.sleep(max(0.0, 60 - (time.time() - began)))


def cut(folder, slug, name, first, count, trip):
    taken = sorted(d for d in os.listdir(folder) if d.isdigit() and os.path.exists(os.path.join(folder, d, slug + ".pb")))
    if first not in taken:
        sys.exit(f"No download of {slug} taken at {first} in {folder}.")
    rounds = taken[taken.index(first):][:count]
    if len(rounds) < count:
        sys.exit(f"Only {len(rounds)} downloads from {first} on, and {count} were asked for.")
    path = os.path.join(OUT, name + ".gz")
    if os.path.exists(path):
        sys.exit(f"{os.path.normpath(path)} is already there. Give this one another name.")
    os.makedirs(OUT, exist_ok=True)
    framed = []
    for at in rounds:
        with open(os.path.join(folder, at, slug + ".pb"), "rb") as f:
            data = f.read()
        kept = only(data, trip) if trip else data
        framed.append(len(kept).to_bytes(4, "big") + kept)
        print(f"{at}: {len(data):7} bytes down, {len(kept):6} kept")
    with open(path, "wb") as f:
        with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0) as z:
            z.write(b"".join(framed))
    print(f"{len(rounds)} downloads, {os.path.getsize(path)} bytes gzipped -> {os.path.normpath(path)}")


def main():
    args = sys.argv[1:]
    trip = ""
    if "--trip" in args:
        at = args.index("--trip")
        trip = args[at + 1]
        del args[at:at + 2]
    if len(args) == 4 and args[0] == "record" and args[3].isdigit():
        record(args[1], args[2], int(args[3]))
    elif len(args) == 6 and args[0] == "cut" and args[3].replace("-", "").isalnum() and args[5].isdigit():
        cut(args[1], args[2], args[3], args[4], int(args[5]), trip)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
