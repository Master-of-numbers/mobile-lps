# CLAUDE.md

## Project

mobile-lps is a location proxy service for Android. It detects GNSS spoofing and, when spoofing is present, replaces the GNSS position with one fused from cell towers (LBS), Wi-Fi and IMU. The result is published through a mock location provider, so any existing navigation app (Waze, Google Maps, ...) keeps working unchanged. See `README.md` for the purpose and architecture diagram.

## Status

Working pipeline: raw GNSS → own PVT solution → spoofing detector; cell towers + Wi-Fi → BeaconDB position; the trusted position is published through mock providers. Runs on a real device (owner-tested). IMU, sensor fusion (`:fusion`) and `:replay` are not implemented yet.

## Tech stack

Decided and in place:

- Kotlin, native Android (no cross-platform layer); Jetpack Compose for UI.
- Gradle (Kotlin DSL only, no Groovy/Maven), version catalog `gradle/libs.versions.toml`, convention plugins in `build-logic/`.
- SDK levels in the catalog: minSdk 29 (Android 10, required by the owner), compile/target 37. JVM bytecode 17, JDK 21 to run the build.
- APIs newer than 29 need a version check with a fallback (Android Lint `NewApi` fails the build otherwise):
    - `GnssMeasurementRequest.setFullTracking` (31): on 29–30 ask the user to enable "Force full GNSS measurements" in Developer options.
    - `Location.isMock` (31): use `isFromMockProvider` on 29–30.
    - `LocationManager.FUSED_PROVIDER` (31): on 29–30 mock only `gps` and `network`.
- JUnit 4 + Truth for tests; ktlint (Spotless), detekt, Android Lint with warnings as errors; GitHub Actions CI; Dependabot.

Proposed, not yet confirmed:

- Coroutines + Flow for all data streams.
- EJML for fusion math (EKF/UKF).
- Room for local storage, Koin or manual DI, MapLibre for the debug map.

## Build and tooling

- Tool versions are pinned: JDK and pre-commit in `.mise.toml`, Gradle by the wrapper (with checksum), everything else in the version catalog. Never hardcode a version in a module build file.
- New modules apply a convention plugin instead of configuring AGP/Kotlin directly: `mobilelps.jvm.library` (pure Kotlin), `mobilelps.android.library`, `mobilelps.android.application`, plus `mobilelps.android.compose` for Compose.
- AGP 9 uses built-in Kotlin: do not apply `org.jetbrains.kotlin.android`.
- Before finishing a change, run `./gradlew spotlessApply detekt lint testDebugUnitTest assembleDebug`.
- pre-commit hooks: generic checks, Prettier (Markdown/YAML/JSON), taplo (TOML), gitleaks and Spotless on commit; detekt on push.

## Layers

Dependencies point downward only: a layer may depend on layers below it, never above.

1. **Presentation** — Compose UI: status, spoofing indicator, settings, onboarding (mock location app, Wi-Fi scan throttling), debug map comparing raw GNSS with the fused position.
2. **Orchestration** — foreground service (`foregroundServiceType="location"`): lifecycle, wires sources → detector → fusion → provider.
3. **Adapters (Android I/O)**
    - _Input:_ location sources — GNSS (fixes, `GnssMeasurement`, `GnssStatus`), Wi-Fi scans / RTT, cell info, IMU and rotation vector sensors.
    - _Output:_ mock location provider (`addTestProvider` / `setTestProviderLocation` for `gps`, `network`, `fused`).
4. **Domain (pure Kotlin/JVM)** — spoofing detection and sensor fusion. No Android dependencies.
5. **Model (pure Kotlin/JVM)** — shared data types: measurements, positions, detector verdicts.

Replay tooling sits beside the stack: it records adapter output to files and feeds recorded sessions into the domain layer.

## Modules

```
:core      Shared model types (positions, cells, raw GNSS), WGS84 geodesy, minimal HTTP client. Pure Kotlin/JVM.
:gnss      RINEX nav parser, broadcast orbits, pseudoranges from raw measurements, WLS PVT, ephemeris download. Pure Kotlin/JVM.
:lbs       Cells + Wi-Fi -> position via BeaconDB (NetworkLocator), access point filter, caching. Pure Kotlin/JVM.
:detector  Spoofing indicators, scoring and hysteresis state machine. Pure Kotlin/JVM.
:fusion    (planned) Sensor fusion (EKF). Pure Kotlin/JVM.
:sources   Raw GNSS measurements, cell scans (all SIMs) and Wi-Fi scans -> Flow. Android.
:provider  Mock location provider wrapper (gps, network, fused). Android.
:replay    (planned) Session recording and playback.
:service   LpsEngine and NetworkPositioning (pure Kotlin orchestration), ephemeris cache, foreground service. Android.
:app       Compose status UI, permissions. Depends on :service only.
```

Rules:

- `:core`, `:gnss`, `:lbs`, `:detector` (and later `:fusion`) must stay pure JVM modules with no Android imports, so they can be tested and tuned on desktop.
- Only `:sources` reads from the Android location, sensor, Wi-Fi and telephony APIs, and only `:provider` writes mock locations.
- `:app` does not talk to `:sources` or `:provider` directly; it goes through `:service` (`Lps.start/stop/status`).
- `LpsEngine` takes flows and interfaces, not Android types, so it is covered by JVM tests with virtual time.

## Pipeline

- **Mock providers are always registered** (owner's decision). In the clean state the published position is the app's own PVT solution from raw measurements, not the platform GNSS fix.
- PVT: GPS L1 C/A + Galileo E1 only, one clock bias per constellation, Klobuchar (fixed default coefficients, BKG files carry none) + simple troposphere model, up to 3 outlier exclusions.
- Ephemerides: BKG `BRDC00WRD_S` merged RINEX 3 files (updated every 15 min), refreshed every 30 min, cached on disk for up to 6 h.
- Published fix time is the system clock, never GNSS time (a spoofer controls GNSS time).
- Arbiter: GNSS (≤ 2 s old) while the detector state is CLEAN/SUSPECT. When GNSS is distrusted it is still published if it lies within the LBS accuracy circle (distance ≤ LBS accuracy + GNSS accuracy) and is more precise than LBS (owner's decision). Otherwise the LBS fix (≤ 120 s old) re-stamped every second, otherwise nothing.

## Debugging on a device

Debug builds (`./gradlew installDebug`) expose live diagnostics over adb; release builds do not.

- Full snapshot (detector, every GNSS signal with state/C/N0/residual, cells, Wi-Fi with filter verdict, lookups):
  `adb shell dumpsys activity service dev.mobilelps/dev.mobilelps.service.LpsService`
- Timeline, one line every 5 s and on every detector state change: `adb logcat -s LPS`
- Debug builds also run a Wi-Fi-only BeaconDB lookup once a minute ("wifi-only lookup") to show whether the access points are known.
- The service must be running (Start pressed in the app). Output contains positions, cell IDs and BSSIDs: do not paste it into public places.

## Open decisions

- **Feasibility risk, to verify on a device:** with a test provider registered for `gps`, the platform may stop the GNSS engine and raw measurements may stop arriving. The status screen shows the age of the last measurement event for exactly this check.
- LBS uses the online BeaconDB API (cell IDs and BSSIDs leave the device, SSIDs never). An offline database may be added later behind `NetworkLocator`.
- LTE/NR neighbour cells report only PCI and cannot be looked up; with one SIM in LTE usually a single cell is identified.
- Distribution channel (Google Play is strict about mock location and background location apps).

## Conventions

- `README.md` and code are in English; the owner communicates in Ukrainian.
- Keep the README diagram and the "How it works" section consistent with each other.
