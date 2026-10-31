#!/bin/sh
# Controller-9 test suite (Android only). Runs everything that can run without a phone: the Kotlin
# profile/input model, the controller engine, touch maths, the Xbox Bluetooth HID reports and the APK
# build. Device tests (a real phone paired with a computer) are in docs/TEST_PLAN.md.
#   tests/run_all.sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
step() { printf '\n== %s\n' "$1"; }

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

step "Kotlin: input model and vectors, controller engine, touch maths, Xbox HID reports"
(cd apps/android && ./gradlew -q :protocol:test :app:testDebugUnitTest --console=plain) && pass "unit tests"

step "Android: debug APK"
(cd apps/android && ./gradlew -q :app:assembleDebug --console=plain) && pass "assembleDebug"

step "HID report vectors unchanged"
git diff --quiet -- tests/vectors && pass "tests/vectors stable" || { echo "  tests/vectors changed: review and commit them"; exit 1; }

printf '\nAll checks passed.\n'
