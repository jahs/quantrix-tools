import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "skills/managing-quantrix/scripts"))
from qxctl import QxClient, QxError


class ModelSelectionTest(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, {}, clear=True)
        env.start()
        self.addCleanup(env.stop)
        self.open_models = ["A"]
        self.script_requests = []

    def client(self, **kwargs):
        client = QxClient(token="test-token", **kwargs)
        client._http = Mock(side_effect=self.respond)
        return client

    def respond(self, method, path, **kwargs):
        if (method, path) == ("GET", "/models"):
            return [{"id": name, "name": name} for name in self.open_models]
        if method == "POST" and path.startswith("/models/"):
            self.script_requests.append(path)
            return {"result": "ok"}
        self.fail(f"Unexpected request: {method} {path}")

    def test_auto_detection_does_not_change_configured_model(self):
        client = self.client()
        self.assertEqual(client.eval("return 1"), "ok")
        self.assertIsNone(client.model)

    def test_auto_detection_follows_replacement_model(self):
        client = self.client()
        client.eval("return 1")
        self.open_models = ["B"]
        client.eval("return 2")
        self.assertEqual(self.script_requests, ["/models/A/script", "/models/B/script"])

    def test_auto_detection_follows_renamed_model(self):
        client = self.client()
        client.eval("return 1")
        self.open_models = ["Renamed A"]
        client.eval("return 2")
        self.assertEqual(self.script_requests[-1], "/models/Renamed%20A/script")

    def test_opening_second_model_blocks_implicit_evaluation(self):
        client = self.client()
        client.eval("return 1")
        self.open_models = ["A", "B"]
        with self.assertRaises(QxError) as error:
            client.eval("return 2")
        self.assertEqual(error.exception.code, "multiple_models")
        self.assertEqual(self.script_requests, ["/models/A/script"])

    def test_closing_all_models_blocks_implicit_evaluation(self):
        client = self.client()
        client.eval("return 1")
        self.open_models = []
        with self.assertRaises(QxError) as error:
            client.eval("return 2")
        self.assertEqual(error.exception.code, "no_model")
        self.assertEqual(self.script_requests, ["/models/A/script"])

    def test_ambiguity_on_first_call_never_posts_a_script(self):
        self.open_models = ["A", "B"]
        with self.assertRaises(QxError) as error:
            self.client().eval("return 1")
        self.assertEqual(error.exception.code, "multiple_models")
        self.assertEqual(self.script_requests, [])

    def test_explicit_model_persists_without_discovery(self):
        client = self.client(model="Pinned")
        client.eval("return 1")
        self.open_models = []
        client.eval("return 2")
        self.assertEqual(self.script_requests, ["/models/Pinned/script"] * 2)
        self.assertTrue(all(call.args[0] == "POST" for call in client._http.call_args_list))

    def test_environment_model_is_explicit(self):
        with patch.dict(os.environ, {"QX_MODEL": "Pinned"}):
            client = self.client()
        self.open_models = ["A", "B"]
        client.eval("return 1")
        self.assertEqual(self.script_requests, ["/models/Pinned/script"])
        client._http.assert_called_once()

    def test_constructor_model_overrides_environment(self):
        with patch.dict(os.environ, {"QX_MODEL": "Environment"}):
            client = self.client(model="Constructor")
        client.eval("return 1")
        self.assertEqual(self.script_requests, ["/models/Constructor/script"])

    def test_per_call_override_does_not_pin_automatic_selection(self):
        client = self.client()
        client.eval("return 1")
        client.eval("return 2", model="B")
        self.open_models = ["C"]
        client.eval("return 3")
        self.assertEqual(self.script_requests,
                         ["/models/A/script", "/models/B/script", "/models/C/script"])

    def test_per_call_override_preserves_explicit_default(self):
        client = self.client(model="Pinned")
        client.eval("return 1", model="Override")
        client.eval("return 2")
        self.assertEqual(self.script_requests,
                         ["/models/Override/script", "/models/Pinned/script"])

    def test_model_names_are_encoded_as_single_path_segments(self):
        client = self.client()
        names = {
            "Plan A": "Plan%20A",
            "Plan+A": "Plan%2BA",
            "Plan%20A": "Plan%2520A",
            "Plan/A": "Plan%2FA",
            "Plan%2FA": "Plan%252FA",
            "Café": "Caf%C3%A9",
        }
        for name, encoded in names.items():
            with self.subTest(name=name):
                client.eval("return 1", model=name)
                self.assertEqual(self.script_requests[-1], f"/models/{encoded}/script")


if __name__ == "__main__":
    unittest.main()
