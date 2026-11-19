# LightNYCSubway

The Light Phone III tool that LightOS shows as **Subway Times**. See the
[repository README](../../README.md) for what it does.

It is the same code as [BrightNYCSubway Lite](https://github.com/gi-os/BrightNYCSubwayLite)
(`tool/` there), with the package renamed from `com.gios.brightnycsubwaylite` to
`com.thelightphone.subway`. Changes are made there first and copied here.

## Build and test

```sh
./gradlew -DlightSdk.toolOnly=true :examples:LightNYCSubway:testDebugUnitTest :examples:LightNYCSubway:assembleDebug
```

`src/main/assets/stations.json` is generated from the MTA's station table and the transfers
file from its schedule:

```sh
python3 examples/LightNYCSubway/scripts/build_stations.py
```

The planner's expected trips are worked out a second time, in Python, from the rules in
words (`scripts/direct_trains.py`, `scripts/change_trains.py`).
