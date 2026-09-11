#!/usr/bin/env python3
"""Decide which emulator should run a module's instrumentation tests.

Uses the Android CLI (`android emulator list --long`) to look at every AVD, its API
level, and whether it is currently running (a running AVD has an ADB serial). It then
emits a one-line decision on stdout for the caller to act on:

    running <serial>    a compatible emulator is already up — target this serial
    boot <avd-id>       none running, but this compatible AVD can be booted

If nothing is compatible at all (no running and no available AVD meets the module's
minSdk) it exits non-zero with the reason on stderr.

"Compatible" = API level >= the module's minSdk. Among several candidates the first
one wins, as specified. The caller boots when told to (via `android emulator start`,
which blocks until the device is ready) and then re-runs this to get the serial.

`android emulator list --long` looks like:

    AVD ID            AVD Name          API Level    Status     Serial
    Pixel_9_Pro_XL    Pixel 9 Pro XL    android-36   Online     emulator-5554
    Medium_Tablet     Medium Tablet     android-34   Offline

The AVD Name column contains spaces, so we pull the API level (`android-<major>`) and
serial (`emulator-<n>`) out of each line by pattern rather than splitting on spaces.
"""

import argparse
import re
import subprocess
import sys

API_PATTERN = re.compile(r"android-(\d+)")
SERIAL_PATTERN = re.compile(r"(emulator-\d+|\b[0-9A-Za-z._-]+:\d+\b)")


def load_listing(input_file):
    """Return the raw `android emulator list --long` text, or exit on failure."""
    if input_file:
        with open(input_file, "r", encoding="utf-8") as handle:
            return handle.read()
    try:
        completed = subprocess.run(
            ["android", "emulator", "list", "--long"],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print("The `android` CLI is not on PATH — cannot list emulators.", file=sys.stderr)
        sys.exit(2)
    if completed.returncode != 0:
        print("`android emulator list --long` failed:\n" + completed.stderr.strip(), file=sys.stderr)
        sys.exit(2)
    return completed.stdout


def parse_avds(listing):
    """Return [(avd_id, api_level_or_None, serial_or_None), ...] for every AVD row."""
    avds = []
    for line in listing.splitlines():
        if not line.strip() or line.lstrip().startswith("AVD ID"):
            continue
        serial_match = SERIAL_PATTERN.search(line)
        api_match = API_PATTERN.search(line)
        avd_id = line.split()[0]
        api_level = int(api_match.group(1)) if api_match else None
        avds.append((avd_id, api_level, serial_match.group(1) if serial_match else None))
    return avds


def compatible(avd, min_sdk):
    """An AVD can run the tests if its API level is unknown or >= minSdk."""
    api_level = avd[1]
    return api_level is None or api_level >= min_sdk


def main():
    parser = argparse.ArgumentParser(description="Decide the emulator to use, or one to boot.")
    parser.add_argument("--min-sdk", type=int, required=True, help="Module minSdk.")
    parser.add_argument(
        "--input",
        help="Read listing text from a file instead of running the android CLI (testing).",
    )
    args = parser.parse_args()

    avds = parse_avds(load_listing(args.input))
    if not avds:
        print("No AVDs exist. Create one with `android emulator create`.", file=sys.stderr)
        return 1

    running = [avd for avd in avds if avd[2] is not None]
    running_compatible = [avd for avd in running if compatible(avd, args.min_sdk)]
    if running_compatible:
        avd_id, api_level, serial = running_compatible[0]
        if len(running_compatible) > 1:
            print(
                f"Multiple running emulators qualify; choosing the first: "
                f"{avd_id} (API {api_level}).",
                file=sys.stderr,
            )
        print(f"running {serial}")
        return 0

    # Nothing compatible is running — recommend booting a compatible available AVD.
    bootable = [avd for avd in avds if avd[2] is None and compatible(avd, args.min_sdk)]
    if bootable:
        avd_id, api_level, _ = bootable[0]
        print(
            f"No compatible emulator is running; will boot {avd_id} (API {api_level}).",
            file=sys.stderr,
        )
        print(f"boot {avd_id}")
        return 0

    listing = "\n".join(
        f"  {avd_id} (API {api}) {'running' if serial else 'offline'}"
        for avd_id, api, serial in avds
    )
    print(
        f"No emulator can run the tests: none meets the module's minSdk ({args.min_sdk}). "
        f"AVDs:\n{listing}\nCreate a higher-API AVD with `android emulator create`.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
