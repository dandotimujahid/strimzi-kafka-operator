#!/usr/bin/env bash
set -e

ARCH=$1
if [ -z "$ARCH" ] || [ "$ARCH" == "amd64" ]; then
    ARCH="x86_64"
fi

if [ "$ARCH" == "arm64" ]; then
    ARCH="aarch64"
fi

if [ "$ARCH" == "s390x" ]; then
    ARCH="s390x"
fi

readonly VERSION="0.9.0"

if [ "$ARCH" == "s390x" ]; then
    sudo apt-get update
    sudo apt-get install -y shellcheck
else
    wget https://github.com/koalaman/shellcheck/releases/download/v$VERSION/shellcheck-v$VERSION.linux.$ARCH.tar.xz -O shellcheck.tar.xz
    tar xf shellcheck.tar.xz -C /tmp --strip-components 1
    chmod +x /tmp/shellcheck
    sudo mv /tmp/shellcheck /usr/bin/
fi
