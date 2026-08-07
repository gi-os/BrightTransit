# BrightTransit

Live subway arrival times for New York City, on the Light Phone III. LightOS shows the
tool as **Subway Times**.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/brightmarket-qr.png" alt="Scan to open BrightMarket" width="180" />
</p>

Scan the code above, or visit
**[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html)**, to install
and keep this app updated through **BrightMarket** — no Play Store, no PC
required.

**Current release: v1.2.0** (tag `v1.2.0-build.21`). `versionCode` 3, tool id
`com.thelightphone.lightnycsubway`.

Part of the [gi-os Light App collection](#the-gi-os-light-app-collection).

## What it does

- Reads the MTA GTFS-realtime feeds directly. There is no middle server and no API key.
- Opens on your saved station and shows two columns, Uptown and Downtown, soonest train
  first.
- Draws the real route bullets, so a `6` reads as a `6` and not as a letter in a box.
- Merges platforms by MTA Complex ID, so a transfer station appears once instead of four
  times.
- Searches the whole system from a bundled catalog of 445 stations, held in
  `assets/stations.json`.
- Sorts the station list by distance under **Near me**, and filters it by borough.
- Stores the chosen station on the device. The tool asks for `INTERNET`, and for coarse and
  fine location so that **Near me** works. It asks for nothing else.

## The wheel

Turning the phone's wheel scrolls the board, the station page and the search results. A
search for "st" returns most of the system, so that last one is the reason it is here.

Nothing else has to be installed for it. Light patched
`/system/usr/keylayout/Generic.kl`, so a notch is an ordinary key event that lands in
whichever app holds focus, and this tool reads it itself. No companion service, no
permission, no root.

A light-sdk tool has no activity of its own to override, but the SDK hands every key that
is not Back or Home to the screen on top of the back stack before it forwards anything to
LightOS. So each of the three screens claims the two wheel scancodes — LightOS relabels
them `WHEEL_CCW` and `WHEEL_CW` — in `onKeyDown` and `onKeyUp`, and the notch reaches the
scroll view through a flow rather than through the screen reaching into its own UI.
Claiming both halves of the notch is what stops the turn carrying on to the server as a
brightness change. Notches arrive faster than a frame, so each one becomes a debt that a
share of gets paid off per frame, and the first notch after a pause is held back, because
the wheel sits under a thumb.

The wheel *click* and the camera button are left alone. Those belong to
[BrightControl](https://github.com/gi-os/BrightControl), which is optional and owns them
across the phone: hold the wheel in and turn for brightness, tap it for the flashlight,
press the camera button for the camera. Each is rebindable, tap and hold separately, to any
installed app, and apps that handle no wheel keys of their own get brightness or a
synthetic-swipe scroll out of it. The long version is in
[LightNews](https://github.com/gi-os/LightNews#the-wheel-and-the-camera-button).

Installing it changes nothing in here, deliberately. The tool ships as
`com.thelightphone.lightnycsubway`, and BrightControl treats every `com.thelightphone.` id
as hands-off, because a key an SDK tool does not claim is forwarded to LightOS, which
already does the sensible thing with it — a bare turn becomes brightness. A service in the
middle would be taking that away rather than adding anything. The trade is that
BrightControl's button bindings do not reach this tool: the click and the camera button keep
whatever LightOS does with them. Scrolling is unaffected either way, which is the part that
matters here.

If you want BrightControl for the rest of the phone:

```bash
# Optional: BrightControl, for brightness, the flashlight and the camera button
adb install -r LightControl-v1.0.x.apk

# The key service. NOTE: this setting is a list, and this command REPLACES it —
# if you also run LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# Brightness, and the level readout + opening apps from the service
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

The current build is at
[BrightControl/releases/latest](https://github.com/gi-os/BrightControl/releases/latest).

## How it stays fast

The station catalog parses once, off the main thread, and then caches process-wide. An
earlier build parsed it during composition and the phone showed a black screen at
launch. `StationCatalog.load` now takes an asset reader and is safe to call from any
thread, more than once.

Each station maps to the set of MTA feeds that serve it. `ArrivalsRepository` requests
those feeds at the same time, drops trains that already left, removes duplicates, and
sorts by time. A feed that fails returns an empty list rather than an error, so one bad
feed does not empty the board.

## Layout of this repository

This is the [light-sdk](https://github.com/lightphone/light-sdk) tree with one tool
added. Upstream code stays where upstream put it, which keeps a rebase cheap.

| Path | What it is |
| --- | --- |
| `examples/BrightTransit/` | The tool |
| `examples/BrightTransit/src/main/kotlin/.../ArrivalsRepository.kt` | Feed fetch and GTFS-realtime decode |
| `examples/BrightTransit/src/main/kotlin/.../StationCatalog.kt` | Cached station parse |
| `examples/BrightTransit/src/main/kotlin/.../hw/` | Wheel keys and the notch-to-scroller bus |
| `examples/BrightTransit/src/main/kotlin/.../HomeScreen.kt` | Uptown and Downtown columns |
| `examples/BrightTransit/src/main/kotlin/.../StationScreen.kt` | Station search and selection |
| `examples/BrightTransit/src/main/assets/stations.json` | Station and stop catalog |
| `examples/BrightTransit/lighttool.toml` | Tool identity, version and permissions |
| `sdk/`, `plugin/`, `builder/`, `docs/`, other `examples/` | Upstream light-sdk |

## Build

```sh
./gradlew :examples:LightNYCSubway:assembleDebug
```

You need JDK 17 and the Android SDK. The SDK client libraries come from GitHub Packages,
so set a token with package-read access first:

```sh
export GITHUB_ACTOR=<your-username>
export GITHUB_TOKEN=<token-with-read:packages>
```

You can put `gpr.user` and `gpr.key` in `local.properties` instead. Never commit either
one.

`.github/workflows/build-apk.yml` builds the tool on each push to `main` that touches
`examples/BrightTransit/`, and attaches the APK to a GitHub Release.

## Run it

To run against the LightOS emulator, set `serverPackage = "com.thelightphone.sdk.emulator"`
in `examples/BrightTransit/lighttool.toml`. Set it back to `com.lightos` before a device
build. The SDK [system-app guide](docs/system_app/README.md) covers the one-time emulator
setup.

## Screenshots

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/starred.png" width="260" alt="Starred stations with live uptown and downtown arrivals"><br>
      <sub>Starred stations, live arrivals</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/stations.png" width="260" alt="Station browser filtered by borough"><br>
      <sub>Browse by borough</sub>
    </td>
  </tr>
</table>

Taken on a Light Phone III against the live MTA feeds.

## Origin and credits

- **[lightphone/light-sdk](https://github.com/lightphone/light-sdk)** is the base of
  this repository. The whole tree is a fork. The Light Phone team wrote the SDK client,
  the Gradle plugin, the lint rules, the builder and the emulator, and released all of
  it under MIT before the platform was even public. Thank you.
- **[MTA](https://api.mta.info/)** publishes the GTFS-realtime feeds and the station
  data as open data. The station catalog derives from it.
- **[Ktor](https://github.com/ktorio/ktor)** and
  **[OkHttp](https://github.com/square/okhttp)** move the bytes. A hand-rolled decoder
  reads the protobuf, so the tool stays inside the SDK dependency allowlist.
- The Light Phone, LightOS and Light Phone III are names of The Light Phone. This is an
  unofficial community tool.

This repo set the pattern the rest of the collection reuses. Fork light-sdk, write the
tool into a module the SDK reserves for it, leave upstream alone, and let a GitHub
Actions workflow build the APK. [BrightNews](https://github.com/gi-os/BrightNews) and
[BrightSolitaire](https://github.com/gi-os/BrightSolitaire) both follow it.

## The gi-os Light App collection

Twelve tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [BrightPasses](https://github.com/gi-os/BrightPasses) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [BrightNews](https://github.com/gi-os/BrightNews) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| **BrightTransit** (this repo) | Live MTA subway arrivals | light-sdk fork |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [FogLight](https://github.com/gi-os/FogLight) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [BrightNonogram](https://github.com/gi-os/BrightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| [BrightSolitaire](https://github.com/gi-os/BrightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |
| [BrightLibrary](https://github.com/gi-os/BrightLibrary) | RSVP speed reader for EPUB and MOBI | Fork of [fluffyspace/FastRead](https://github.com/fluffyspace/FastRead) |
| [BrightTip](https://github.com/gi-os/BrightTip) | Tip calculator, plus a receipt splitter that reads the line items | Plain Android |
| [BrightNoise](https://github.com/gi-os/BrightNoise) | Twelve synthesized sounds, a two-layer mixer and a sleep timer | Plain Android |
| [LightPods](https://github.com/gi-os/LightPods) | AirPods battery, in-ear and lid status | Plain Android, ports [LibrePods](https://github.com/kavishdevar/librepods) |

The Light Phone does not sponsor or endorse any of these. Licences vary per repo.

## Version history

CI (`.github/workflows/build-apk.yml`) builds and releases automatically on every push to `main`
that touches `examples/BrightTransit/` or the workflow file itself. The tag is
`v<versionName>-build.<run number>`, with `versionName` read from `lighttool.toml` — a push that
only edits docs elsewhere in the repo does not cut a release.

- **v1.2.0** (build 21, 2026-07-29) — Hardware wheel scrolls the board, the station list and search
  results, with a bump guard against stray brushes (`ArmedNotches`).
- **v1.1.0** (builds 10–19, 2026-07-28) — Local tab: browse stations by borough, or by GPS/IP-based
  distance under **Near me**; favorite lines and stations; real subway route bullets; adaptive
  launcher icon; SDK icons on the bottom bar in place of text.
- **v1.0.0** (builds 3–8, 2026-07-27) — Initial release: live GTFS-realtime arrivals over direct MTA
  feeds, stations merged by MTA Complex ID, Uptown/Downtown columns, renamed from `nyc-subway` to
  BrightTransit / "Subway Times".

## License

MIT, the same as upstream light-sdk. See [LICENSE](LICENSE).
