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
from pathlib import Path

from mcos_chatanywhere import PlannerError, plan_export, plan_located_export

PACKAGE = "com.morainet.mcos.android"
RECORD_ACTIVITY = f"{PACKAGE}/{PACKAGE}.demo.records.RecordExportActivity"
SAMPLE_TITLE = "Export an app record"
EXPORT_BUTTON = "Preview and export record"
ENTERED_EXPORT_BUTTON = "Preview and export entered record"
FIND_LATEST_BUTTON = "Find latest completed record"
LOCATED_EXPORT_BUTTON = "Preview and export located record"
RECORD_INPUT_ID = PACKAGE + ":id/record_id"
SAMPLE_RECORD_ID = "demo-001"
APPROVAL_TITLE = "Export this record?"
RUN_ID = re.compile(r"^Run: ([\w-]+)$", re.MULTILINE)
VALID_RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
EXPECTED_RECORD = "Record demo-001\nTitle: Site inspection\nStatus: completed\n"
EXPECTED_RECORDS = {
    SAMPLE_RECORD_ID: EXPECTED_RECORD,
    "demo-002": "Record demo-002\nTitle: Equipment inspection\nStatus: completed\n",
}
LOOKUP_RESULT = re.compile(r"^Latest completed record: ([\w-]+)\nLookup: ([0-9a-f-]{36})$", re.MULTILINE)
ARTIFACT_NAME = re.compile(r"record-[A-Za-z0-9._-]+\.txt")
ADB_TEXT = re.compile(r"[A-Za-z0-9 .,_@-]{1,120}")


class DeviceError(Exception):
    pass


