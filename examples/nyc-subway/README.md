# Subway — a Light Phone III tool

Real-time NYC subway arrivals for the Light Phone III, built on the
[light-sdk](https://github.com/lightphone/light-sdk). Search the ~470 stations,
star the ones you ride, and see the next trains per line the moment you open it.

## Screens

- **Home** — your starred stations, each with the next few trains (line +
  direction arrow + minutes). Pull data with *Refresh*; it also refreshes every
  time you open the tool.
- **Search** — type a station name on the Light keyboard, tap a result.
- **Station** — arrivals split by direction (using the MTA's own uptown/downtown
  labels), with a *Star* / *Unstar* button.

## How it works

- **Data**: MTA GTFS-realtime feeds (`api-endpoint.mta.info`), **no API key
  required**. The eight subway feeds are grouped by line; a station queries only
  the feeds its routes need, in parallel, then filters `stop_time_update`s whose
  `stop_id` matches the station (`635N` / `635S` for stop `635`).
- **No heavy dependencies**: the SDK restricts third-party libraries, so
  `GtfsRealtime.kt` is a ~150-line, dependency-free protobuf wire decoder that
  reads only the five fields we need (route id, stop id, arrival time). It was
  validated field-for-field against a live feed.
- **Stations**: `assets/stations.json` is generated from the MTA "Stations"
  open-data table (GTFS stop id, name, borough, daytime routes, direction
  labels). Loaded once via `lightContext.readAsset`.
- **Starred stations**: persisted in the SDK `DataStore` as a `|`-joined id list.

### ⚠️ Feed URL gotcha

The gateway treats `nyct/<feed>` as a **single** path parameter, so the internal
slash must be percent-encoded or every request 403s:

```
✅ https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2Fgtfs-ace
❌ https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct/gtfs-ace   → 403
```

`ArrivalsRepository` builds the request with Ktor's `encodedPath` to keep the
`%2F` intact (OkHttp preserves it). See `MtaFeeds.encodedPath`.

## Build & run

1. Drop this folder into the SDK repo at `examples/nyc-subway`.
2. Register it in the root `settings.gradle.kts`:

   ```kotlin
   include(":examples:nyc-subway")
   project(":examples:nyc-subway").projectDir = file("examples/nyc-subway")
   ```

3. Add your GitHub Packages token (`gpr.user` / `gpr.key` in `local.properties`)
   as the SDK README describes, then:

   ```
   ./gradlew :examples:nyc-subway:assembleDebug
   ```

4. Test in the **LightOS emulator** (1080×1240, API 34, no Google Play). To point
   at the emulator instead of a real phone, flip `serverPackage` in
   `lighttool.toml` to `com.thelightphone.sdk.emulator`.

## File map

| File | Role |
|------|------|
| `SubwayModels.kt` | `Station`, `Arrival`, route→feed mapping, encoded paths |
| `GtfsRealtime.kt` | dependency-free protobuf decoder |
| `ArrivalsRepository.kt` | fetch + decode + filter feeds for a station |
| `StationStore.kt` | station catalog + starred-id persistence + search |
| `HomeScreen.kt` | starred stations board (`@InitialScreen`) |
| `SearchScreen.kt` | station search |
| `StationScreen.kt` | per-station arrivals + star toggle |
| `TextInputScreen.kt` | reusable Light keyboard editor |
| `SubwayUi.kt` | shared composables (route badge, arrival row) |
| `assets/stations.json` | 496 stations |

## Notes / next steps

- Times are computed from the feed's arrival timestamp; a train `0 min` away is
  "Now".
- Express variants (`6X`, `7X`) and shuttles (`GS`) show with their feed labels.
- Service-alert feeds and walking-distance sorting are not wired up yet — easy
  follow-ons if you want them.
