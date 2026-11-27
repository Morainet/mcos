import unittest
from unittest.mock import patch
from xml.etree.ElementTree import Element

from mcos_device import (
    APPROVAL_TITLE, PACKAGE, RECORD_ACTIVITY, SAMPLE_TITLE, Device, DeviceError, ExportError,
    EXPECTED_RECORD, FIND_LATEST_BUTTON, LOCATED_EXPORT_BUTTON, input_and_verify, launch_and_verify,
    lookup_latest_record, run_export, swipe_and_verify, tap_and_verify, verify_export,
)


def control(text, **attributes):
    return Element("node", {
        "text": text, "package": PACKAGE, "clickable": "true",
        "enabled": "true", "bounds": "[10,20][30,40]", **attributes,
    })


class SampleDevice(Device):
    def __init__(self, *screens, launch_report="Status: ok"):
        self.screens = iter(screens)
        self.commands = []
        self.launch_report = launch_report

    def observe(self):
        return next(self.screens)

    def invoke(self, *args, **kwargs):
        self.commands.append(args)
        return self.launch_report


class LaunchTest(unittest.TestCase):
    def test_launch_checks_sample_screen(self):
        device = SampleDevice([control(SAMPLE_TITLE + "\n\nRecord demo-001")])
        result = launch_and_verify(device, SAMPLE_TITLE, 1)
        self.assertTrue(result["verified"])
        self.assertEqual(device.commands, [("shell", "am", "start", "-W", "-n", RECORD_ACTIVITY)])

    def test_matching_text_from_another_app_does_not_verify(self):
        device = SampleDevice([control(SAMPLE_TITLE, package="com.example.other")])
        with self.assertRaisesRegex(DeviceError, "was not observed"):
            # Allow one observation, then expire without sleeping or relaunching.
            with patch("mcos_device.time.monotonic", side_effect=[0, 0, 2]), patch("mcos_device.time.sleep"):
                launch_and_verify(device, SAMPLE_TITLE, 1)
        self.assertEqual(len(device.commands), 1)

    def test_activity_manager_failure_stops_before_observation(self):
        device = SampleDevice(launch_report="Status: timeout")
        with self.assertRaisesRegex(DeviceError, "Status: timeout"):
            launch_and_verify(device, SAMPLE_TITLE, 1)


class TapTest(unittest.TestCase):
    def test_taps_once_and_checks_new_text(self):
        device = SampleDevice([control("PREVIEW")], [control(APPROVAL_TITLE)])
        result = tap_and_verify(device, "Preview", APPROVAL_TITLE, 1)
        self.assertEqual(result["matchedText"], APPROVAL_TITLE)
        self.assertTrue(result["verified"])
        self.assertEqual(device.commands, [("shell", "input", "tap", "20", "30")])

    def test_preexisting_text_cannot_verify_an_action(self):
        device = SampleDevice([control("Preview"), control("Complete")])
        with self.assertRaisesRegex(DeviceError, "already visible"):
            tap_and_verify(device, "Preview", "Complete", 1)
        self.assertEqual(device.commands, [])

    def test_does_not_tap_ambiguous_or_foreign_controls(self):
        for nodes in ([control("Preview"), control("Preview")],
                      [control("Preview", package="com.example.other")]):
            with self.subTest(nodes=nodes):
                device = SampleDevice(nodes)
                with self.assertRaisesRegex(DeviceError, "Expected one enabled, clickable"):
                    tap_and_verify(device, "Preview", "Complete", 1)
                self.assertEqual(device.commands, [])

    def test_confirmation_buttons_are_left_to_phone_user(self):
        device = SampleDevice([control(APPROVAL_TITLE), control("Export")])
        with self.assertRaisesRegex(DeviceError, "waiting for a decision"):
            tap_and_verify(device, "Export", "Complete", 1)
        self.assertEqual(device.commands, [])

    def test_unverified_click_is_not_repeated(self):
        device = SampleDevice([control("Preview")])
        with self.assertRaisesRegex(DeviceError, "Tap sent"):
            tap_and_verify(device, "Preview", "Complete", 0)
        self.assertEqual(len(device.commands), 1)


