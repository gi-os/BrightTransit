#!/usr/bin/env python3
"""Builds src/main/assets/stations.json from the MTA "Stations" open-data table.

    python3 examples/LightNYCSubway/scripts/build_stations.py

Source: https://data.ny.gov/Transportation/MTA-Subway-Stations/39hk-dx4f (saved here as
Stations.csv). One row there is one platform group (a GTFS parent stop). Rows that share a
Complex ID are one station to a rider, so they become one entry with several platforms.

Each platform keeps its own name, lines and direction labels, because those differ inside a
complex: at 14 St-Union Sq the L runs "West Side / Brooklyn" while the 4 5 6 run
"Uptown / Downtown".

Labels the MTA table fills with a compass word ("Northbound", "Outbound", "Last Stop") are
written as "" so the tool names that direction after where each train is actually going.
So are the few in BLANKED, which say something, but not something true of every train.
"Inbound" is the one compass word that names a place: outside Manhattan it is written
"Manhattan".

Two things are written for search alone: the other names a station goes by (NICKNAMES)
and the avenue its lines run under (AVENUES).

Each station also keeps how long the MTA allows to change trains inside it, platform to
platform, from transfers.txt in its GTFS schedule (saved here as transfers.txt, from
https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip). It is written as "changes": a list of
{from stop, to stop, seconds}, the same stop twice for a change on one platform. A change on
one platform takes three minutes, the time the file gives most platforms, unless the file
says otherwise, so only the platforms that differ are written. Between two platforms, a pair
the file does not list is not a change the tool will offer. Pairs that join two
stations (Livonia Av and Junius St; Lexington Av/63 St and 59 St) are left out: a change is
only ever made inside one station.
"""
import csv
import json
import os
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "Stations.csv")
TRANSFERS = os.path.join(HERE, "transfers.txt")

# What the transfers file gives most platforms for a change on the platform itself. The
# tool takes this for any platform the file leaves out.
SAME_PLATFORM = 180
OUT = os.path.join(HERE, "..", "src", "main", "assets", "stations.json")

# Says nothing a rider can use; the tool works the direction out from where the train goes.
VAGUE = {"northbound", "southbound", "inbound", "outbound", "westbound", "eastbound", "last stop", ""}

# The table writes these two out in full on its signs and short in its station names.
# Spelled the short way a sign matches the name of the stop it points at.
SPELLING = {"Court Square": "Court Sq", "Times Square": "Times Sq"}

# Signs left out although the table has a real word, keyed by (GTFS Stop ID, "N" or "S").
# With no sign the tool names the direction train by train, from where each one goes.
BLANKED = {
    # Hoyt-Schermerhorn Sts. "Queens" is true of the A. The C from the same side ends in
    # Brooklyn, and so does the G, whose trains to Queens leave from the other side.
    ("A42", "S"),
    # New Utrecht Av. The N's platform says "Coney Island"; the D's, in the same station
    # and bound for the same place, has only a compass word. Kept, the station would give
    # one way out two names, with a train to Coney Island under each.
    ("N04", "S"),
}

# What people call a station when the sign says something else. Search only; never shown.
# Keyed by Complex ID.
NICKNAMES = {
    "97": ["Myrtle-Broadway"],
    "448": ["Citi Field", "US Open"],
    "617": ["Atlantic Terminal", "Pacific St"],
    "164": ["Madison Square Garden", "MSG"],
    "318": ["Madison Square Garden", "MSG"],
    "471": ["Javits Center"],
    "635": ["Staten Island Ferry"],
    "621": ["East New York"],
    "610": ["Grand Central Terminal"],
    # Names the MTA has replaced, which riders still say.
    "441": ["Central Park North"],
    "603": ["149 St-Grand Concourse"],
    "323": ["Christopher St-Sheridan Sq"],
}

# Which rows of the table an avenue holds for. CBD is the table's word for south of 60 St.
def in_manhattan(row):
    return row["Borough"].strip() == "M"


def below_60_st(row):
    return in_manhattan(row) and row["CBD"].strip() == "true"


def everywhere(row):
    return True


# The avenue a trunk line runs under, keyed by the table's "Line" column. Search only;
# never shown. A station's name gives its street and seldom its avenue: nothing else says
# that the 1 2 3 at 34 St-Penn Station are on 7 Av, and "34th and 7th" has to find them.
#
# A trunk keeps its name long after it has left its avenue, so each avenue comes with the
# rows it holds for. The F is "6th Av - Culver" all the way to Coney Island. The 1 leaves
# 7 Av for Broadway at Times Sq, and the 7 Av of Harlem is four blocks from it; south of
# 60 St is the nearest the table has to where that happens.
AVENUES = {
    "Broadway - 7Av": ("7 Av", below_60_st),
    "6th Av - Culver": ("6 Av", in_manhattan),
    "8th Av - Fulton St": ("8 Av", in_manhattan),
    "Lexington Av": ("Lexington Av", everywhere),
    "Second Av": ("2 Av", everywhere),
    "4th Av": ("4 Av", everywhere),
    "Jerome Av": ("Jerome Av", everywhere),
    "Liberty Av": ("Liberty Av", everywhere),
    "Myrtle Av": ("Myrtle Av", everywhere),
}

