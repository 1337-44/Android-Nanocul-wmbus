#!/usr/bin/env bash
set -euo pipefail
# Prebuilt GPLv3 wmbusmeters WASM generated using upstream Emscripten code.
# Pinned source tree: https://github.com/jasiek/web-rtl-wmbus/tree/cc5eb2089f10fe541c26e5bb28065b0d7aa28ab3
# Original engine: https://github.com/wmbusmeters/wmbusmeters
# See THIRD_PARTY_WMBUSMETERS.md for source/attribution.
COMMIT=cc5eb2089f10fe541c26e5bb28065b0d7aa28ab3
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/app/src/main/assets/wmbus"
mkdir -p "$DIR"
check() {
    local name="$1" expected="$2"
    local file="$DIR/$name"
    curl --fail --location --retry 3 --max-time 120 --silent --show-error \
        "https://raw.githubusercontent.com/jasiek/web-rtl-wmbus/$COMMIT/src/wasm/$name" -o "$file"
    local actual
    actual="$(git hash-object "$file")"
    if [ "$actual" != "$expected" ]; then
        echo "ERROR: Git blob SHA mismatch for $name: $actual" >&2
        rm -f "$file"
        exit 1
    fi
    echo "Verified $name (Git blob SHA $actual)"
}
check wmbusmeters.js 630c80e0c35d20b69bb7bc03431c721c75dc1856
check wmbusmeters.wasm d34d98c7da388c939e0d1b81090de55ea716d30a
