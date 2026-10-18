# ADR 0008 — Linux: uinput

**Status:** accepted (2026-10-17); **needs a Linux run to confirm**

**Decision.** `/dev/uinput` evdev gamepad with the xpad button/axis layout, hat D-pad, 0–1023
triggers and FF_RUMBLE pass-through. Default identity is USB 045e:028e ("Xbox 360 compatible") so
SDL/Steam/Proton apply their standard mapping — a compatibility identity, not an access-control
bypass; `Identity::Generic` is available. Non-root access via a `uaccess` udev rule.
