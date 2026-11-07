# Consuming n2k02AdbCoreLibrary

This repository follows the same **private GitHub Packages, reviewed-main-only** delivery model as `n2k02theme` and `n2k02updater`.

`dev.n2k02:adb-core:<version>` is **not available** until the first reviewed `main` publication succeeds. Never pin a guessed version. Consumers must keep their legacy transport operational until an actual package version and CI are verified.

## Wiring a host

The library deliberately **does not generate or replace** identities. Each app keeps its original private key and certificate in the same location and supplies a reader:

```kotlin
val wire = LibAdbWire(appContext, AdbIdentitySource { context ->
    val old = LegacyAdbIdentity.getOrCreate(context)
    AdbIdentity(old.privateKey, old.certificate)
}, deviceName = "VREC") // Supply the existing app name, not a new identity.

val store: AdbPreferenceStore = LegacySettingsAdapter(appContext)
val discovery = LocalAdbDiscovery(appContext)
val core = AdbCore(wire, store, discovery, hooks = object : AdbHostHooks {
    override suspend fun onVerifiedShell() {
        // App-specific actions; NEVER bypass UID-2000 verification.
    }
    override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
        // App-specific, explicit and reversible optional permission handling.
    }
})
AdbCoreRegistry.install(core) // in Application.onCreate
```

Do not destroy existing keys, clear a user-off flag, regrant permissions, or silently switch to TCP/5555 during migration. Use an adapter for the five existing preference values; the new default `SharedAdbPreferences` only applies to new clients without legacy settings.

## Canonical UI routes

```kotlin
// Home screen (stable root, never inside a lazy item's DisposableEffect):
AdbPageLifecycle(core)
LazyColumn { item { AdbHomeSetupPage(core) } }

// Settings > ADB connection (single header provided by host OR AdbPageScaffold):
AdbConnectionSettingsPage(
    adb = core,
    accessibilityEnabled = rememberAdbAccessibilityEnabled(),
    onOpenAccessibility = { /* open Android Accessibility settings */ }
)

// Settings > Diagnostics:
AdbDiagnosticsPage(core)
```

If the host already draws the mRadio-style single header, render only the content pages. Otherwise use `AdbPageScaffold`; **never nest headers**. The shared library owns the five settings rows, status, pairing fallback and diagnostic wording.

`SharedAdbPairingAccessibilityService` is declared in the library manifest. Android keeps it **off until the user explicitly enables it**. It reads Android Settings pairing-code screens only. Call `AdbCoreRegistry.uninstall(core)` on app-owned teardown after `core.quit()`. No codes are stored, logged or uploaded.

## Lifecycle and shutdown

- Call `core.acquireService()` when an app-owned recording/cleaning/monitoring service needs ADB recovery while its UI is hidden, and `releaseService()` when that work stops.
- Only an explicit user Retry clears a saved manual Stop.
- `core.startAfterBoot()` is called from the host's existing boot worker, which still owns platform scheduling and Android power restrictions.
- Call `core.quit()` from Quit and stop app-specific recordings, jobs and notifications separately.
- ADB TCP/5555 is a **device-wide listener**, so disabling it can disconnect other authorized apps. Show the explicit UI action; never change another app's saved preferences.
- An app using ADB for privileged commands must still verify UID 2000 for every newly established connection. The core's `AdbState.connected` is not a permanent grant to run arbitrary commands; use the verified `AdbWire` and app-level authorization policy.
- No automatic scanning or watchdog survives the final UI/service lifecycle owner unless a boot recovery task owns it.

## Migration and release sequence

1. Review library PR #1, run tests, lint, minified release build and on-device pairing/USB/TLS/TCP/5555 tests.
2. Merge the library only after validation, publish an immutable `dev.n2k02:adb-core` version on `main`.
3. In each existing experimental app PR (one PR per app), add authenticated GitHub Packages repository and pin **the actual published version**.
4. Replace the duplicated libadb transport, discovery, pairing-code reader and Compose page implementations with `LibAdbWire`, `LocalAdbDiscovery` and the library pages.
5. Migrate VRec, LocalAdb, MCP Android Logger, SEQ and SDCleaner individually. Preserve their recording/console/logging/audio/cleanup components as host hooks.
6. Keep a temporary experimental feature flag for rollback while validating on the Pixel 8 Pro and Android Auto where relevant.
7. Verify: power-saving/charging recovery, Wi-Fi off/on, port changes, boot restart, manual Stop/Quit, orphaned shell streams, timeout/cancel, reconnection, TCP opt-out, accessibility and actual UID verification. Confirm all app release/R8 builds before merging consumer PRs.

### Shared bug-fix rule

Fix bugs once in `n2k02AdbCoreLibrary`; add a library regression test; publish a **new pinned version**. Update the shared dependency in all five apps through their existing experimental PRs. Do not copy fixes back into five forked transports.