ROUTE_ORDER ="1 2 3 4 5 6 7 A C E B D F M G J Z L N Q R W S SIR".split()


def route_key(r):
    return ROUTE_ORDER.index(r) if r in ROUTE_ORDER else len(ROUTE_ORDER)


def label(text):
    text = text.strip()
    return "" if text.lower() in VAGUE else SPELLING.get(text, text)


def labels(row):
    """The (north, south) direction names for one platform.

    The L is the one line riders name differently from the table: its two directions are
    Manhattan and Brooklyn to everyone who rides it. The table has "Outbound" for most of
    its stops outside Manhattan and "Canarsie" for the last five, so the same direction
    changed name partway down the line, and changed again whenever trains turned back early.
    The end of the line keeps its blank: nothing leaves Canarsie going that way.

    "Inbound" is the table's word for the side that leads into Manhattan: the M's own
    stretch of line and the branch to Rockaway Park. Left blank, the Rockaway Park shuttle,
    which turns back at Broad Channel, would be headed "Queens" at stations already in
    Queens, and listed apart from the A to Manhattan that leaves from the same track.
    Essex St is "Inbound" too, but it is in Manhattan, where that is no direction.
    """
    stop = row["GTFS Stop ID"].strip()
    n = "" if (stop, "N") in BLANKED else label(row["North Direction Label"])
    s = "" if (stop, "S") in BLANKED else label(row["South Direction Label"])
    if row["Daytime Routes"].split() == ["L"] and n == "Manhattan" and row["South Direction Label"].strip().lower() != "last stop":
        s = "Brooklyn"
    if row["Borough"].strip() != "M":
        if row["North Direction Label"].strip().lower() == "inbound":
            n = "Manhattan"
        if row["South Direction Label"].strip().lower() == "inbound":
            s = "Manhattan"
    return n, s


def avenue(row):
    """The avenue this platform's line runs under here, or None."""
    name, holds = AVENUES.get(row["Line"].strip(), (None, None))
    return name if name and holds(row) else None


def transfers():
    """{(from stop, to stop): seconds} from the GTFS transfers file."""
    with open(TRANSFERS, newline="") as f:
        return {(r["from_stop_id"].strip(), r["to_stop_id"].strip()): int(r["min_transfer_time"]) for r in csv.DictReader(f)}


def main():
    with open(SRC, newline="") as f:
        rows = list(csv.DictReader(f))
    times = transfers()

    complexes = OrderedDict()
    seen = set()
    for r in rows:
        stop = r["GTFS Stop ID"].strip()
        if stop in seen:  # the table repeats a few rows
            continue
        seen.add(stop)
        complexes.setdefault(r["Complex ID"].strip(), []).append(r)

    stations = []
    for cid, group in complexes.items():
        # The name most platforms use; on a tie, the one with the most lines.
        names = OrderedDict()
        for r in group:
            names[r["Stop Name"].strip()] = names.get(r["Stop Name"].strip(), 0) + len(r["Daytime Routes"].split())
        name = max(names, key=lambda n: names[n])
        aka = [n for n in names if n != name] + NICKNAMES.get(cid, [])

        routes = sorted({x for r in group for x in r["Daytime Routes"].split()}, key=route_key)
        platforms = []
        for r in group:
            north, south = labels(r)
            platforms.append({
                "stop": r["GTFS Stop ID"].strip(),
                "name": r["Stop Name"].strip(),
                "routes": sorted(r["Daytime Routes"].split(), key=route_key),
                "n": north,
                "s": south,
            })
        entry = {
            "id": cid,
            "name": name,
            "boro": group[0]["Borough"].strip(),
            "lat": round(sum(float(x["GTFS Latitude"]) for x in group) / len(group), 5),
            "lon": round(sum(float(x["GTFS Longitude"]) for x in group) / len(group), 5),
            "routes": routes,
            "platforms": platforms,
        }
        if aka:
            entry["aka"] = aka
        avenues = list(OrderedDict.fromkeys(a for a in map(avenue, group) if a))
        if avenues:
            entry["avenues"] = avenues
        stops = [p["stop"] for p in platforms]
        # A change on one platform is three minutes unless the file says otherwise, so only
        # the platforms that differ from that are written.
        changes = [
            {"from": a, "to": b, "seconds": times[(a, b)]}
            for a in stops for b in stops
            if (a, b) in times and (a != b or times[(a, b)] != SAME_PLATFORM)
        ]
        if changes:
            entry["changes"] = changes
        stations.append(entry)

    stations.sort(key=lambda s: (s["name"], s["id"]))
    with open(OUT, "w") as f:
        json.dump({"stations": stations}, f, separators=(",", ":"), ensure_ascii=False)
        f.write("\n")
    print(f"{len(stations)} stations, {sum(len(s['platforms']) for s in stations)} platforms -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
