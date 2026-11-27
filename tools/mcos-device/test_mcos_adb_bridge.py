import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element

from mcos_adb_bridge import inspect_screen, serve_requests
from mcos_device import PACKAGE


class AdbBridgeTest(unittest.TestCase):
    def test_observation_only_reports_known_states_from_mcos(self):
        device = Mock()
        device.observe.return_value = [
            Element("node", {"text": "MCOS", "package": PACKAGE}),
            Element("node", {"text": "Describe a task", "package": PACKAGE}),
            Element("node", {"text": "Export inspection demo-002?", "package": "another.app"}),
        ]
        self.assertEqual(inspect_screen(device), "agent_workspace")

    def test_new_workspace_recognizes_accessible_controls(self):
        device = Mock()
        for title, label in (("MCOS", "Describe a task"), ("Tasks", "Back to workspace")):
            with self.subTest(title=title):
                device.observe.return_value = [
                    Element("node", {"text": title, "package": PACKAGE}),
                    Element("node", {"content-desc": label, "package": PACKAGE}),
                ]
                self.assertEqual(inspect_screen(device), "agent_workspace")

    def test_only_unanswered_uuid_requests_reach_adb(self):
        device = Mock()
        first = "11111111-1111-4111-8111-111111111111"
        second = "22222222-2222-4222-8222-222222222222"
        device.invoke.return_value = "\n".join([
            f"{first}.request", f"{second}.request", f"{second}.response", "unrecognized.request",
        ])
        with patch("mcos_adb_bridge.inspect_screen", return_value="agent_workspace") as inspect:
            serve_requests(device)
        inspect.assert_called_once_with(device)
        response = device.invoke.call_args.args[1]
        self.assertIn(f"{first}.response", response)
        self.assertNotIn(second, response)
        self.assertNotIn("unrecognized", response)
