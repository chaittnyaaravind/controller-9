#!/bin/sh
# Run a Swift package's tests. With only the Command Line Tools installed, SwiftPM may fail to
# locate the Swift Testing macro plugin ("plugin for module 'TestingMacros' not found"); passing
# its directory explicitly is harmless when full Xcode is present.
# Usage: tools/swift-test.sh protocol/swift   |   tools/swift-test.sh apps/ios/Core
set -e
dir="${1:-.}"
plugin="$(xcode-select -p)/usr/lib/swift/host/plugins/testing"
cd "$dir"
if [ -d "$plugin" ]; then exec swift test -Xswiftc -plugin-path -Xswiftc "$plugin"; else exec swift test; fi
