import io
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from mcos_chatanywhere import ENDPOINT, MAINLAND_ENDPOINT, MAX_RESPONSE_BYTES, PlannerError, plan_export, plan_located_export
from mcos_device import Device, DeviceError, SAMPLE_RECORD_ID, run_agent_export


def reply(content, finish_reason="stop"):
    return json.dumps({"choices": [{
        "message": {"role": "assistant", "content": content},
        "finish_reason": finish_reason,
    }]}).encode()


class ChatAnywhereTest(unittest.TestCase):
    def test_http_request_uses_environment_and_only_sends_task_to_model(self):
        task = "请导出示例记录"
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key", "CHATANYWHERE_MODEL": "test-model"},
                        clear=True), patch("mcos_chatanywhere.build_opener") as opener:
            opener.return_value.open.return_value = io.BytesIO(reply('{"action":"export_demo_record"}'))
            self.assertEqual(plan_export(task), "export_demo_record")
            request, = opener.return_value.open.call_args.args
            self.assertEqual(request.full_url, ENDPOINT)
            self.assertEqual(request.get_method(), "POST")
            self.assertEqual(request.get_header("Authorization"), "Bearer test-key")
            payload = json.loads(request.data)
            self.assertEqual(payload["model"], "test-model")
            self.assertEqual(payload["messages"][1:], [{"role": "user", "content": task}])
            self.assertEqual(opener.return_value.open.call_args.kwargs["timeout"], 25)
            self.assertIsNone(opener.call_args.args[0].redirect_request(
                request, None, 302, "", {}, "https://example.invalid",
            ))

    def test_endpoint_override_accepts_only_official_chatanywhere_urls(self):
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key",
                                      "CHATANYWHERE_ENDPOINT": MAINLAND_ENDPOINT}, clear=True), \
                patch("mcos_chatanywhere.build_opener") as opener:
            opener.return_value.open.return_value = io.BytesIO(reply('{"action":"clarify"}'))
            self.assertEqual(plan_export("导出"), "clarify")
            self.assertEqual(opener.return_value.open.call_args.args[0].full_url, MAINLAND_ENDPOINT)
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key",
                                      "CHATANYWHERE_ENDPOINT": "https://example.invalid/v1/chat/completions"},
                        clear=True), patch("mcos_chatanywhere.build_opener") as opener:
            with self.assertRaisesRegex(PlannerError, "official ChatAnywhere"):
                plan_export("导出")
            opener.assert_not_called()

    def test_missing_key_fails_before_network(self):
        with patch.dict("os.environ", {}, clear=True), patch("mcos_chatanywhere.build_opener") as opener:
            with self.assertRaisesRegex(PlannerError, "CHATANYWHERE_API_KEY"):
                plan_export("导出示例")
            opener.assert_not_called()

    def test_rejects_unrecognized_commands_extra_fields_and_incomplete_responses(self):
        invalid_bodies = [
            reply('{"action":"shell"}'),
            reply('{"action":"export_demo_record","adb":"tap"}'),
            reply('{"action":"export_demo_record"}', "length"),
            reply("not JSON"),
            b"x" * (MAX_RESPONSE_BYTES + 1),
        ]
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key"}, clear=True):
            for body in invalid_bodies:
                with self.subTest(size=len(body)), patch("mcos_chatanywhere.build_opener") as opener:
                    opener.return_value.open.return_value = io.BytesIO(body)
                    with self.assertRaises(PlannerError):
                        plan_export("导出示例")

    def test_provider_error_does_not_expose_response_body(self):
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key"}, clear=True), \
                patch("mcos_chatanywhere.build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError(ENDPOINT, 401, "Unauthorized", {},
                                                            io.BytesIO(b"echoed secret"))
            with self.assertRaises(PlannerError) as failure:
                plan_export("导出示例")
            self.assertEqual(str(failure.exception), "ChatAnywhere returned HTTP 401.")


    def test_replan_sends_only_the_record_id_and_cannot_substitute_another(self):
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key"}, clear=True), \
                patch("mcos_chatanywhere.build_opener") as opener:
            opener.return_value.open.return_value = io.BytesIO(reply(
                '{"action":"export_located_record","recordId":"demo-002"}',
            ))
            plan_located_export("导出最新已完成巡检", "demo-002")
            request, = opener.return_value.open.call_args.args
            observation = json.loads(json.loads(request.data)["messages"][-1]["content"])
            self.assertEqual(observation, {"tool": "record.latest_completed", "recordId": "demo-002"})
            opener.return_value.open.return_value = io.BytesIO(reply(
                '{"action":"export_located_record","recordId":"demo-001"}',
            ))
            with self.assertRaisesRegex(PlannerError, "observed record"):
                plan_located_export("导出最新已完成巡检", "demo-002")


class AgentExportTest(unittest.TestCase):
    def test_latest_goal_needs_a_valid_second_plan_before_export(self):
        task = "导出最新已完成巡检"
        device = object()
        with patch("mcos_device.plan_export", return_value="lookup_latest_completed"), \
                patch("mcos_device.lookup_latest_record", return_value=("demo-002", "lookup")) as lookup, \
                patch("mcos_device.plan_located_export", side_effect=PlannerError("Wrong record")) as replan, \
                patch("mcos_device.run_export") as execute:
            with self.assertRaisesRegex(PlannerError, "Wrong record"):
                run_agent_export(device, task, 10)
            lookup.assert_called_once_with(device, 10)
            replan.assert_called_once_with(task, "demo-002")
            execute.assert_not_called()

    def test_adb_subprocess_does_not_inherit_provider_key(self):
        with patch.dict("os.environ", {"CHATANYWHERE_API_KEY": "test-key", "PATH": "/local/bin"},
                        clear=True), patch("mcos_device.subprocess.run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = b""
            Device("adb", "sample-device").invoke("get-state")
            self.assertEqual(run.call_args.kwargs["env"], {"PATH": "/local/bin"})

    def test_supported_plan_uses_only_sample_id_and_hands_off_for_phone_approval(self):
        device = object()
        with patch("mcos_device.plan_export", return_value="export_demo_record") as planner, \
                patch("mcos_device.run_export") as execute:
            execute.return_value = {"status": "approval_required", "verified": False, "runId": "original"}
            self.assertEqual(run_agent_export(device, "导出 demo-001", 10), execute.return_value)
            planner.assert_called_once_with("导出 demo-001")
            execute.assert_called_once_with(device, 10, SAMPLE_RECORD_ID, until_approval=True)

    def test_no_device_actions_for_ambiguous_unsupported_or_failed_plan(self):
        for action in ("clarify", "unsupported", PlannerError("Network error")):
            with self.subTest(action=action), patch("mcos_device.plan_export") as planner, \
                    patch("mcos_device.run_export") as execute:
                if isinstance(action, Exception):
                    planner.side_effect = action
                    with self.assertRaises(PlannerError):
                        run_agent_export(object(), "导出", 10)
                elif action == "clarify":
                    planner.return_value = action
                    self.assertEqual(run_agent_export(object(), "导出", 10)["status"], "clarification_required")
                else:
                    planner.return_value = action
                    with self.assertRaises(DeviceError):
                        run_agent_export(object(), "导出", 10)
                execute.assert_not_called()