class SwipeTest(unittest.TestCase):
    def test_swipes_inside_selected_region_in_requested_direction(self):
        region = control("", scrollable="true", bounds="[10,20][110,420]", **{"resource-id": "target"})
        other = control("", scrollable="true", **{"resource-id": "other"})
        for direction, start, end in (("up", "320", "120"), ("down", "120", "320")):
            with self.subTest(direction=direction):
                device = SampleDevice([other, region], [control("Next record")])
                result = swipe_and_verify(device, direction, "target", "Next record", 1)
                self.assertTrue(result["verified"])
                self.assertEqual(device.commands, [("shell", "input", "swipe", "60", start, "60", end, "350")])

    def test_never_swipes_when_region_is_ambiguous_or_approval_is_open(self):
        region = control("", scrollable="true")
        for nodes, error in (([region, region], "found 2"),
                             ([region, control(APPROVAL_TITLE)], "waiting for a decision")):
            with self.subTest(error=error):
                device = SampleDevice(nodes)
                with self.assertRaisesRegex(DeviceError, error):
                    swipe_and_verify(device, "up", None, "Next record", 1)
                self.assertEqual(device.commands, [])

    def test_missing_outcome_does_not_repeat_gesture(self):
        device = SampleDevice([control("", scrollable="true", bounds="[0,0][50,100]")])
        with self.assertRaisesRegex(DeviceError, "Swipe sent"):
            swipe_and_verify(device, "up", None, "Next record", 0)
        self.assertEqual(len(device.commands), 1)


INPUT_ID = PACKAGE + ":id/record_id"


def editable(value="", **attributes):
    defaults = {"resource-id": INPUT_ID, "class": "android.widget.EditText", "password": "false", "focused": "true"}
    defaults.update(attributes)
    return control(value, **defaults)


class InputTest(unittest.TestCase):
    def test_focuses_target_encodes_spaces_and_checks_exact_value(self):
        device = SampleDevice(
            [editable(focused="false")], [editable()], [editable("Demo 001")],
        )
        result = input_and_verify(device, INPUT_ID, "Demo 001", 1)
        self.assertEqual(result, {"status": "succeeded", "verified": True, "resourceId": INPUT_ID})
        self.assertEqual(device.commands, [
            ("shell", "input", "tap", "20", "30"),
            ("shell", "input", "text", "Demo%s001"),
        ])

    def test_unsafe_or_unsupported_input_is_rejected_before_adb(self):
        for value in ("x;id", "hello%sworld", "中文", " trailing ", ""):
            with self.subTest(value=value):
                device = SampleDevice()
                with self.assertRaisesRegex(DeviceError, "ASCII"):
                    input_and_verify(device, INPUT_ID, value, 1)
                self.assertEqual(device.commands, [])

    def test_nonempty_password_or_ambiguous_field_cannot_receive_input(self):
        for nodes in ([editable("Existing")], [editable(password="true")], [editable(), editable()]):
            with self.subTest(nodes=nodes):
                device = SampleDevice(nodes)
                with self.assertRaises(DeviceError):
                    input_and_verify(device, INPUT_ID, "demo-001", 1)
                self.assertEqual(device.commands, [])

    def test_does_not_type_when_focus_does_not_move(self):
        device = SampleDevice([editable(focused="false")], [editable(focused="false")])
        with self.assertRaisesRegex(DeviceError, "did not receive focus"):
            input_and_verify(device, INPUT_ID, "demo-001", 1)
        self.assertEqual(device.commands, [("shell", "input", "tap", "20", "30")])

    def test_text_elsewhere_or_wrong_case_does_not_verify_or_retry(self):
        device = SampleDevice([editable()], [editable("Demo-001"), control("demo-001")])
        with patch("mcos_device.time.monotonic", side_effect=[0, 0, 2]), patch("mcos_device.time.sleep"):
            with self.assertRaisesRegex(DeviceError, "Text sent"):
                input_and_verify(device, INPUT_ID, "demo-001", 1)
        self.assertEqual(device.commands, [("shell", "input", "text", "demo-001")])

    def test_approval_blocks_input(self):
        device = SampleDevice([editable(), control(APPROVAL_TITLE)])
        with self.assertRaisesRegex(DeviceError, "waiting for a decision"):
            input_and_verify(device, INPUT_ID, "demo-001", 1)
        self.assertEqual(device.commands, [])


