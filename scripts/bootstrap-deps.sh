#!/bin/bash
# bootstrap-deps.sh — Download and verify pinned dependencies for native build.
# Run this once after cloning the repository (or when dependencies are missing).
#
# Usage:
#   ./scripts/bootstrap-deps.sh
#
# Dependencies:
#   curl, tar, shasum (or sha256sum)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEP_DIR="$ROOT_DIR/dep"

# ---------------------------------------------------------------------------
# Boost 1.86.0 (headers-only, used by libtorrent)
# License: Boost Software License 1.0 (BSD-style)
# Source:  https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz
# ---------------------------------------------------------------------------
BOOST_VERSION="1.86.0"
BOOST_URL="https://archives.boost.io/release/${BOOST_VERSION}/source/boost_${BOOST_VERSION//./_}.tar.gz"
BOOST_SHA256="2575e74ffc3ef1cd0babac2c1ee8bdb5782a0ee672b1912da40e5b4b591ca01f"

if [ -d "$DEP_DIR/boost" ]; then
    echo "Boost $BOOST_VERSION already present in dep/. Skipping."
else
    echo "Downloading Boost $BOOST_VERSION..."
    mkdir -p "$DEP_DIR"
    TMPFILE=$(mktemp /tmp/boost_*.tar.gz)
    curl -sL "$BOOST_URL" -o "$TMPFILE"

    ACTUAL_SHA=$(shasum -a 256 "$TMPFILE" 2>/dev/null | awk '{print $1}' || sha256sum "$TMPFILE" | awk '{print $1}')
    if [ "$ACTUAL_SHA" != "$BOOST_SHA256" ]; then
        echo "ERROR: Boost SHA-256 mismatch!"
        echo "  Expected: $BOOST_SHA256"
        echo "  Actual:   $ACTUAL_SHA"
        rm -f "$TMPFILE"
        exit 1
    fi
    echo "SHA-256 verified: $ACTUAL_SHA"

    echo "Extracting Boost $BOOST_VERSION..."
    tar xzf "$TMPFILE" -C "$DEP_DIR" --strip-components=1
    rm -f "$TMPFILE"
    echo "Boost $BOOST_VERSION installed in dep/."
fi

echo "Bootstrap complete."
