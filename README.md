# n2k02AdbCoreLibrary

Shared Android localhost ADB connection, recovery and Jetpack Compose UI for N2K02 apps.

**Maven:** `dev.n2k02:adb-core:<pinned-version>` — published only after reviewed `main` merge.

## Design rules
- One library owns TLS pairing, localhost ADB transport, UID 2000 verification, recovery policy, state and UI.
- Never silently promote an unauthorized or unverified connection; never connect to non-loopback hosts.
- Keep each app's existing private key/certificate identity through an `AdbIdentitySource` adapter, avoiding unexpected re-pairing.
- The library does not acquire app permissions or run privileged app-specific actions without explicit app callbacks.
- App-specific actions (VRec capture daemon, LocalAdb Shizuku server, MCP logging, SDCleaner cache trim and SEQ DSP) stay in app modules.
- One status model and one set of Home setup, Connection settings and Diagnostics pages across apps.
- Persist manual Stop, and stop discovery, watchdogs and sockets when a feature is shut down.
- Fail closed on missing or mismatched `id -u`; do not report stale verification as healthy.
- A published version is immutable and pinned by each consuming app.

## Publication
Publishing follows n2k02theme and n2k02updater: Android library + Maven Publish + GitHub Packages, with publication from reviewed `main` only. PR CI runs library tests, lint and release assembly without publishing.

**Status:** Shared library extraction in progress. Consumer apps must not remove their working transports until compatibility tests pass.

## Shared API

- `LibAdbWire` – one libadb transport, serialized shell, independent command deadline, preserved host identity.
- `AdbCore` – validated UID-2000 state machine, TCP opt-in, saved Stop, service-owned watchdog, bounded reconnect.
- `LocalAdbDiscovery` – device-local NSD for pairing and connection ports.
- `SharedAdbPairingAccessibilityService` – user-enabled, read-only Android Settings pairing-code extraction.
- `AdbHomeSetupPage`, `AdbConnectionSettingsPage`, `AdbDiagnosticsPage`, `AdbPageScaffold` – consistent Material 3 pages.

See [MIGRATION.md](MIGRATION.md) for integration and rollout. This draft is **not yet used by the five apps**.