class ExportDevice(SampleDevice):
    def __init__(self, *screens, content=EXPECTED_RECORD, receipt=None):
        super().__init__(*screens)
        self.files = iter([{"record-old.txt"}, {"record-old.txt", "record-new.txt"}])
        self.content = content
        self.receipt = receipt

    def export_receipt(self, run_id):
        return self.receipt

    def export_files(self):
        return next(self.files)

    def invoke(self, *args, **kwargs):
        super().invoke(*args, **kwargs)
        if args[:4] == ("shell", "run-as", PACKAGE, "cat"):
            return self.content
        return self.launch_report


class EnteredExportTest(unittest.TestCase):
    def test_fills_field_and_uses_entered_record_button(self):
        button = control("Preview and export entered record", bounds="[0,100][100,160]")
        finished = control("Run: new-run\nExport completed.\nFile: record-new.txt")
        device = ExportDevice([editable()], [editable("demo-001"), button], [finished])
        with patch("mcos_device.time.sleep"):
            result = run_export(device, 1, "demo-001")
        self.assertEqual(result, {
            "status": "succeeded", "verified": True, "file": "record-new.txt", "runId": "new-run",
        })
        self.assertEqual(device.commands[1:3], [
            ("shell", "input", "text", "demo-001"),
            ("shell", "input", "tap", "50", "130"),
        ])

    def test_reuses_exact_value_and_reports_rejection(self):
        device = ExportDevice(
            [editable("demo-001"), control("Preview and export entered record")],
            [control("Run: new-run\nTask failed: Confirmation rejected")],
        )
        with patch("mcos_device.time.sleep"), self.assertRaisesRegex(DeviceError, "Confirmation rejected"):
            run_export(device, 1, "demo-001")
        self.assertEqual(device.commands[1:], [("shell", "input", "tap", "20", "30")])

    def test_existing_different_value_is_preserved(self):
        device = ExportDevice([editable("another-record"), control("Preview and export entered record")])
        with self.assertRaisesRegex(DeviceError, "contains a different value"):
            run_export(device, 1, "demo-001")
        self.assertEqual(len(device.commands), 1)

    def test_wrong_file_content_cannot_verify_export(self):
        device = ExportDevice(
            [editable("demo-001"), control("Preview and export entered record")],
            [control("Run: new-run\nExport completed.\nFile: record-new.txt")],
            content="Incorrect record",
        )
        with patch("mcos_device.time.sleep"), self.assertRaisesRegex(DeviceError, "does not match the expected"):
            run_export(device, 1, "demo-001")


class LocatedExportTest(unittest.TestCase):
    def test_lookup_requires_fresh_result_and_verified_selection_before_export(self):
        old_token = "1" * 36
        new_token = "2" * 36
        old = control(f"Latest completed record: demo-001\nLookup: {old_token}")
        new = control(f"Latest completed record: demo-002\nLookup: {new_token}")
        export = control(LOCATED_EXPORT_BUTTON)
        device = ExportDevice(
            [control(FIND_LATEST_BUTTON), old], [old], [new, export],
            [new, export], [control(APPROVAL_TITLE), control("Run: current")],
        )
        with patch("mcos_device.time.sleep"):
            record_id, token = lookup_latest_record(device, 1)
            self.assertEqual((record_id, token), ("demo-002", new_token))
            result = run_export(device, 1, record_id, until_approval=True, lookup_token=token)
        self.assertEqual(result["runId"], "current")
        self.assertEqual(device.commands, [
            ("shell", "am", "start", "-W", "-n", RECORD_ACTIVITY),
            ("shell", "input", "tap", "20", "30"),
            ("shell", "input", "tap", "20", "30"),
        ])

    def test_different_selection_cannot_trigger_export(self):
        device = ExportDevice([control(LOCATED_EXPORT_BUTTON)])
        with self.assertRaisesRegex(DeviceError, "changed before export"):
            run_export(device, 1, "demo-002", lookup_token="1" * 36)
        self.assertEqual(device.commands, [])


