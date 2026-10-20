#!/bin/sh
# Generate the winget manifests (packaging/winget/<version>/) for the published Windows installer.
# The SHA-256 is computed from the exact file the website serves. ViGEmBus is declared as a package
# dependency, so `winget install Controller9.Controller9` also installs the driver games need.
# Submit: fork microsoft/winget-pkgs, copy to manifests/c/Controller9/Controller9/<version>/, open a PR.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; V="${1:?usage: winget.sh <version>}"
F="Controller-9-$V-windows-x64-setup.exe"; FILE="$ROOT/site/public/downloads/$F"
[ -f "$FILE" ] || { echo "missing $FILE" >&2; exit 1; }
SHA=$(shasum -a 256 "$FILE" | cut -d' ' -f1 | tr a-f A-F)
OUT="$ROOT/packaging/winget/$V"; mkdir -p "$OUT"; ID=Controller9.Controller9; MV=1.10.0
cat >"$OUT/$ID.yaml" <<Y
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.version.$MV.schema.json
PackageIdentifier: $ID
PackageVersion: $V
DefaultLocale: en-US
ManifestType: version
ManifestVersion: $MV
Y
cat >"$OUT/$ID.installer.yaml" <<Y
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.installer.$MV.schema.json
PackageIdentifier: $ID
PackageVersion: $V
InstallerType: nullsoft
Scope: machine
UpgradeBehavior: install
Dependencies:
  PackageDependencies:
  - PackageIdentifier: ViGEm.ViGEmBus
Installers:
- Architecture: x64
  InstallerUrl: https://controller-9.vercel.app/downloads/$F
  InstallerSha256: $SHA
ManifestType: installer
ManifestVersion: $MV
Y
cat >"$OUT/$ID.locale.en-US.yaml" <<Y
# yaml-language-server: \$schema=https://aka.ms/winget-manifest.defaultLocale.$MV.schema.json
PackageIdentifier: $ID
PackageVersion: $V
PackageLocale: en-US
Publisher: Controller-9
PublisherUrl: https://controller-9.vercel.app/
PrivacyUrl: https://controller-9.vercel.app/privacy/
PackageName: Controller-9
PackageUrl: https://controller-9.vercel.app/
License: Freeware
ShortDescription: Use your phone as a game controller for this PC.
Description: |-
  The Windows companion for the Controller-9 phone app (Android, iPhone). Pair once with a code;
  games see an Xbox 360 controller (through the ViGEmBus driver) with rumble back to the phone.
  Local network or Bluetooth only, end-to-end encrypted, no account, no telemetry.
Tags:
- controller
- gamepad
- xinput
- phone
- remote
ManifestType: defaultLocale
ManifestVersion: $MV
Y
echo "$OUT"
