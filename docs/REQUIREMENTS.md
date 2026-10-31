# Requirement → implementation → verification (Stage 3 completeness review)

> **Since 2026-10-30 the project is Android only** (see README). Requirements about the iPhone app, the desktop companion and the LAN protocol are archived with that code on `archive/multiplatform`.

States: **1** implemented, ready for acceptance testing · **2** implemented, blocked by an external
dependency · **3** not implemented (reason + remaining work). "Verified" means the check ran.
Last reviewed 2026-10-17 at the commit that adds this file.

## A. Common architecture
| Requirement | Files | Verification so far | State |
|---|---|---|---|
| Monorepo layout | `apps/`, `hosts/`, `protocol/`, `docs/`, `tests/`, `tools/` | — | 1 |
| Common input model | `protocol/rust/src/input.rs`, Kotlin `Input.kt`, Swift `Input.swift` | vectors | 1 |
| Profile format + migration | `protocol/PROFILE.md`, `schema/profile.v1.schema.json`, `profile.rs/.kt/.swift` | vectors (migration + invalid) in 3 languages | 1 |
| Versioned protocol | `protocol/SPEC.md` | 3-language vectors + live interop | 1 |
| Shared vs OS boundary | `nine_host_core::Backend` | e2e with mock; per-OS crates | 1 |

## B. Android
| Requirement | Files (`apps/android/app/src/main/kotlin/dev/ninecontroller/android/…`) | Verification | State |
|---|---|---|---|
| Dashboard | `ui/home/HomeScreen.kt` | compiles | 1 |
| Discovery | `net/Discovery.kt` | compiles | 1 |
| QR + code pairing | `ui/pair/*`, `net/ConnectionManager.kt` | Kotlin client↔Rust host live (QR) | 1 |
| Diagnostics | `ui/diagnostics/DiagnosticsScreen.kt` | compiles | 1 |
| Layouts, move/resize | `ui/editor/LayoutEditorScreen.kt` | compiles | 1 |
| Buttons, sticks, D-pad, triggers, pedals | `ui/controller/*` | engine unit tests | 1 |
| Motion steering, calibration, dead zones, filtering, curves, lock | `input/MotionSensor.kt`, `ui/controller/ControllerScreen.kt`, `ui/motion/MotionScreen.kt`, protocol `Motion.kt` | motion vectors | 1 |
| Profiles + import/export | `data/ProfileStore.kt`, `ui/profiles/ProfilesScreen.kt` | vectors | 1 |
| Haptics | `input/Haptics.kt` | compiles | 1 |
| Orientation, accessibility | `ControllerScreen.kt` (orientation lock, semantics) | compiles | 1 |
| Settings, privacy | `ui/settings/SettingsScreen.kt`, `data_extraction_rules.xml` | compiles | 1 |
| Reconnect | `net/ConnectionManager.kt` | Rust-side resume e2e | 1 |
| Installable APK + commands | `docs/BUILD.md` | **debug APK built**; not installed (no device) | 1 |

## C. iOS
| Requirement | Files | Verification | State |
|---|---|---|---|
| Native Swift app, Xcode project | `apps/ios/App/*`, `project.yml`, `NineController.xcodeproj` | sources parse; **no type-check/build: full Xcode missing** | 2 (Xcode) |
| Shared protocol + profiles | `protocol/swift` (NineKit) | vectors + live interop | 1 |
| Touch controller | `App/ControllerView.swift` | parse only | 2 |
| Core Motion steering + calibration | `App/MotionInput.swift`, `ControllerView.swift` | parse only | 2 |
| Profiles, discovery, pairing, status, haptics, a11y, reconnect | `App/*`, `apps/ios/Core` | Core tests pass; UI parse only | 2 |
| Signed IPA | — | needs Apple team + Xcode | 2 |

## D. macOS host
| Requirement | Files | Verification | State |
|---|---|---|---|
| Host UI, service, pairing, receiver, profiles (name), diagnostics, reconnection, mapping | `hosts/core`, `hosts/app` | e2e tests; app compiles; CLI run on this Mac | 1 |
| OS-visible controller | `hosts/macos/src/iohid.rs` | **verified Blocked** (entitlement) | 2 (Apple entitlement) |

## E. Windows host
| Requirement | Files | Verification | State |
|---|---|---|---|
| Host app, pipeline, mapping, pairing, diagnostics | shared core + app | shared tests | 1 |
| Driver options investigated | `docs/decisions/0007-windows-driver.md`, `FEASIBILITY.md` | research | 1 |
| Real VHF driver + interface | `hosts/windows/driver/NineVhf/*`, `hosts/windows/src/*` | Rust type-checks for Windows; descriptor/IOCTL parity tests | 2 (Windows PC + WDK; signing) |
| Build + install instructions | `docs/platforms/windows.md`, NSIS hooks, `nine-devnode` | not run | 2 |

