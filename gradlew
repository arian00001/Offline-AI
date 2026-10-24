#!/bin/sh
# Minimal wrapper bootstrap; uses the official Gradle distribution configured in gradle-wrapper.properties.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
GRADLE_VERSION=8.9
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
GRADLE_HOME="$HOME/.gradle/wrapper/manual-gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$HOME/.gradle/wrapper"
  ZIP="$HOME/.gradle/wrapper/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP" || exit 1
  else echo 'Install Gradle 8.9 or curl to bootstrap Gradle.' >&2; exit 1; fi
  mkdir -p "$GRADLE_HOME"
  unzip -q "$ZIP" -d "$GRADLE_HOME" || exit 1
  mv "$GRADLE_HOME/gradle-$GRADLE_VERSION"/* "$GRADLE_HOME/"
  rmdir "$GRADLE_HOME/gradle-$GRADLE_VERSION" 2>/dev/null || true
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
