# mGBA libretro bridge patch

This directory contains the native, loopback-only bridge used by the Android
companion. Copy `archipelago_bridge.c` and `archipelago_bridge.h` into
`mgba/src/platform/libretro/`, then apply
`0001-add-archipelago-loopback-bridge.patch` followed by
`0002-defer-audio-rate-notification.patch`.
The mGBA top-level CMake build already globs every C file in that directory.

The second patch mirrors the libretro mGBA fork's safe audio-rate handling.
The bridge source also replaces a stale client with the newest queued loopback
connection when polling resumes. This is required on Android because RetroArch
stops calling `retro_run()` while its activity is backgrounded.
Upstream mGBA can call `RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO` from inside
`retro_load_game()`, which re-enters RetroArch's Android video initialization
and can crash its Vulkan driver with `native_window_api_connect(): -22`.

The bridge binds `127.0.0.1:43056` only. It never exposes emulator memory to
the LAN and does not perform network I/O with the Archipelago server. The
companion owns the server connection and translates its game-specific actions
to the loopback protocol.

`APBridgePoll()` is called only from `retro_run()`. This is essential: it
keeps reads, guards, and writes on mGBA's emulation thread, with writes applied
before the next emulated frame.

The current protocol is deliberately small and intended for a first vertical
slice. Version 2 supports `HELLO`, `PING`, `ROM_SHA1`, `READ`, `GUARD`,
`WRITE`, and `MESSAGE`. `MESSAGE` queues a UTF-8 string for RetroArch's OSD;
the core consumes it from `retro_run()` via `RETRO_ENVIRONMENT_SET_MESSAGE`.
All integers are big-endian, the header is 20 bytes, and payloads are capped at
4096 bytes (OSD text is capped at 511 bytes). `READ` carries its requested byte count as a four-byte payload;
the response carries the requested memory bytes. See the companion's
`BridgeProtocol.kt` for the matching wire definitions.

When building the Android shared library with NDK r27 or older, pass both
`-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384` through
`CMAKE_SHARED_LINKER_FLAGS`. Without these flags the resulting core has 4 KiB
ELF load-segment alignment and cannot be loaded reliably on 16 KiB-page Android
devices. NDK r28 and newer produces 16 KiB-compatible libraries by default.