## F. Linux host
| Requirement | Files | Verification | State |
|---|---|---|---|
| uinput gamepad + FF | `hosts/linux/src/uinput.rs` | type-checks (x86_64, aarch64); mapping/ioctl unit tests written; **not run** | 2 (Linux machine) |
| Permissions/setup | `hosts/linux/packaging/*`, `docs/platforms/linux.md` | — | 1 |
| Clean disconnect | core neutral + `UinputPad::drop` | core e2e | 1 |

## G. Shared input and network
| Requirement | Where | Verification | State |
|---|---|---|---|
| Buttons + transitions | `InputState::transitions`, backends emit changes only | unit tests | 1 |
| Axes and ranges | `frame.rs` bounds | vectors (out_of_range) | 1 |
| Timestamped, sequenced frames | `InputFrame.t_us`, header counter | vectors | 1 |
| Motion steering + curves | `motion.*` | vectors | 1 |
| Authenticated encrypted pairing + sessions | Noise XX, pairing | `snow` interop, vectors, live interop | 1 |
| Replay + malformed defenses | `Freshness`, `Header::peek` | e2e + vectors | 1 |
| Neutral on disconnect | `tick`, `end_session`, pause flag, driver cleanup | e2e | 1 |
| Gap detection, stale rejection, jitter, reconnect | `Freshness`, `Jitter`, resume | e2e | 1 |
| Up to four slots | `host.rs` | e2e `four_slots_then_full` | 1 |
| Profile compatibility + migration | `profile.*` | vectors | 1 |
| Diagnostics + graceful errors | snapshots, error codes, app messages | e2e | 1 |

## H. Premium experience
| Requirement | Where | Verification | State |
|---|---|---|---|
| Racing preset, distinct controls, haptics, sensitivity, calibration, live indicators, editor | presets + apps | screenshots of desktop UI (preview data); phones not yet run | 1 |
| Desktop companion | `hosts/app/ui` | design-preview screenshots 1440 px | 1 |

## Controller-9 transport requirements (master prompt §C–F)
| Requirement | Files | Verification so far | State |
|---|---|---|---|
| Common transport interface, input model independent of link | `protocol/SPEC.md §8`, `protocol/rust/src/link.rs`, `hosts/core/src/link.rs`, Kotlin `Links.kt`, Swift `Client.swift` | 3-language vectors; host e2e over IP, stream, simulated GATT | 1 |
| Local Wi-Fi/LAN without Internet, multi-interface, QR/manual fallback | `netif.rs`, multi-address URI, address racing (Android `Transports.kt`, iOS `Transports.swift`) | loopback live interop | 1 (device test pending) |
| Hotspot mode | IP binding + subnet classification + in-app guidance | — | 1 (UNVERIFIED) |
| Peer-to-peer Wi-Fi | Android `LocalNetwork.kt` (autonomous Wi-Fi Direct group); Apple peer-to-peer not used | compiles | 1 Android (UNVERIFIED) / 3 iPhone (no API) |
| Bluetooth LE | host `ble.rs` (btleplug central), Android `BlePeripheral.kt`, iOS `BlePeripheral.swift` | segmentation vectors; simulated GATT e2e at MTU 23/185 | 1 (radio UNVERIFIED) |
| Bluetooth Classic RFCOMM | — | — | 3 (stream binding ready to carry it) |
| USB tethering / Ethernet | IP binding + "USB network"/"Ethernet" classification | — | 1 (UNVERIFIED) |
| Android USB accessory, iPhone USB data channel | — | — | 3 (AOA not started; iPhone not possible for third-party apps) |
| Connection manager: ranking, failover, anti-flap, override, diagnostics | `policy.rs/.kt/.swift`, Android + iOS `ConnectionManager` | policy vectors (9 scenarios × 3 languages); failover/takeover integration tests | 1 |
| Safe neutral on transitions; one identity ↔ one slot | host takeover + watchdog | `links.rs` | 1 |
| Name migration | `docs/NAME_MIGRATION.md` | suites green after rename | 1 |
| Release workflows, checksums, download page | `.github/workflows/`, `tools/release/checksums.sh`, `site/download/` | YAML parses; page screenshot at 390 px | 1 (workflows never run) |

## Targets (not yet measurable here)
Wi-Fi latency, USB latency, motion ≥ 100 Hz, reconnect < 3 s, battery < 8 %/h, setup < 2 min:
all need devices (TEST_PLAN A12–A14). Loopback numbers in `tests/results/` are protocol overhead only.

## Not implemented (state 3)
| Item | Reason | Remaining work |
|---|---|---|
| Android USB transport (ADB reverse / USB tethering) | not started; Wi-Fi first | use `adb reverse tcp:47901 tcp:47901` + UDP over USB tethering; measure A13 |
| Windows rumble | descriptor has no output report | add PID/vendor output report + `EvtVhfAsyncOperationWriteReport` |
| macOS rumble | needs `IOHIDUserDeviceRegisterSetReportBlock` | after entitlement |
| Host-side remapping UI | mapping lives on the phone profile today | optional per-slot remap in host |
| Desktop tray / launch-at-login | not started | Tauri tray + autostart plugin |
| Pinned pid.codes USB product id | placeholder 0x9C01 | request at pid.codes before release |
