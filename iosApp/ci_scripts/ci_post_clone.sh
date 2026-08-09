#!/bin/sh

set -eu

if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  exit 0
fi

if ! command -v brew >/dev/null 2>&1; then
  echo "Xcode Cloud requires Homebrew to install the JDK 17 build dependency." >&2
  exit 1
fi

brew install openjdk@17