class VerifyExportTest(unittest.TestCase):
    def test_verify_deadline_reports_approval_for_requested_run(self):
        device = ExportDevice([control(APPROVAL_TITLE), control("Run: original")])
        with patch("mcos_device.time.monotonic", side_effect=[0, 0, 2]), patch("mcos_device.time.sleep"):
            result = verify_export(device, "original", 1)
        self.assertEqual(result, {"status": "approval_required", "verified": False, "runId": "original"})
        self.assertEqual(device.commands, [])

    def test_verify_deadline_does_not_report_disappeared_or_ambiguous_approval(self):
        for last_screen in (
            [control("Run: original")],
            [control(APPROVAL_TITLE), control("Run: original"), control("Run: other")],
        ):
            with self.subTest(last_screen=[node.get("text") for node in last_screen]):
                device = ExportDevice([control(APPROVAL_TITLE), control("Run: original")], last_screen)
                with patch("mcos_device.time.monotonic", side_effect=[0, 0, 0, 2]), patch("mcos_device.time.sleep"):
                    with self.assertRaisesRegex(ExportError, "No verified result"):
                        verify_export(device, "original", 1)

    def test_terminal_receipt_wins_if_saved_while_reading_approval_screen(self):
        device = ExportDevice([control(APPROVAL_TITLE), control("Run: original")])
        with patch.object(device, "export_receipt", side_effect=[None, {"status": "cancelled"}]) as receipt:
            with patch("mcos_device.time.monotonic", side_effect=[0, 0, 2]), patch("mcos_device.time.sleep"):
                result = verify_export(device, "original", 1)
        self.assertEqual(result, {"status": "cancelled", "verified": False, "runId": "original"})
        self.assertEqual(receipt.call_count, 2)

    def test_pending_approval_can_be_followed_by_read_only_file_verification(self):
        device = ExportDevice(
            [control(APPROVAL_TITLE), control("Run: original")],
            [control("Run: original\nExport completed.\nFile: record-new.txt")],
        )
        device.files = iter([{"record-old.txt", "record-new.txt"}])
        with patch("mcos_device.time.sleep"):
            result = verify_export(device, "original", 1)
        self.assertTrue(result["verified"])
        self.assertEqual(result["runId"], "original")
        self.assertEqual(device.commands, [
            ("shell", "run-as", PACKAGE, "cat", "files/record-exports/record-new.txt"),
        ])

    def test_other_runs_cannot_supply_the_result(self):
        device = ExportDevice([control("Run: other\nExport completed.\nFile: record-new.txt")])
        with patch("mcos_device.time.monotonic", side_effect=[0, 0, 2]), patch("mcos_device.time.sleep"):
            with self.assertRaises(ExportError) as failure:
                verify_export(device, "original", 1)
        self.assertEqual(failure.exception.run_id, "original")
        self.assertEqual(device.commands, [])

    def test_untrusted_filename_is_rejected_before_file_read(self):
        device = ExportDevice([control("Run: original\nExport completed.\nFile: ../record-new.txt")])
        with self.assertRaisesRegex(ExportError, "valid export filename"):
            verify_export(device, "original", 1)
        self.assertEqual(device.commands, [])

    def test_export_timeout_preserves_id_observed_in_approval(self):
        device = ExportDevice(
            [control("Preview and export record")],
            [control(APPROVAL_TITLE), control("Run: original")],
        )
        with patch("mcos_device.time.monotonic", side_effect=[0, 0, 0, 0, 2]), patch("mcos_device.time.sleep"):
            with self.assertRaises(ExportError) as failure:
                run_export(device, 1)
        self.assertEqual(failure.exception.run_id, "original")
        self.assertEqual(len(device.commands), 2)


