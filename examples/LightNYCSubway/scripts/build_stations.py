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
written as "" so the tool names that direction after where the train is actually going.
"""
import csv
import json
import os
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "Stations.csv")
OUT = os.path.join(HERE, "..", "src", "main", "assets", "stations.json")

# Says nothing a rider can use; the tool falls back to the train's destination.
VAGUE = {"northbound", "southbound", "inbound", "outbound", "westbound", "eastbound", "last stop", ""}

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
}

ROUTE_ORDER ="1 2 3 4 5 6 7 A C E B D F M G J Z L N Q R W S SIR".split()


def route_key(r):
    return ROUTE_ORDER.index(r) if r in ROUTE_ORDER else len(ROUTE_ORDER)


def label(text):
    text = text.strip()
    return "" if text.lower() in VAGUE else text


def main():
    with open(SRC, newline="") as f:
        rows = list(csv.DictReader(f))

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
            platforms.append({
                "stop": r["GTFS Stop ID"].strip(),
                "name": r["Stop Name"].strip(),
                "routes": sorted(r["Daytime Routes"].split(), key=route_key),
                "n": label(r["North Direction Label"]),
                "s": label(r["South Direction Label"]),
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
        stations.append(entry)

    stations.sort(key=lambda s: (s["name"], s["id"]))
    with open(OUT, "w") as f:
        json.dump({"stations": stations}, f, separators=(",", ":"), ensure_ascii=False)
        f.write("\n")
    print(f"{len(stations)} stations, {sum(len(s['platforms']) for s in stations)} platforms -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
