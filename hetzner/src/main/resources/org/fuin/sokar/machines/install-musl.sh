#!/usr/bin/env bash
# Installs the musl cross-toolchain plus a musl-built zlib, as required by
# `native-image --static --libc=musl`, which links sokar's hooks. Run on a snapshot as its build user.
set -euo pipefail

PREFIX="${MUSL_PREFIX:-$HOME/.local/opt}"
TC="$PREFIX/x86_64-linux-musl-native"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ZLIB_VERSION=1.3.1

# Both downloads are pinned by digest. Everything else this project installs is verified -
# the agent CLI by SHA-256, npm by lockfile integrity, the Node runtime by digest - and the
# toolchain that links the hooks had been the one exception.
#
# The URLs are overridable because musl.cc is a single small host that is not reachable from
# every network - GitHub's hosted runners cannot connect to it at all - so a mirror has to be
# usable. The digests are not: the digest is what is trusted, not the host, so it is written here
# and nothing in the environment can replace it - a mirror serves these bytes or the install stops.
MUSL_URL="${MUSL_URL:-https://musl.cc/x86_64-linux-musl-native.tgz}"
MUSL_SHA256=eb1db6f0f3c2bdbdbfb993d7ef7e2eeef82ac1259f6a6e1757c33a97dbcef3ad
ZLIB_URL="${ZLIB_URL:-https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz}"
ZLIB_SHA256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23

verify() {
    echo "$2  $1" | sha256sum -c - >/dev/null 2>&1 || {
        echo "digest mismatch for $1"
        echo "  expected $2"
        echo "  actual   $(sha256sum "$1" | cut -d" " -f1)"
        exit 1
    }
}

mkdir -p "$PREFIX"

if [ ! -x "$TC/bin/x86_64-linux-musl-gcc" ]; then
    echo "==> downloading musl toolchain"
    curl -fSL --retry 3 -o "$WORK/musl.tgz" "$MUSL_URL"
    verify "$WORK/musl.tgz" "$MUSL_SHA256"
    echo "==> extracting to $TC"
    tar -xzf "$WORK/musl.tgz" -C "$PREFIX"
else
    echo "==> musl toolchain already present at $TC"
fi

export PATH="$TC/bin:$PATH"
x86_64-linux-musl-gcc --version | head -1

if [ ! -f "$TC/lib/libz.a" ]; then
    echo "==> building zlib $ZLIB_VERSION against musl"
    curl -fSL --retry 3 -o "$WORK/zlib.tar.gz" "$ZLIB_URL"
    verify "$WORK/zlib.tar.gz" "$ZLIB_SHA256"
    tar -xzf "$WORK/zlib.tar.gz" -C "$WORK"
    cd "$WORK/zlib-$ZLIB_VERSION"
    CC=x86_64-linux-musl-gcc ./configure --static --prefix="$TC"
    make -j"$(nproc)" >/dev/null
    make install >/dev/null
else
    echo "==> musl zlib already present"
fi

echo "==> installed:"
ls -la "$TC/bin/x86_64-linux-musl-gcc" "$TC/lib/libz.a"
echo "==> add to PATH: $TC/bin"
