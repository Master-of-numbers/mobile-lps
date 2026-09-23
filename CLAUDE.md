# CLAUDE.md

## Project

mobile-lps is a location proxy service for Android. It detects GNSS spoofing and, when spoofing is present, replaces the GNSS position with one fused from cell towers (LBS), Wi-Fi and IMU. The result is published through a mock location provider, so any existing navigation app (Waze, Google Maps, ...) keeps working unchanged. See `README.md` for the purpose and architecture diagram.

## Status

Initial skeleton: only the `:app` module with an empty Compose screen. The other modules below are planned, not created yet.

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
:core      Shared model types (measurements, positions, verdicts). Pure Kotlin/JVM.
:detector  Spoofing scoring. Pure Kotlin/JVM. Depends on :core.
:fusion    Sensor fusion (EKF), ENU <-> WGS84. Pure Kotlin/JVM. Depends on :core.
:sources   GNSS / Wi-Fi / cell / IMU -> Flow<Measurement>. Android. Depends on :core.
:provider  Mock location provider wrapper. Android. Depends on :core.
:replay    Session recording and playback. Depends on :core (recording side also on :sources).
:service   Foreground service, orchestration. Depends on :sources, :detector, :fusion, :provider, :core.
:app       Compose UI, permissions, settings. Depends on :service, :core.
```

Rules:

- `:core`, `:detector` and `:fusion` must stay pure JVM modules with no Android imports, so they can be tested and tuned on desktop against recorded sessions.
- Only `:sources` reads from the Android location, sensor, Wi-Fi and telephony APIs, and only `:provider` writes mock locations.
- `:app` does not talk to `:sources` or `:provider` directly; it goes through `:service`.

## Open decisions

- **Clean-signal passthrough.** While a test provider is registered for `gps`, the app no longer receives real GNSS fixes (raw `GnssMeasurement` / `GnssStatus` still arrive). Options: register mock providers only while spoofing is detected (simpler, possible jumps on switch), or keep mock always on and compute position from raw measurements (needs a PVT solver). Leaning towards the first option.
- LBS / Wi-Fi location database: to be decided by the owner.
- Distribution channel (Google Play is strict about mock location and background location apps).

## Conventions

- `README.md` and code are in English; the owner communicates in Ukrainian.
- Keep the README diagram and the "How it works" section consistent with each other.
