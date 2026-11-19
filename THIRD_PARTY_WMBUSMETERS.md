# wmbusmeters decoder source and licence

**wmbusmeters** (GNU GPL-3.0-or-later) is an independent open-source
project; this Android application is not its official Android release.

The full decoder bundled with Android v0.3 is a **precompiled WebAssembly
derivative** of the upstream wmbusmeters browser build. The archive contains
wmbusmeters' C++ meter drivers built into a wasm engine, with
`wm_main("--format=json <telegram> scan auto * NOKEY")` as an entry point.
The app retains the existing Java OMS parser as a safe fallback.

- Original source: https://github.com/wmbusmeters/wmbusmeters
- Browser / WASM build system:
  https://github.com/wmbusmeters/wmbusmeters/tree/master/browser
- Intermediate ES-module build, pinned revision:
  https://github.com/jasiek/web-rtl-wmbus/tree/cc5eb2089f10fe541c26e5bb28065b0d7aa28ab3
- Build instructions for the intermediate derivative:
  https://github.com/jasiek/web-rtl-wmbus/blob/cc5eb2089f10fe541c26e5bb28065b0d7aa28ab3/native/build-wmbusmeters.sh
- Browser source in intermediate derivative:
  https://github.com/jasiek/web-rtl-wmbus/tree/cc5eb2089f10fe541c26e5bb28065b0d7aa28ab3/src/wasm
- Local pinned binaries installed during CI by
  `scripts/install-wmbusmeters-wasm.sh` are verified against their Git blob IDs:
  `wmbusmeters.js`: `630c80e0c35d20b69bb7bc03431c721c75dc1856`
  `wmbusmeters.wasm`: `d34d98c7da388c939e0d1b81090de55ea716d30a`
- The top-level `LICENSE` in this repository reproduces the GPL v3 text.
  Retain this attribution and copyright notices in all redistributed versions.

The **exact upstream wmbusmeters source commit** used to construct the
intermediate prebuilt wasm was not explicitly pinned in the intermediate
repository's original setup script. Therefore this is a transparent
documented binary-origin import but **not yet a byte-for-byte reproducible
build**. For full reproducibility, replace prebuilt artifacts with WASM
built directly from a pinned upstream wmbusmeters commit (including the
Emscripten and libxml2 versions) before production distribution.

## Decode scope and limitations

- The compiled wmbusmeters engine supports whichever meter drivers were
  included in its pinned build. This is substantially broader than the
  Java-only OMS subset but does not guarantee every proprietary variant.
- `NOKEY` is passed on each telegram. **No user AES keys are currently
  supplied. Encrypted telegrams are not decrypted.** Do not claim they are
  valid measurements.
- Every raw packet is logged immediately with a telegram ID by the native
  Android reader. When the WASM engine finishes asynchronously, a separate
  `decode_update` JSONL record supplies the wmbusmeters JSON reading using
  the same telegram ID. The map and survey remain based only on CRC-valid
  packet header and RSSI/GPS observations, not the decrypted contents.
- WebAssembly runs in a headless local-asset-only WebView. It may be
  unavailable on older Android System WebView versions; the app should still
  record original packets and supported Java OMS values.
- The wasm binaries are fetched and bundled at Android build time.
  **No network request downloads or executes arbitrary decoder code during
  phone operation**. Only the map tiles and GitHub Release update check use
  network access.

## Licensing of the combined app

Because GPL-3.0-or-later wmbusmeters components are shipped inside the APK,
the combined Android app is distributed under GNU GPL v3 or later.
Any redistribution of the APK must also offer corresponding source code,
including modifications. Refer to the upstream authors for notices in their
respective source files. The third-party serial USB driver and osmdroid
retain their own compatible licence notices.
