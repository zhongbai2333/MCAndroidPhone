"""Offline regression checks: python -m unittest discover -s scripts/tests."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("emulator_probe", Path(__file__).parents[1] / "emulator-probe.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def snapshot(width=2, height=4, pixels=None, fmt=2, format_dimensions=True):
    return SimpleNamespace(
        format=SimpleNamespace(width=width if format_dimensions else 0,
                               height=height if format_dimensions else 0, format=fmt),
        width=width, height=height,
        image=b"\x00" * (width * height * 3) if pixels is None else pixels,
    )


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def __call__(self):
        return self.now

    def sleep(self, duration):
        self.sleeps.append(duration)
        self.now += duration


class ProbeTests(unittest.TestCase):
    def test_missing_token_is_sanitized(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pid_1.ini"
            path.write_text("grpc.port=8554\n", encoding="utf-8")
            token_reader = Mock(side_effect=ValueError("secret credential details"))
            with self.assertRaises(probe.ProbeError) as raised:
                probe.read_discovery(path, token_reader)
            self.assertIn("no usable Bearer token", str(raised.exception))
            self.assertNotIn("secret", str(raised.exception))
            token_reader.assert_called_once_with(discovery_file=str(path))

    def test_only_numeric_local_port_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pid_1.ini"
            for invalid in ("", "0", "65536", "remote.example:8554", "127.0.0.1:8554", "-1", "8554.0"):
                with self.subTest(port=invalid):
                    path.write_text("grpc.port=" + invalid + "\n", encoding="utf-8")
                    token_reader = Mock(return_value="secret")
                    with self.assertRaises(probe.ProbeError):
                        probe.read_discovery(path, token_reader)
                    token_reader.assert_not_called()
            path.write_text("grpc.port=8554\n", encoding="utf-8")
            self.assertEqual(probe.read_discovery(path, Mock(return_value="secret")), (8554, "secret"))

    def test_native_dimension_fallback(self):
        self.assertEqual(probe.validate_snapshot(snapshot(format_dimensions=False)), (2, 4))

    def test_odd_dimensions_are_rejected(self):
        for width, height in ((3, 4), (2, 3)):
            with self.subTest(width=width, height=height):
                with self.assertRaisesRegex(probe.ProbeError, "both be even"):
                    probe.validate_snapshot(snapshot(width, height))

    def test_malformed_rgb_and_dimensions_are_rejected(self):
        for image in (snapshot(pixels=b"short"), snapshot(fmt=1),
                      snapshot(width=0, pixels=b"x"), snapshot(width=4098, pixels=b"x")):
            with self.subTest(image=image):
                with self.assertRaises(probe.ProbeError):
                    probe.validate_snapshot(image)

    def test_empty_screenshot_and_rpc_error_retry_then_succeed(self):
        class FakeRpcError(Exception):
            pass

        controller = Mock()
        controller.getScreenshot.side_effect = [FakeRpcError("metadata=secret"), snapshot(pixels=b""), snapshot()]
        clock = FakeClock()
        request = object()
        metadata = (("authorization", "Bearer secret"),)
        result = probe.wait_for_snapshot(controller, request, metadata, 8554, 8,
                                         rpc_errors=(FakeRpcError,), clock=clock, sleep=clock.sleep)
        self.assertEqual(result, {"width": 2, "height": 4, "grpcPort": 8554})
        self.assertEqual(controller.getScreenshot.call_count, 3)
        self.assertEqual(clock.now, 1.0)
        for call in controller.getScreenshot.call_args_list:
            self.assertIs(call.args[0], request)
            self.assertEqual(call.kwargs["metadata"], metadata)
            self.assertLessEqual(call.kwargs["timeout"], 5.0)

    def test_timeout_caps_last_rpc_and_sleep(self):
        controller = Mock()
        controller.getScreenshot.return_value = snapshot(pixels=b"")
        clock = FakeClock()
        with self.assertRaisesRegex(probe.ProbeError, "Timed out.*inactive"):
            probe.wait_for_snapshot(controller, None, (), 8554, 0.75, clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.now, 0.75)
        self.assertEqual(clock.sleeps, [0.5, 0.25])
        self.assertEqual([call.kwargs["timeout"] for call in controller.getScreenshot.call_args_list], [0.75, 0.25])

    def test_nonfinite_or_nonpositive_timeout_rejected(self):
        for timeout in (0, -1, float("nan"), float("inf")):
            with self.subTest(timeout=timeout), self.assertRaises(probe.ProbeError):
                probe.wait_for_snapshot(Mock(), None, (), 8554, timeout)

    def test_run_probe_uses_only_loopback_native_size_and_closes_channel(self):
        class FakeRpcError(Exception):
            pass

        grpc = ModuleType("grpc")
        grpc.RpcError = FakeRpcError
        channel = Mock()
        grpc.insecure_channel = Mock(return_value=channel)
        package = ModuleType("mcandroid_bridge")
        package.__path__ = []
        emulator = ModuleType("mcandroid_bridge.emulator")
        emulator.read_token = Mock(return_value="secret")
        wire = ModuleType("mcandroid_bridge.emulator_wire")
        controller = Mock()
        controller.getScreenshot.return_value = snapshot()
        wire.Controller = Mock(return_value=controller)
        wire.ImageFormat = Mock(return_value=object())
        package.emulator_wire = wire
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bridge = root / "bridge" / "mcandroid_bridge"
            bridge.mkdir(parents=True)
            (bridge / "emulator_wire.py").touch()
            discovery = root / "pid_1.ini"
            discovery.write_text("grpc.port=8554\ngrpc.host=remote.example\n", encoding="utf-8")
            with patch.dict(probe.sys.modules, {"grpc": grpc, "mcandroid_bridge": package,
                                               "mcandroid_bridge.emulator": emulator,
                                               "mcandroid_bridge.emulator_wire": wire}), patch.object(probe.sys, "path", list(probe.sys.path)):
                result = probe.run_probe(root, discovery, 5)
        self.assertEqual(result, {"width": 2, "height": 4, "grpcPort": 8554})
        self.assertEqual(grpc.insecure_channel.call_args.args, ("127.0.0.1:8554",))
        wire.ImageFormat.assert_called_once_with(format=2)
        self.assertEqual(controller.getScreenshot.call_args.kwargs["metadata"], (("authorization", "Bearer secret"),))
        channel.close.assert_called_once_with()

    def test_cli_outputs_only_one_success_json_object(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        expected = {"width": 2, "height": 4, "grpcPort": 8554}
        with patch.object(probe, "run_probe", return_value=expected), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exit_code = probe.main(["--project-root", "unused", "--discovery-file", "unused.ini"])
        self.assertEqual(exit_code, 0)
        self.assertEqual(json.loads(stdout.getvalue()), expected)
        self.assertEqual(len(stdout.getvalue().splitlines()), 1)
        self.assertEqual(stderr.getvalue(), "")

    def test_cli_unexpected_errors_never_leak_metadata(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(probe, "run_probe", side_effect=RuntimeError("Bearer secret metadata")), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exit_code = probe.main(["--project-root", "unused", "--discovery-file", "unused.ini"])
        self.assertEqual(exit_code, 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("secret", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