class SavedExportTest(unittest.TestCase):
    def test_saved_result_must_match_the_agents_selected_record(self):
        device = ExportDevice(receipt={"status": "succeeded", "file": "record-new.txt", "recordId": "demo-001"})
        with self.assertRaisesRegex(ExportError, "different record"):
            verify_export(device, "original", 1, "demo-002")
        self.assertEqual(device.commands, [])

    def test_saved_success_verifies_file_without_reading_ui(self):
        device = ExportDevice(receipt={"status": "succeeded", "file": "record-new.txt"})
        device.files = iter([{"record-new.txt"}])
        result = verify_export(device, "original", 1)
        self.assertEqual(result, {
            "status": "succeeded", "verified": True, "file": "record-new.txt", "runId": "original",
        })
        self.assertEqual(device.commands, [
            ("shell", "run-as", PACKAGE, "cat", "files/record-exports/record-new.txt"),
        ])

    def test_saved_failure_and_cancellation_do_not_require_ui_or_file(self):
        failed = ExportDevice(receipt={"status": "failed", "error": "Confirmation rejected"})
        with self.assertRaisesRegex(ExportError, "Confirmation rejected"):
            verify_export(failed, "original", 1)
        self.assertEqual(failed.commands, [])
        cancelled = ExportDevice(receipt={"status": "cancelled"})
        self.assertEqual(verify_export(cancelled, "original", 1)["status"], "cancelled")
        self.assertEqual(cancelled.commands, [])

    def test_receipt_for_a_different_run_is_rejected(self):
        device = SampleDevice(launch_report='{"version":1,"runId":"other","status":"cancelled"}')
        with self.assertRaisesRegex(ExportError, "does not match"):
            verify_export(device, "original", 1)
        self.assertIn("files/export-results/original.json", device.commands[0][1])


class ExportApprovalHandoffTest(unittest.TestCase):
    def test_returns_fresh_approval_without_sending_an_approval_tap(self):
        button = control("Preview and export record")
        device = ExportDevice(
            [button, control("Run: previous")],
            [control(APPROVAL_TITLE), control("Run: previous")],
            [control(APPROVAL_TITLE)],
            [control(APPROVAL_TITLE), control("Run: new-run")],
        )
        with patch("mcos_device.time.sleep"):
            result = run_export(device, 1, until_approval=True)
        self.assertEqual(result, {"status": "approval_required", "verified": False, "runId": "new-run"})
        self.assertEqual(device.commands, [
            ("shell", "am", "start", "-W", "-n", RECORD_ACTIVITY),
            ("shell", "input", "tap", "20", "30"),
        ])

    def test_existing_approval_does_not_start_another_export(self):
        device = ExportDevice([control(APPROVAL_TITLE), control("Run: existing")])
        with self.assertRaisesRegex(DeviceError, "waiting for a decision"):
            run_export(device, 1, until_approval=True)
        self.assertEqual(len(device.commands), 1)

    def test_terminal_result_before_first_observed_confirmation_is_respected(self):
        device = ExportDevice(
            [control("Preview and export record")],
            [control("Run: new-run\nTask cancelled.")],
        )
        result = run_export(device, 1, until_approval=True)
        self.assertEqual(result, {"status": "cancelled", "verified": False, "runId": "new-run"})

    def test_waits_for_own_export_screen_before_tapping(self):
        device = ExportDevice(
            [control("Preview and export record", package="com.android.systemui")],
            [control("Preview and export record")],
            [control(APPROVAL_TITLE), control("Run: new-run")],
        )
        with patch("mcos_device.time.sleep"):
            result = run_export(device, 1, until_approval=True)
        self.assertEqual(result["status"], "approval_required")
        self.assertEqual(len(device.commands), 2)
