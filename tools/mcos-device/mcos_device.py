#!/usr/bin/env python3
"""Local ADB driver for the MCOS Record Export reference app."""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.morainet.mcos.sample.embedded"
EXPORT_BUTTON = "Preview and export record"
RUN_ID = re.compile(r"^Run: ([\w-]+)$", re.MULTILINE)
EXPECTED_RECORD = "Record demo-001\nTitle: Site inspection\nStatus: completed\n"
ARTIFACT_NAME = re.compile(r"record-[A-Za-z0-9._-]+\.txt")


class DeviceError(Exception):
    pass


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def invoke(self, *args):
        command = [self.adb]
        if self.serial:
            command += ["-s", self.serial]
        try:
            result = subprocess.run(
                [*command, *args], capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=20,
            )
        except subprocess.TimeoutExpired as error:
            raise DeviceError(f"ADB timed out on {args[0]}. Check the current task before retrying.") from error
        except OSError as error:
            raise DeviceError(f"Cannot start ADB: {error}") from error
        if result.returncode:
            operation = " ".join(args[:2])
            detail = (result.stderr or result.stdout).strip() or f"exit {result.returncode}"
            raise DeviceError(f"ADB {operation} failed: {detail}")
        return result.stdout

    def devices(self):
        return [
            {"serial": fields[0], "state": fields[1]}
            for line in self.invoke("devices").splitlines()[1:]
            if len(fields := line.split()) >= 2
        ]

    def observe(self):
        path = f"/sdcard/mcos-{uuid.uuid4().hex}.xml"
        try:
            report = self.invoke("shell", "uiautomator", "dump", path)
            if "ERROR" in report:
                raise DeviceError(report.strip())
            root = ET.fromstring(self.invoke("exec-out", "cat", path))
        except ET.ParseError as error:
            raise DeviceError("ADB returned an invalid UI hierarchy.") from error
        finally:
            try:
                self.invoke("shell", "rm", "-f", path)
            except DeviceError:
                pass
        return list(root.iter("node"))

    def export_files(self):
        # Fixed script: no user input enters the device shell program.
        script = "if [ -d files/record-exports ]; then ls files/record-exports; fi"
        result = self.invoke("shell", f"run-as {PACKAGE} sh -c '{script}'")
        return {name for name in result.splitlines() if ARTIFACT_NAME.fullmatch(name)}

    def tap_export(self, nodes):
        matches = [
            node for node in nodes if node.get("package") == PACKAGE
            and node.get("text", "").casefold() == EXPORT_BUTTON.casefold()
            and node.get("clickable") == "true" and node.get("enabled") == "true"
        ]
        if len(matches) != 1:
            raise DeviceError("Expected exactly one enabled export button in the sample.")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", matches[0].get("bounds", ""))
        if not bounds:
            raise DeviceError("The export button has invalid screen bounds.")
        x1, y1, x2, y2 = map(int, bounds.groups())
        if x2 <= x1 or y2 <= y1:
            raise DeviceError("The export button has empty screen bounds.")
        self.invoke("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def sample_text(nodes):
    return "\n".join(node.get("text", "") for node in nodes if node.get("package") == PACKAGE)


def displayed_run(screen):
    match = RUN_ID.search(screen)
    return match.group(1) if match else None


def run_export(device, wait_seconds):
    before = device.export_files()
    output = device.invoke("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    if "Error" in output or "Exception" in output:
        raise DeviceError(output.strip())
    nodes = device.observe()
    initial_screen = sample_text(nodes)
    previous_run = displayed_run(initial_screen)
    if "Export this record?" in initial_screen:
        raise DeviceError("An earlier export is waiting for a decision. Resolve it before starting another.")
    device.tap_export(nodes)
    print("Approve, reject, or cancel the export on the phone if prompted.", file=sys.stderr)
    deadline = time.monotonic() + wait_seconds
    last_error = None
    time.sleep(min(3, wait_seconds / 4))
    while time.monotonic() < deadline:
        try:
            screen = sample_text(device.observe())
        except DeviceError as error:
            last_error = error
            time.sleep(0.3)
            continue
        run_id = displayed_run(screen)
        if run_id is None or run_id == previous_run:
            time.sleep(0.3)
            continue
        if "Task cancelled." in screen:
            return {"status": "cancelled", "verified": False, "runId": run_id}
        if "Task failed:" in screen or "Unable to complete task:" in screen:
            raise DeviceError(next(line for line in screen.splitlines() if
                                   line.startswith(("Task failed:", "Unable to complete task:"))))
        if "Export completed." in screen:
            created = device.export_files() - before
            # An old success screen may still be visible before the new tap is processed.
            if not created:
                time.sleep(0.3)
                continue
            if len(created) != 1:
                raise DeviceError("Export result is ambiguous: multiple new files were created.")
            name = created.pop()
            if f"File: {name}" not in screen.splitlines():
                raise DeviceError("The new file does not match the filename shown by the app.")
            content = device.invoke("shell", "run-as", PACKAGE, "cat", f"files/record-exports/{name}")
            if content != EXPECTED_RECORD:
                raise DeviceError("The new file does not match the expected record.")
            return {"status": "succeeded", "verified": True, "file": name,
                    "runId": run_id}
        time.sleep(0.3)
    detail = f" Last observation error: {last_error}" if last_error else ""
    raise DeviceError(f"No verified result before the deadline. Check the task on the phone.{detail}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("MCOS_ADB", "adb"))
    parser.add_argument("--serial", help="Device serial from the devices command")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("devices", help="List ADB devices and connection states")
    commands.add_parser("observe", help="Read visible UI elements from the reference app")
    export = commands.add_parser("export-record", help="Start and verify the sample export")
    export.add_argument("--wait", type=int, default=60, help="Seconds to wait for a result")
    args = parser.parse_args()
    try:
        devices = Device(args.adb, None).devices()
        if args.command == "devices":
            print(json.dumps(devices))
            return 0
        if not args.serial or not any(d == {"serial": args.serial, "state": "device"} for d in devices):
            raise DeviceError("Select an authorized device using --serial. Run devices to check its state.")
        device = Device(args.adb, args.serial)
        if args.command == "observe":
            nodes = [
                {key: node.get(key) for key in ("text", "resource-id", "class", "bounds", "enabled")}
                for node in device.observe() if node.get("package") == PACKAGE
            ]
            if not nodes:
                raise DeviceError("Open the MCOS Record Export app before observing its UI.")
            result = {"nodes": nodes}
        else:
            if not 1 <= args.wait <= 600:
                raise DeviceError("--wait must be between 1 and 600 seconds.")
            result = run_export(device, args.wait)
        print(json.dumps(result, ensure_ascii=False))
        return 2 if result.get("status") == "cancelled" else 0
    except DeviceError as error:
        print(json.dumps({"status": "failed", "verified": False, "error": str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
