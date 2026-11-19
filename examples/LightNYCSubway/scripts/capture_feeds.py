#!/usr/bin/env python3
"""Records every train in the system at one moment, as a test fixture.

    python3 examples/LightNYCSubway/scripts/capture_feeds.py weekday-afternoon
    python3 examples/LightNYCSubway/scripts/capture_feeds.py weekday-midday --from RECORDING/1794846058

Downloads the MTA's eight train feeds at once, so they describe the same minute, and writes
them to src/test/resources/snapshots/<name>/<feed>.pb.gz. The tests build the board of
every station from a snapshot, which is the only way to see what the tool says at a station
nobody has stood in with it.

With --from, nothing is downloaded: the eight feeds are read from a folder that holds one
download of each as <feed>.pb, which is how cut_replay.py's recordings are laid out.

Each file keeps the feed header and, byte for byte, every entity that holds a trip update
or a vehicle position: the trains, their stops to come, and where each was last seen.
Following a train reads all three. Its position says whether it is standing at a stop, how
far along its list it has got, and on the railway whether it is a train yet. Entities that
hold only an alert are dropped.

The first snapshot, weekday-afternoon, was taken before the tool read positions and holds
none. It is kept as it is, since tests are pinned to it. weekday-midday has them.

Nothing is re-encoded: what is kept is the exact bytes the MTA sent, extensions included, so
the reader is tested against the real thing. The files are gzipped with no name or time in
them, so the same download always gives the same file.

Name a snapshot for the kind of service it caught ("weekday-afternoon", "saturday-night"):
that is what a test using it is about. Nothing is written unless all eight feeds arrive,
and a snapshot already there is never written over, since tests are pinned to what it holds.
"""
import gzip
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "test", "resources", "snapshots")

SLUGS = ["gtfs", "gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw", "gtfs-l", "gtfs-si"]
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


def download(slug):
    request = urllib.request.Request(URL + slug, headers={"Accept": "application/x-protobuf"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def trim(data):
    """(kept bytes, feed timestamp, trip updates kept, positions kept, entities seen) for one feed."""
    kept = []
    timestamp = 0
    trips = 0
    positions = 0
    seen = 0
    for f, w, v, start, end in fields(data):
        if f == 1 and w == 2:
            kept.append(data[start:end])
            for f2, w2, v2, _, _ in fields(v):
                if f2 == 3 and w2 == 0:
                    timestamp = v2
        elif f == 2 and w == 2:
            seen += 1
            held = {f2 for f2, w2, _, _, _ in fields(v) if w2 == 2}
            if 3 in held or 4 in held:
                kept.append(data[start:end])
                trips += 3 in held
                positions += 4 in held
    if not timestamp:
        raise ValueError("no feed timestamp")
    return b"".join(kept), timestamp, trips, positions, seen


def recorded(source, slug):
    with open(os.path.join(source, slug + ".pb"), "rb") as f:
        return f.read()


def main():
    args = sys.argv[1:]
    source = ""
    if "--from" in args:
        at = args.index("--from")
        source = args[at + 1] if at + 1 < len(args) else ""
        del args[at:at + 2]
        if not os.path.isdir(source):
            sys.exit(__doc__)
    if len(args) != 1 or not args[0].replace("-", "").isalnum():
        sys.exit(__doc__)
    folder = os.path.join(OUT, args[0])
    if os.path.exists(folder):
        sys.exit(f"{os.path.normpath(folder)} is already there. Give this one another name.")

    if source:
        downloads = [recorded(source, slug) for slug in SLUGS]
    else:
        with ThreadPoolExecutor(len(SLUGS)) as pool:
            downloads = list(pool.map(download, SLUGS))
    trimmed = [trim(data) for data in downloads]

    os.makedirs(folder)
    total = 0
    for slug, data, (kept, timestamp, trips, positions, seen) in zip(SLUGS, downloads, trimmed):
        path = os.path.join(folder, slug + ".pb.gz")
        with open(path, "wb") as f:
            with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0) as z:
                z.write(kept)
        size = os.path.getsize(path)
        total += size
        print(f"{slug:10} built at {timestamp}: {trips:4} trips and {positions:4} positions of {seen:4} entities, "
              f"{len(data):7} bytes down, {len(kept):7} kept, {size:6} gzipped")
    built = [t[1] for t in trimmed]
    print(f"feeds built between {min(built)} and {max(built)} ({max(built) - min(built)} s apart), "
          f"{total} bytes -> {os.path.normpath(folder)}")


if __name__ == "__main__":
    main()