class ExportError(DeviceError):
    def __init__(self, message, run_id):
        super().__init__(message)
        self.run_id = run_id


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def invoke(self, *args, binary=False):
        command = [self.adb]
        if self.serial:
            command += ["-s", self.serial]
        try:
            result = subprocess.run(
                [*command, *args], capture_output=True, timeout=20,
                env={name: value for name, value in os.environ.items() if name != "CHATANYWHERE_API_KEY"},
            )
        except subprocess.TimeoutExpired as error:
            raise DeviceError(f"ADB timed out on {args[0]}. Check the current task before retrying.") from error
        except OSError as error:
            raise DeviceError(f"Cannot start ADB: {error}") from error
        if result.returncode:
            operation = " ".join(args[:2])
            detail = (result.stderr or result.stdout).decode("utf-8", errors="replace").strip()
            detail = detail or f"exit {result.returncode}"
            raise DeviceError(f"ADB {operation} failed: {detail}")
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

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

    def export_receipt(self, run_id):
        if not VALID_RUN_ID.fullmatch(run_id):
            raise DeviceError("Invalid export run ID.")
        path = f"files/export-results/{run_id}.json"
        script = f"if [ -f {path} ]; then cat {path}; fi"
        raw = self.invoke("shell", f"run-as {PACKAGE} sh -c '{script}'")
        if not raw:
            return None
        try:
            receipt = json.loads(raw)
        except ValueError as error:
            raise ExportError("The saved export result is not valid JSON.", run_id) from error
        if not isinstance(receipt, dict) or receipt.get("version") != 1 or receipt.get("runId") != run_id:
            raise ExportError("The saved export result does not match the requested run.", run_id)
        return receipt

    def launch_sample(self):
        report = self.invoke("shell", "am", "start", "-W", "-n", RECORD_ACTIVITY)
        status = re.search(r"^Status:[ \t]*(.+)$", report, re.MULTILINE)
        if re.search(r"^(?:Error|Exception)\b", report, re.MULTILINE) or (
            status and status.group(1).strip().lower() != "ok"
        ):
            raise DeviceError(report.strip())

    def tap_text(self, nodes, label):
        require_no_approval(nodes)
        matches = [
            node for node in nodes if node.get("package") == PACKAGE
            and node.get("text", "").strip().casefold() == label.casefold()
            and node.get("clickable") == "true" and node.get("enabled") == "true"
        ]
        if len(matches) != 1:
            raise DeviceError(f"Expected one enabled, clickable sample control for {label!r}; found {len(matches)}.")
        self.tap_node(matches[0])

    def tap_node(self, node):
        x1, y1, x2, y2 = screen_bounds(node)
        self.invoke("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def swipe_sample(self, nodes, direction, resource_id):
        require_no_approval(nodes)
        matches = [
            node for node in nodes
            if node.get("package") == PACKAGE and node.get("scrollable") == "true"
            and node.get("enabled") == "true"
            and (resource_id is None or node.get("resource-id") == resource_id)
        ]
        if len(matches) != 1:
            raise DeviceError(f"Expected one enabled, scrollable sample region; found {len(matches)}.")
        x1, y1, x2, y2 = screen_bounds(matches[0])
        height = y2 - y1
        if height < 4:
            raise DeviceError("The scroll region is too small for a swipe.")
        x = (x1 + x2) // 2
        top, bottom = y1 + height // 4, y2 - height // 4
        start, end = (bottom, top) if direction == "up" else (top, bottom)
        self.invoke("shell", "input", "swipe", str(x), str(start), str(x), str(end), "350")


def screen_bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise DeviceError("The control has invalid screen bounds.")
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1:
        raise DeviceError("The control has empty screen bounds.")
    return x1, y1, x2, y2


def sample_text(nodes):
    return "\n".join(node.get("text", "") for node in nodes if node.get("package") == PACKAGE)


def has_sample_text(nodes, expected):
    return any(line.strip().casefold() == expected.casefold() for line in sample_text(nodes).splitlines())


def require_no_approval(nodes):
    if has_sample_text(nodes, APPROVAL_TITLE):
        raise DeviceError("An export is waiting for a decision on the phone.")


def verify_sample_state(device, matches, wait_seconds, failure):
    deadline = time.monotonic() + wait_seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            nodes = device.observe()
            if matches(nodes):
                return nodes
        except DeviceError as error:
            last_error = error
        time.sleep(0.3)
    detail = f" Last observation error: {last_error}" if last_error else ""
    raise DeviceError(f"{failure} Check the phone before retrying.{detail}")


def verify_sample_text(device, expected, wait_seconds, action):
    verify_sample_state(
        device, lambda nodes: has_sample_text(nodes, expected), wait_seconds,
        f"{action}, but {expected!r} was not observed.",
    )
    return {"status": "succeeded", "verified": True, "matchedText": expected}


def sample_input(nodes, resource_id):
    fields = [
        node for node in nodes if node.get("package") == PACKAGE
        and node.get("resource-id") == resource_id and node.get("class") == "android.widget.EditText"
        and node.get("enabled") == "true" and node.get("password") == "false"
    ]
    if len(fields) != 1:
        raise DeviceError(f"Expected one enabled, non-password sample input for {resource_id!r}; found {len(fields)}.")
    return fields[0]


def validate_adb_text(value):
    if not ADB_TEXT.fullmatch(value) or not value.strip() or value != value.strip():
        raise DeviceError("Use 1–120 ASCII letters, digits, internal spaces or .,_@- for ADB text input.")


def enter_sample_text(device, nodes, resource_id, value, wait_seconds):
    require_no_approval(nodes)
    field = sample_input(nodes, resource_id)
    if field.get("text") != "":
        raise DeviceError("The target input must be empty before typing.")
    if field.get("focused") != "true":
        device.tap_node(field)
        nodes = device.observe()
        require_no_approval(nodes)
        field = sample_input(nodes, resource_id)
    if field.get("focused") != "true" or field.get("text") != "":
        raise DeviceError("The empty target input did not receive focus.")

    # ADB joins shell arguments. Only the restricted character set above
    # reaches the shell, and Android's input command decodes %s as a space.
    device.invoke("shell", "input", "text", value.replace(" ", "%s"))
    return verify_sample_state(
        device, lambda nodes: sample_input(nodes, resource_id).get("text") == value,
        wait_seconds, "Text sent, but the target input did not show the requested value.",
    )


def input_and_verify(device, resource_id, value, wait_seconds):
    validate_adb_text(value)
    enter_sample_text(device, device.observe(), resource_id, value, wait_seconds)
    return {"status": "succeeded", "verified": True, "resourceId": resource_id}


def observe_before_action(device, expected):
    nodes = device.observe()
    if has_sample_text(nodes, expected):
        raise DeviceError("The expected text is already visible. Choose a new state to verify.")
    return nodes


def tap_and_verify(device, label, expected, wait_seconds):
    nodes = observe_before_action(device, expected)
    device.tap_text(nodes, label)
    return verify_sample_text(device, expected, wait_seconds, "Tap sent")


def swipe_and_verify(device, direction, resource_id, expected, wait_seconds):
    nodes = observe_before_action(device, expected)
    device.swipe_sample(nodes, direction, resource_id)
    return verify_sample_text(device, expected, wait_seconds, "Swipe sent")


def launch_and_verify(device, expected, wait_seconds):
    device.launch_sample()
    return verify_sample_text(device, expected, wait_seconds, "Launch requested")


def visible_sample_nodes(device):
    fields = ("text", "resource-id", "class", "bounds", "enabled", "clickable", "scrollable", "focused", "password")
    nodes = [
        {key: node.get(key) for key in fields}
        for node in device.observe() if node.get("package") == PACKAGE
    ]
    if not nodes:
        raise DeviceError("Open the MCOS Record Export app before observing its UI.")
    return nodes


def observe_sample(device, screenshot):
    nodes = visible_sample_nodes(device)
    result = {"nodes": nodes}
    if screenshot is not None:
        png = device.invoke("exec-out", "screencap", "-p", binary=True)
        if not png.startswith(b"\x89PNG\r\n\x1a\n") or not png.endswith(bytes.fromhex("0000000049454e44ae426082")):
            raise DeviceError("ADB did not return a complete PNG.")
        if nodes != visible_sample_nodes(device):
            raise DeviceError("The sample UI changed during capture. Observe again.")
        try:
            with screenshot.open("xb") as output:
                output.write(png)
        except OSError as error:
            raise DeviceError(f"Cannot save screenshot: {error}") from error
        result["screenshot"] = str(screenshot.absolute())
    return result


def displayed_run(screen):
    match = RUN_ID.search(screen)
    return match.group(1) if match else None


def observed_lookup(nodes):
    results = LOOKUP_RESULT.findall(sample_text(nodes))
    if len(results) == 1 and results[0][0] in EXPECTED_RECORDS:
        return results[0]
    return None


def lookup_latest_record(device, wait_seconds):
    device.launch_sample()
    nodes = verify_sample_state(
        device,
        lambda screen: has_sample_text(screen, FIND_LATEST_BUTTON) or has_sample_text(screen, APPROVAL_TITLE),
        wait_seconds,
        "The latest-record tool is not visible in the MCOS debug screen.",
    )
    previous = observed_lookup(nodes)
    device.tap_text(nodes, FIND_LATEST_BUTTON)

    def fresh_result(screen):
        require_no_approval(screen)
        result = observed_lookup(screen)
        return result is not None and result != previous

    updated = verify_sample_state(device, fresh_result, wait_seconds, "No fresh completed record was observed.")
    return observed_lookup(updated)


def run_export(device, wait_seconds, record_id=None, *, until_approval=False, lookup_token=None):
    if record_id is not None:
        validate_adb_text(record_id)
    before = device.export_files()
    button = EXPORT_BUTTON
    if lookup_token is None:
        device.launch_sample()
        nodes = verify_sample_state(
            device,
            lambda screen: has_sample_text(screen, APPROVAL_TITLE) or (
                has_sample_text(screen, EXPORT_BUTTON) if record_id is None else any(
                    node.get("package") == PACKAGE and node.get("resource-id") == RECORD_INPUT_ID
                    for node in screen
                )
            ),
            wait_seconds,
            "The sample export screen did not become ready.",
        )
    else:
        nodes = device.observe()
        if observed_lookup(nodes) != (record_id, lookup_token):
            raise DeviceError("The located record changed before export. Start a fresh agent run.")
        button = LOCATED_EXPORT_BUTTON
    if record_id is not None and lookup_token is None:
        require_no_approval(nodes)
        field = sample_input(nodes, RECORD_INPUT_ID)
        if field.get("text") == "":
            nodes = enter_sample_text(device, nodes, RECORD_INPUT_ID, record_id, wait_seconds)
        elif field.get("text") != record_id:
            raise DeviceError("The record ID field contains a different value. Clear it before retrying.")
        button = ENTERED_EXPORT_BUTTON
    initial_screen = sample_text(nodes)
    previous_run = displayed_run(initial_screen)
    device.tap_text(nodes, button)
    print("Approve, reject, or cancel the export on the phone if prompted.", file=sys.stderr)
    if not until_approval:
        time.sleep(min(3, wait_seconds / 4))
    return wait_for_export(
        device, wait_seconds, before=before, previous_run=previous_run, record_id=record_id,
        until_approval=until_approval,
    )


def verify_export(device, run_id, wait_seconds, record_id=None):
    if not VALID_RUN_ID.fullmatch(run_id):
        raise DeviceError("Invalid run ID. Use the run ID shown by the sample or export-record.")
    return wait_for_export(device, wait_seconds, run_id=run_id, record_id=record_id, read_receipt=True)


def run_agent_export(device, task, wait_seconds, *, wait_for_result=False):
    action = plan_export(task)
    if action == "clarify":
        return {"status": "clarification_required", "verified": False,
                "message": "Specify demo-001 or ask to export the latest completed inspection."}
    if action == "lookup_latest_completed":
        record_id, lookup_token = lookup_latest_record(device, wait_seconds)
        print(f"Observed latest completed record: {record_id}", file=sys.stderr)
        plan_located_export(task, record_id)
        result = run_export(device, wait_seconds, record_id, until_approval=not wait_for_result,
                            lookup_token=lookup_token)
        return {**result, "recordId": record_id}
    if action != "export_demo_record":
        raise DeviceError("This agent supports exporting demo-001 or the latest completed inspection.")
    return run_export(device, wait_seconds, SAMPLE_RECORD_ID, until_approval=not wait_for_result)


def verified_export_file(device, run_id, name, before=None, record_id=None):
    if not isinstance(name, str) or not ARTIFACT_NAME.fullmatch(name):
        raise ExportError("The sample did not report a single valid export filename.", run_id)
    try:
        files = device.export_files()
        if before is not None and files - before != {name}:
            raise DeviceError("The filename shown by the app does not identify one new export file.")
        if name not in files:
            raise DeviceError("The export file shown by the app is missing.")
        content = device.invoke("shell", "run-as", PACKAGE, "cat", f"files/record-exports/{name}")
        expected_record = EXPECTED_RECORDS.get(SAMPLE_RECORD_ID if record_id is None else record_id)
        if expected_record is None or content != expected_record:
            raise DeviceError("The export file does not match the expected record.")
    except DeviceError as error:
        raise ExportError(str(error), run_id) from error
    return {"status": "succeeded", "verified": True, "file": name, "runId": run_id}


def verify_saved_export(device, run_id, receipt, expected_record_id):
    status = receipt.get("status")
    if status == "succeeded":
        record_id = receipt.get("recordId", SAMPLE_RECORD_ID)
        if not isinstance(record_id, str) or record_id not in EXPECTED_RECORDS:
            raise ExportError("The saved export result has an invalid record ID.", run_id)
        if expected_record_id is not None and record_id != expected_record_id:
            raise ExportError("The saved export result is for a different record.", run_id)
        result = verified_export_file(device, run_id, receipt.get("file"), record_id=record_id)
        return {**result, "recordId": record_id} if "recordId" in receipt else result
    if status == "cancelled":
        return {"status": "cancelled", "verified": False, "runId": run_id}
    if status == "failed" and isinstance(receipt.get("error"), str):
        raise ExportError(f"Task failed: {receipt['error']}", run_id)
    raise ExportError("The saved export result has an invalid status.", run_id)


def wait_for_export(device, wait_seconds, *, run_id=None, before=None, previous_run=None, record_id=None,
                    read_receipt=False, until_approval=False):
    deadline = time.monotonic() + wait_seconds
    last_error = None
    approval_pending = False
    while time.monotonic() < deadline:
        approval_pending = False
        if read_receipt:
            receipt = device.export_receipt(run_id)
            if receipt is not None:
                return verify_saved_export(device, run_id, receipt, record_id)
        try:
            screen = sample_text(device.observe())
        except DeviceError as error:
            last_error = error
            time.sleep(0.3)
            continue
        observed_run = displayed_run(screen)
        if run_id is None and observed_run and observed_run != previous_run:
            run_id = observed_run
            print(f"Run: {run_id}", file=sys.stderr)
        if run_id is None or observed_run != run_id:
            time.sleep(0.3)
            continue
        lines = screen.splitlines()
        if "Task cancelled." in lines:
            return {"status": "cancelled", "verified": False, "runId": run_id}
        failure = next((line for line in lines
                        if line.startswith(("Task failed:", "Unable to complete task:"))), None)
        if failure is not None:
            raise ExportError(failure, run_id)
        if "Export completed." in lines:
            if read_receipt:
                receipt = device.export_receipt(run_id)
                if receipt is not None:
                    return verify_saved_export(device, run_id, receipt, record_id)
            names = [line.removeprefix("File: ") for line in lines if line.startswith("File: ")]
            if not names:
                # The app can publish the completion line before filling its filename.
                time.sleep(0.3)
                continue
            if len(names) != 1:
                raise ExportError("The sample did not report a single valid export filename.", run_id)
            return verified_export_file(device, run_id, names[0], before, record_id)
        approval_pending = APPROVAL_TITLE in lines and set(RUN_ID.findall(screen)) == {run_id}
        if until_approval and approval_pending:
            break
        time.sleep(0.3)
    if approval_pending and (read_receipt or until_approval):
        if read_receipt:
            # A terminal result may have been saved while ADB was reading the UI.
            receipt = device.export_receipt(run_id)
            if receipt is not None:
                return verify_saved_export(device, run_id, receipt, record_id)
        return {"status": "approval_required", "verified": False, "runId": run_id}
    detail = f" Last observation error: {last_error}" if last_error else ""
    raise ExportError(f"No verified result before the deadline. Check the task on the phone.{detail}", run_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("MCOS_ADB", "adb"))
    parser.add_argument("--serial", help="Device serial from the devices command")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("devices", help="List ADB devices and connection states")
    observe = commands.add_parser("observe", help="Read visible UI elements from the reference app")
    observe.add_argument("--screenshot", type=Path, metavar="FILE", help="Save a device screenshot as a new PNG")
    launch = commands.add_parser("launch", help="Open the sample app and verify its visible screen")
    launch.add_argument("--expect", default=SAMPLE_TITLE, help="Exact line of text expected in the sample app")
    launch.add_argument("--wait", type=int, default=15, help="Seconds to wait for the expected text")
    tap = commands.add_parser("tap", help="Tap a sample control and verify newly visible text")
    tap.add_argument("--text", required=True, help="Exact text of an enabled, clickable control")
    tap.add_argument("--expect", required=True, help="Exact line of text expected after the tap")
    tap.add_argument("--wait", type=int, default=15, help="Seconds to wait for the expected text")
    swipe = commands.add_parser("swipe", help="Swipe inside a sample scroll region and verify newly visible text")
    swipe.add_argument("--direction", required=True, choices=("up", "down"), help="Finger movement direction")
    swipe.add_argument("--resource-id", help="Exact resource ID when multiple scroll regions are visible")
    swipe.add_argument("--expect", required=True, help="Exact line of text expected after the swipe")
    swipe.add_argument("--wait", type=int, default=15, help="Seconds to wait for the expected text")
    type_text = commands.add_parser("type", help="Type into an empty sample input and verify its exact value")
    type_text.add_argument("--resource-id", required=True, help="Exact resource ID of the target input")
    type_text.add_argument("--text", required=True, help="Text to enter")
    type_text.add_argument("--wait", type=int, default=15, help="Seconds to wait for the updated value")
    export = commands.add_parser("export-record", help="Start and verify the sample export")
    export.add_argument("--record-id", help="Fill and export this record ID instead of using the preset")
    export.add_argument("--until-approval", action="store_true", help="Return when the phone is waiting for approval")
    export.add_argument("--wait", type=int, default=60, help="Seconds to wait for a result")
    agent = commands.add_parser("agent-export", help="Plan a sample export from natural language using ChatAnywhere")
    agent.add_argument("--task", required=True, help="Natural-language export request")
    agent.add_argument("--wait", type=int, default=60, help="Seconds to wait for the requested device state")
    agent.add_argument("--wait-for-result", action="store_true", help="Keep waiting through phone approval")
    verify = commands.add_parser("verify-export", help="Check an existing run without launching another export")
    verify.add_argument("--run-id", required=True, help="Run ID shown by the sample or export-record")
    verify.add_argument("--record-id", choices=EXPECTED_RECORDS, help="Require the run to export this record ID")
    verify.add_argument("--wait", type=int, default=15, help="Seconds to observe before reporting the run state")
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
            result = observe_sample(device, args.screenshot)
        else:
            if not 1 <= args.wait <= 600:
                raise DeviceError("--wait must be between 1 and 600 seconds.")
            if args.command == "launch":
                expected = args.expect.strip()
                if not expected:
                    raise DeviceError("--expect must be non-empty.")
                result = launch_and_verify(device, expected, args.wait)
            elif args.command == "tap":
                label, expected = args.text.strip(), args.expect.strip()
                if not label or not expected:
                    raise DeviceError("--text and --expect must be non-empty.")
                result = tap_and_verify(device, label, expected, args.wait)
            elif args.command == "swipe":
                expected = args.expect.strip()
                if not expected:
                    raise DeviceError("--expect must be non-empty.")
                result = swipe_and_verify(device, args.direction, args.resource_id, expected, args.wait)
            elif args.command == "type":
                result = input_and_verify(device, args.resource_id, args.text, args.wait)
            elif args.command == "verify-export":
                result = verify_export(device, args.run_id, args.wait, args.record_id)
            elif args.command == "agent-export":
                result = run_agent_export(device, args.task, args.wait, wait_for_result=args.wait_for_result)
            else:
                result = run_export(device, args.wait, args.record_id, until_approval=args.until_approval)
        print(json.dumps(result, ensure_ascii=False))
        return {"cancelled": 2, "approval_required": 3, "clarification_required": 4}.get(result.get("status"), 0)
    except (DeviceError, PlannerError) as error:
        result = {"status": "failed", "verified": False, "error": str(error)}
        if isinstance(error, ExportError) and error.run_id is not None:
            result["runId"] = error.run_id
        print(json.dumps(result, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
