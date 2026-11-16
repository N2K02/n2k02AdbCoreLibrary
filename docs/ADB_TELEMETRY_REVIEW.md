# ADB transport telemetry — first implementation review

Scope: experimental PR #9, `LibAdbWire` transport metrics. This is diagnostic instrumentation, not a replacement for the existing ADB protocol implementation.

## What is measured

- `PAIR`: elapsed time and outcome of the pairing attempt.
- `CONNECT_TLS` / `CONNECT_TCP`: elapsed time and outcome of connection attempts; reuse of an already healthy connection is counted as a successful call, not a new handshake.
- `SHELL`: elapsed time and outcome of the entire `run()` call, including mutex contention and any stream setup. It is **not** exclusively device execution time.
- A failure includes errors and cancellation, but the telemetry does not distinguish those causes.
- The enum includes `DISCONNECT` for future work; it is not yet instrumented. Do not display a disconnection latency value until an actual asynchronous teardown lifecycle is measured.

Percentiles use the nearest-rank method on the bounded in-memory window, not over the lifetime of the device. The window contains at most 60 samples by default, and failed operations contribute to latency percentiles. Report sample count along with p50/p95.

## Safety

- No pairing code, private key, shell command, stdout/stderr, IP/port or certificate content is stored.
- No network telemetry transmission or persistent storage is introduced.
- The existing local-only host, stable key identity, UID 2000 checks and Stop/Quit behavior remain unchanged.

## Regression tests

- Bounded FIFO eviction; no negative durations.
- Stage-specific failures and nearest-rank percentile boundaries.
- Reset/snapshot isolation and concurrent record/read access.
- Existing AdbCore and transport tests remain applicable.

## Remaining release gates

- Confirm GitHub Actions unit tests, lint and release assembly on the **latest** PR commit.
- Validate pairing, TCP 5555 fallback, mDNS discovery, repeated reconnection, shell timeout and Quit on a physical device.
- Check library Android API/minSdk compatibility and ensure no caller treats these aggregate metrics as scrcpy video decoder latency.
- Do not publish a Maven version or change consumer dependencies before testing and reviewing main merge.
