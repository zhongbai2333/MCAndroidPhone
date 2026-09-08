"""Local scripted RFB peers: no Android image, QEMU, gRPC, or FFmpeg needed."""
import contextlib
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest import mock

from mcandroid_bridge import qemu, rfb
from mcandroid_bridge.protocol import BridgeServer


def receive(peer, count):
    data = bytearray()
    while len(data) < count:
        part = peer.recv(count - len(data))
        if not part:
            raise EOFError("scripted peer disconnected")
        data.extend(part)
    return bytes(data)


def send_fragmented(peer, data):
    # Deliberately split protocol integers and pixels across writes.
    for offset in range(0, len(data), 3):
        peer.sendall(data[offset:offset + 3])


def rectangle(x, y, width, height, encoding=0, payload=b""):
    return struct.pack("!HHHHi", x, y, width, height, encoding) + payload


def update(*rectangles):
    return struct.pack("!BBH", 0, 0, len(rectangles)) + b"".join(rectangles)


def client_message(peer):
    kind = receive(peer, 1)[0]
    size = {3: 9, 4: 7, 5: 5}[kind]
    return bytes([kind]) + receive(peer, size)


class FakeVnc:
    def __init__(self, script=lambda _peer: None, version=8, size=(4, 4), security=(1,), name_size=None):
        self.script, self.version, self.size, self.security = script, version, size, security
        self.name_size = name_size
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(2)
        self.port = self.listener.getsockname()[1]
        self.peer = None
        self.stop_event = threading.Event()
        self.ready = threading.Event()
        self.errors = []
        self.client_banner = None
        self.pixel_format = None
        self.encodings = None

    def __enter__(self):
        def run():
            try:
                self.peer, address = self.listener.accept()
                assert address[0] == "127.0.0.1"
                self.peer.settimeout(2)
                send_fragmented(self.peer, f"RFB 003.{self.version:03d}\n".encode())
                self.client_banner = receive(self.peer, 12)
                negotiated = self.version if self.version in (7, 8) else 3
                assert self.client_banner == f"RFB 003.{negotiated:03d}\n".encode()
                if negotiated == 3:
                    send_fragmented(self.peer, struct.pack("!I", self.security[0]))
                else:
                    send_fragmented(self.peer, bytes([len(self.security), *self.security]))
                    assert receive(self.peer, 1) == b"\x01"
                    if negotiated == 8:
                        send_fragmented(self.peer, b"\x00" * 4)
                assert receive(self.peer, 1) == b"\x01"
                name = b"scripted QEMU"
                send_fragmented(self.peer, struct.pack("!HH16sI", *self.size, rfb.PIXEL_FORMAT,
                                                       len(name) if self.name_size is None else self.name_size) + name)
                self.pixel_format = receive(self.peer, 20)
                self.encodings = receive(self.peer, 16)
                assert self.pixel_format == b"\x00" * 4 + rfb.PIXEL_FORMAT
                assert self.encodings == struct.pack("!BBHiii", 2, 0, 3, 0, 1, -223)
                self.ready.set()
                self.script(self.peer)
                self.stop_event.wait(3)
            except (EOFError, ConnectionError):
                pass
            except OSError as error:
                if not self.stop_event.is_set():
                    self.errors.append(error)
            except BaseException as error:
                self.errors.append(error)
        self.thread = threading.Thread(target=run, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, exc_type, _value, _traceback):
        self.stop_event.set()
        if self.peer is not None:
            with contextlib.suppress(OSError):
                self.peer.shutdown(socket.SHUT_RDWR)
            self.peer.close()
        self.listener.close()
        self.thread.join(3)
        if exc_type is None:
            if self.thread.is_alive():
                raise AssertionError("scripted VNC server failed to stop")
            if self.errors:
                raise AssertionError(f"scripted VNC server failed: {self.errors}")


@contextlib.contextmanager
def local_client(width=4, height=4, timeout=.1):
    first, second = socket.socketpair()
    first.settimeout(min(.02, timeout))
    second.settimeout(1)
    client = rfb.RfbClient(timeout=timeout)
    client.socket = first
    client._resize(width, height, None)
    try:
        yield client, second
    finally:
        client.close()
        second.close()


class RfbTests(unittest.TestCase):
    def test_fragmented_handshake_versions_and_native_raw_color(self):
        pixels = bytes([11, 22, 33, 0]) * 16
        for version in (3, 7, 8, 9):
            with self.subTest(version=version):
                def script(peer):
                    self.assertEqual(struct.pack("!BBHHHH", 3, 0, 0, 0, 4, 4), receive(peer, 10))
                    send_fragmented(peer, update(rectangle(0, 0, 4, 4, payload=pixels)))

                with FakeVnc(script, version=version) as server:
                    client = rfb.RfbClient(server.port)
                    try:
                        self.assertEqual((4, 4), client.connect())
                        self.assertEqual("scripted QEMU", client.name)
                        client.request_update(False)
                        self.assertEqual((True, False), client.read_update())
                        self.assertEqual(bytes([33, 22, 11]) * 16, client.rgb_frame())
                    finally:
                        client.close()

    def test_connect_is_fixed_to_ipv4_loopback(self):
        with mock.patch.object(rfb.socket, "socket") as factory:
            connection = factory.return_value
            connection.recv_into.side_effect = OSError("stop after address inspection")
            with self.assertRaises(rfb.RfbProtocolError):
                rfb.RfbClient(5999).connect()
            connection.connect.assert_called_once_with(("127.0.0.1", 5999))

    def test_authenticated_vnc_is_rejected_without_sending_credentials(self):
        for version in (3, 7, 8):
            with self.subTest(version=version), FakeVnc(version=version, security=(2,)) as server:
                with self.assertRaisesRegex(rfb.RfbProtocolError, "None security"):
                    rfb.RfbClient(server.port).connect()

    def test_invalid_native_dimensions_and_name_bounds_fail_before_allocation(self):
        for size in ((3, 4), (4, 3), (4098, 4), (0, 4)):
            with self.subTest(size=size), FakeVnc(size=size) as server:
                with self.assertRaises(rfb.RfbProtocolError):
                    rfb.RfbClient(server.port).connect()
        with FakeVnc(name_size=65537) as server:
            with self.assertRaisesRegex(rfb.RfbProtocolError, "name exceeds"):
                rfb.RfbClient(server.port).connect()

    def test_raw_rectangles_and_overlapping_copyrect_preserve_all_pixels(self):
        initial = b"".join(bytes([pixel, pixel + 1, pixel + 2, 0]) for pixel in range(16))
        expected = bytearray(initial)
        for row in range(3):
            expected[((row + 1) * 4 + 1) * 4:((row + 1) * 4 + 4) * 4] = initial[row * 16:row * 16 + 12]
        with local_client() as (client, peer):
            peer.sendall(update(rectangle(0, 0, 4, 4, payload=initial),
                                rectangle(1, 1, 3, 3, 1, struct.pack("!HH", 0, 0))))
            self.assertEqual((True, False), client.read_update())
            self.assertEqual(expected, client.canvas)
            # A later small incremental update must retain all other pixels.
            peer.sendall(update(rectangle(0, 0, 1, 1, payload=b"\x09\x08\x07\x00")))
            client.read_update()
            expected[:4] = b"\x09\x08\x07\x00"
            self.assertEqual(expected, client.canvas)

    def test_resize_releases_pointer_before_callback_and_preserves_top_left(self):
        with local_client() as (client, peer):
            old = bytes(range(64))
            client.canvas[:] = old
            client.touch("DOWN", 1, 1)
            self.assertEqual(struct.pack("!BBHH", 5, 1, 3, 3), receive(peer, 6))

            def reconfigure(width, height):
                self.assertEqual((8, 2), (width, height))
                self.assertEqual((4, 4), (client.width, client.height))
                self.assertEqual(0, client._mask)
                # Bridge retirement may call UP from another worker; no input
                # lock may remain held across the callback.
                finished = threading.Event()
                def release():
                    client.touch("UP", 1, 1)
                    finished.set()
                thread = threading.Thread(target=release, daemon=True)
                thread.start()
                self.assertTrue(finished.wait(.5), "resize callback held the input lock")
                thread.join(.5)

            peer.sendall(update(rectangle(999, 999, 8, 2, -223)))
            self.assertEqual((False, True), client.read_update(reconfigure))
            self.assertEqual(struct.pack("!BBHH", 5, 0, 3, 3) * 2, receive(peer, 12))
            self.assertEqual((8, 2), (client.width, client.height))
            self.assertEqual(old[:16], client.canvas[:16])
            self.assertEqual(old[16:32], client.canvas[32:48])
            self.assertEqual(bytes(16), client.canvas[16:32])
            client.request_update(False)
            self.assertEqual(struct.pack("!BBHHHH", 3, 0, 0, 0, 8, 2), receive(peer, 10))

    def test_malformed_rectangles_unknown_encodings_and_limits(self):
        cases = (
            (update(rectangle(3, 0, 2, 1)), "outside"),
            (update(rectangle(0, 0, 0, 1)), "outside"),
            (update(rectangle(0, 0, 2, 2, 5)), "encoding"),
            (update(rectangle(0, 0, 2, 2, 1, struct.pack("!HH", 3, 3))), "outside"),
            (update(rectangle(0, 0, 3, 2, -223)), "even dimensions"),
            (update(rectangle(0, 0, 6, 6, -223), rectangle(0, 0, 1, 1)), "last rectangle"),
            (struct.pack("!BBH", 0, 0, rfb.MAX_RECTANGLES + 1), "too many rectangles"),
            (b"\x03" + struct.pack("!3xI", rfb.MAX_TEXT_BYTES + 1), "clipboard payload"),
            (b"\x01", "Unsupported VNC server message"),
        )
        for payload, reason in cases:
            with self.subTest(reason=reason, payload=payload), local_client() as (client, peer):
                peer.sendall(payload)
                with self.assertRaisesRegex(rfb.RfbProtocolError, reason):
                    client.read_update()
        with local_client() as (client, peer), mock.patch.object(rfb, "MAX_UPDATE_BYTES", 64):
            peer.sendall(update(rectangle(0, 0, 4, 4)))
            with self.assertRaisesRegex(rfb.RfbProtocolError, "payload limit"):
                client.read_update()

    def test_clipboard_and_bell_are_bounded_and_do_not_hide_next_update(self):
        with local_client() as (client, peer):
            peer.sendall(b"\x02\x03" + struct.pack("!3xI", 4) + b"text" + update())
            self.assertEqual((False, False), client.read_update())

    def test_truncated_frame_disconnect_and_partial_message_timeout(self):
        with local_client() as (client, peer):
            peer.sendall(update(rectangle(0, 0, 4, 4, payload=b"short")))
            peer.shutdown(socket.SHUT_WR)
            with self.assertRaisesRegex(rfb.RfbProtocolError, "disconnected"):
                client.read_update()
        with local_client(timeout=.05) as (client, peer):
            peer.sendall(b"\x00\x00")
            started = time.monotonic()
            with self.assertRaisesRegex(rfb.RfbProtocolError, "timed out"):
                client.read_update()
            self.assertLess(time.monotonic() - started, .5)

    def test_idle_incremental_read_waits_then_close_interrupts(self):
        with local_client(timeout=.03) as (client, _peer):
            finished = threading.Event()
            errors = []
            def read():
                try:
                    client.read_update()
                except rfb.RfbProtocolError as error:
                    errors.append(error)
                finally:
                    finished.set()
            thread = threading.Thread(target=read, daemon=True)
            thread.start()
            self.assertFalse(finished.wait(.12), "idle update incorrectly timed out")
            client.close()
            self.assertTrue(finished.wait(.5), "close did not interrupt pending receive")
            thread.join(.5)
            self.assertEqual(1, len(errors))

    def test_pointer_drag_mask_coordinates_and_disconnect_release(self):
        with local_client() as (client, peer):
            client.touch("DOWN", 0, 0)
            client.touch("MOVE", 1, 1)
            client.touch("UP", .5, .5)
            client.touch("MOVE", -1, 2)
            client.touch("DOWN", 1, 0)
            client.close()
            packets = [struct.unpack("!BBHH", receive(peer, 6)) for _ in range(6)]
            self.assertEqual([(5, 1, 0, 0), (5, 1, 3, 3), (5, 0, 2, 2),
                              (5, 0, 0, 3), (5, 1, 3, 0), (5, 0, 3, 0)], packets)

    def test_key_chord_attempts_all_key_ups_if_a_key_down_fails(self):
        with local_client() as (client, _peer):
            with mock.patch.object(client, "_send", side_effect=(None, rfb.RfbProtocolError("failed"), None, None)) as send:
                with self.assertRaisesRegex(rfb.RfbProtocolError, "failed"):
                    client.key_chord((0xffe9, 0xff09))
                self.assertEqual([(4, 1, 0, 0xffe9), (4, 1, 0, 0xff09),
                                  (4, 0, 0, 0xff09), (4, 0, 0, 0xffe9)],
                                 [struct.unpack("!BBHI", call.args[0]) for call in send.call_args_list])
            self.assertEqual(set(), client._pressed_keys)

    def test_ambiguous_pointer_send_still_releases_on_close(self):
        with local_client() as (client, _peer):
            with mock.patch.object(client, "_send", side_effect=(rfb.RfbProtocolError("send timed out"), None)) as send:
                with self.assertRaisesRegex(rfb.RfbProtocolError, "send timed out"):
                    client.touch("DOWN", 1, 1)
                client.close()
                self.assertEqual([struct.pack("!BBHH", 5, 1, 3, 3), struct.pack("!BBHH", 5, 0, 3, 3)],
                                 [call.args[0] for call in send.call_args_list])

    def test_failed_pointer_release_is_retried_on_close(self):
        with local_client() as (client, peer):
            client.touch("DOWN", 0, 0)
            receive(peer, 6)
            with mock.patch.object(client, "_send", side_effect=(rfb.RfbProtocolError("send timed out"), None)) as send:
                with self.assertRaises(rfb.RfbProtocolError):
                    client.release_pointer()
                client.close()
                self.assertEqual([struct.pack("!BBHH", 5, 0, 0, 0)] * 2,
                                 [call.args[0] for call in send.call_args_list])


class MockConverter:
    instances = []

    def __init__(self, executable, width, height, fps, publish, input_format="rgb24"):
        self.width, self.height, self.publish = width, height, publish
        self.input_format = input_format
        self.closed, self.frames = False, []
        self.index = len(self.instances) + 1
        self.instances.append(self)

    def submit(self, rgb):
        assert not self.closed
        assert len(rgb) == self.width * self.height * 3
        self.frames.append(bytes(rgb))
        self.publish(bytes([self.index]) * (self.width * self.height * 3 // 2), time.monotonic_ns())

    def check_health(self):
        pass

    def close(self):
        self.closed = True
        # Emulate delayed converter output while being retired after resize.
        self.publish(bytes([239]) * (self.width * self.height * 3 // 2), time.monotonic_ns())


class BackendTests(unittest.TestCase):
    def setUp(self):
        MockConverter.instances = []
        self.converters = mock.patch.object(qemu, "FFmpegConverter", MockConverter)
        self.converters.start()

    def tearDown(self):
        self.converters.stop()

    def wait_for(self, predicate, message):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate():
                return
            threading.Event().wait(.005)
        self.fail(message)

    def test_native_resize_converter_epoch_and_full_refresh(self):
        allow_resize = threading.Event()
        messages = []
        def next_request(peer):
            while True:
                packet = client_message(peer)
                messages.append(packet)
                if packet[0] == 3:
                    return packet

        def script(peer):
            self.assertEqual(struct.pack("!BBHHHH", 3, 0, 0, 0, 4, 4), next_request(peer))
            send_fragmented(peer, update(rectangle(0, 0, 4, 4, payload=bytes([0, 0, 255, 0]) * 16)))
            self.assertEqual(1, next_request(peer)[1])
            self.assertTrue(allow_resize.wait(2))
            send_fragmented(peer, update(rectangle(0, 0, 8, 2, -223)))
            self.assertEqual(struct.pack("!BBHHHH", 3, 0, 0, 0, 8, 2), next_request(peer))
            send_fragmented(peer, update(rectangle(0, 0, 8, 2, payload=bytes([0, 255, 0, 0]) * 16)))

        with FakeVnc(script) as vnc, tempfile.TemporaryDirectory() as runtime:
            backend = qemu.QemuBackend(640, 480, 30, vnc.port, "mock-ffmpeg")
            bridge = BridgeServer(runtime, backend, 640, 480, port=0)
            backend.set_resize_handler(bridge.reconfigure)
            try:
                backend.start(bridge.publish)
                self.assertTrue(backend.first_frame.wait(2))
                self.assertEqual((4, 4), (bridge.width, bridge.height))
                self.assertEqual(bytes([1]) * 24, bridge.latest_frame[0])
                backend.touch("DOWN", 1, 1)
                allow_resize.set()
                self.wait_for(lambda: backend.frames_received >= 2, "resized native frame was not published")
                backend.check_health()
                self.assertEqual((8, 2), (bridge.width, bridge.height))
                self.assertEqual((8, 2), (backend.width, backend.height))
                self.assertEqual(2, len(MockConverter.instances))
                old, new = MockConverter.instances
                self.assertEqual(("rgb24", "rgb24"), (old.input_format, new.input_format))
                self.assertTrue(old.closed)
                self.assertEqual(bytes([255, 0, 0]) * 16, old.frames[0])
                self.assertEqual(bytes([0, 255, 0]) * 16, new.frames[0])
                self.assertEqual(bytes([2]) * 24, bridge.latest_frame[0])
                self.assertFalse(old.publish(bytes([99]) * 24, 1))
                self.assertEqual(bytes([2]) * 24, bridge.latest_frame[0])
                self.assertIn(struct.pack("!BBHH", 5, 1, 3, 3), messages)
                self.assertIn(struct.pack("!BBHH", 5, 0, 3, 3), messages)
            finally:
                allow_resize.set()
                backend.stop()
                bridge.close()
            self.assertFalse(backend.thread.is_alive())
            self.assertTrue(all(converter.closed for converter in MockConverter.instances))

    def test_bgr_compatibility_survives_native_resize(self):
        backend = qemu.QemuBackend(4, 4, 30, color_order="bgr")
        backend.set_resize_handler(lambda _width, _height: lambda _frame, _timestamp: None)
        try:
            backend._resize(4, 4)
            backend._resize(8, 2)
            self.assertEqual(["bgr24", "bgr24"], [c.input_format for c in MockConverter.instances])
            self.assertTrue(MockConverter.instances[0].closed)
        finally:
            backend.stop()

    def test_invalid_color_order_is_rejected_before_converter_start(self):
        for order in ("auto", "RGB", "rgba", None):
            with self.subTest(order=order), self.assertRaisesRegex(ValueError, "color order"):
                qemu.QemuBackend(4, 4, 30, color_order=order)
        self.assertEqual([], MockConverter.instances)

    @contextlib.contextmanager
    def touchscreen(self):
        backend = qemu.QemuBackend(4, 4, 30, qmp_port=4444, input_mode="touchscreen")
        backend.rfb = mock.Mock()
        with mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            try:
                yield backend, factory
            finally:
                backend.stop()

    def test_touchscreen_qmp_wire_contract_and_contact_identity(self):
        with self.touchscreen() as (backend, factory):
            backend.touch("DOWN", 0, 1)
            backend.touch("MOVE", .25, .75)
            backend.touch("UP", 1, 0)
            backend.touch("DOWN", .5, .5)
            backend.touch("UP", .5, .5)
            expected = [
                [
                    {"type": "mtt", "data": {"type": "begin", "slot": 0, "tracking-id": 0, "axis": "x", "value": 0}},
                    {"type": "btn", "data": {"button": "touch", "down": True}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 0, "axis": "x", "value": 0}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 0, "axis": "y", "value": 32767}},
                ],
                [
                    {"type": "mtt", "data": {"type": "update", "slot": 0, "tracking-id": 0, "axis": "x", "value": 0}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 0, "axis": "x", "value": 8192}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 0, "axis": "y", "value": 24575}},
                ],
                [
                    {"type": "mtt", "data": {"type": "end", "slot": 0, "tracking-id": -1, "axis": "x", "value": 0}},
                    {"type": "btn", "data": {"button": "touch", "down": False}},
                ],
                [
                    {"type": "mtt", "data": {"type": "begin", "slot": 0, "tracking-id": 1, "axis": "x", "value": 0}},
                    {"type": "btn", "data": {"button": "touch", "down": True}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 1, "axis": "x", "value": 16384}},
                    {"type": "mtt", "data": {"type": "data", "slot": 0, "tracking-id": 1, "axis": "y", "value": 16384}},
                ],
                [
                    {"type": "mtt", "data": {"type": "end", "slot": 0, "tracking-id": -1, "axis": "x", "value": 0}},
                    {"type": "btn", "data": {"button": "touch", "down": False}},
                ],
            ]
            self.assertEqual(
                [mock.call("input-send-event", {"device": "phone-display", "events": events}) for events in expected],
                factory.return_value.execute.call_args_list)
            self.assertEqual([mock.call(4444, timeout=2)] * 5, factory.call_args_list)
            self.assertEqual(5, factory.return_value.connect.call_count)
            self.assertEqual(5, factory.return_value.close.call_count)
            backend.rfb.touch.assert_not_called()
            backend.rfb.release_pointer.assert_not_called()

    def test_touchscreen_validates_mode_coordinates_and_contact_sequence(self):
        for mode in (None, "", "tablet", "TOUCHSCREEN"):
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "input mode"):
                qemu.QemuBackend(4, 4, 30, input_mode=mode)
        with self.assertRaisesRegex(ValueError, "--qmp-port"):
            qemu.QemuBackend(4, 4, 30, input_mode="touchscreen")
        with self.touchscreen() as (backend, factory):
            invalid = [("CANCEL", .5, .5), ("down", .5, .5), ("DOWN", -.01, .5),
                       ("DOWN", .5, 1.01), ("MOVE", float("nan"), .5),
                       ("UP", .5, float("inf")), ("DOWN", float("-inf"), .5)]
            for action, u, v in invalid:
                with self.subTest(action=action, u=u, v=v), self.assertRaisesRegex(ValueError, "normalized"):
                    backend.touch(action, u, v)
            with self.assertRaisesRegex(ValueError, "requires DOWN"):
                backend.touch("MOVE", .5, .5)
            # Bridge retirement may send a redundant UP; it must be harmless.
            backend.touch("UP", .5, .5)
            factory.assert_not_called()
            backend.touch("DOWN", .5, .5)
            with self.assertRaisesRegex(ValueError, "already down"):
                backend.touch("DOWN", .5, .5)
            self.assertEqual(1, factory.return_value.execute.call_count)
            backend.touch("UP", .5, .5)
            backend.touch("UP", .5, .5)
            self.assertEqual(2, factory.return_value.execute.call_count)
            backend.rfb.touch.assert_not_called()

    def test_touchscreen_stop_releases_contact_once_and_rejects_later_input(self):
        with self.touchscreen() as (backend, factory):
            backend.touch("DOWN", .5, .5)
            backend.stop()
            backend.stop()
            self.assertEqual(2, factory.return_value.execute.call_count)
            self.assertEqual(
                {"device": "phone-display", "events": [
                    {"type": "mtt", "data": {"type": "end", "slot": 0, "tracking-id": -1, "axis": "x", "value": 0}},
                    {"type": "btn", "data": {"button": "touch", "down": False}},
                ]}, factory.return_value.execute.call_args.args[1])
            for action in ("DOWN", "MOVE", "UP"):
                with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "stopped"):
                    backend.touch(action, .5, .5)
            self.assertEqual(2, factory.return_value.close.call_count)
            backend.rfb.touch.assert_not_called()

    def test_touchscreen_ambiguous_down_is_released_after_connect_or_execute_failure(self):
        for operation in ("connect", "execute"):
            with self.subTest(operation=operation), self.touchscreen() as (backend, factory):
                failed, cleanup = mock.Mock(), mock.Mock()
                getattr(failed, operation).side_effect = ConnectionError("uncertain DOWN")
                factory.side_effect = (failed, cleanup)
                with self.assertRaisesRegex(ConnectionError, "uncertain DOWN"):
                    backend.touch("DOWN", 1, 1)
                failed.close.assert_called_once_with()
                backend.stop()
                cleanup.execute.assert_called_once_with("input-send-event", {
                    "device": "phone-display", "events": [
                        {"type": "mtt", "data": {"type": "end", "slot": 0, "tracking-id": -1, "axis": "x", "value": 0}},
                        {"type": "btn", "data": {"button": "touch", "down": False}},
                    ]})
                cleanup.close.assert_called_once_with()
                self.assertEqual(2, factory.call_count)

    def test_touchscreen_failed_up_is_retried_by_up_or_stop(self):
        for cleanup_action in ("UP", "stop"):
            with self.subTest(cleanup_action=cleanup_action), self.touchscreen() as (backend, factory):
                controller = factory.return_value
                controller.execute.side_effect = (None, ConnectionError("uncertain UP"), None)
                backend.touch("DOWN", .2, .8)
                with self.assertRaisesRegex(ConnectionError, "uncertain UP"):
                    backend.touch("UP", .2, .8)
                # Lost video must reject new contacts without blocking release
                # of one whose delivery to QEMU is still uncertain.
                backend.error = ConnectionError("lost framebuffer")
                if cleanup_action == "UP":
                    backend.touch("UP", .2, .8)
                else:
                    backend.stop()
                self.assertEqual(controller.execute.call_args_list[1], controller.execute.call_args_list[2])
                self.assertEqual(3, controller.close.call_count)
                backend.stop()
                self.assertEqual(3, controller.execute.call_count)

    def test_touchscreen_failed_best_effort_release_remains_pending_for_next_stop(self):
        with self.touchscreen() as (backend, factory):
            controller = factory.return_value
            controller.execute.side_effect = (None, ConnectionError("monitor temporarily unavailable"), None)
            backend.touch("DOWN", 0, 0)
            backend.stop()
            backend.stop()
            self.assertEqual(3, controller.execute.call_count)
            self.assertEqual(controller.execute.call_args_list[1], controller.execute.call_args_list[2])
            self.assertEqual(3, controller.close.call_count)

    def test_touchscreen_resize_releases_old_contact_and_retires_converter(self):
        with self.touchscreen() as (backend, factory):
            publish = mock.Mock()
            backend.set_resize_handler(lambda _width, _height: publish)
            backend._resize(4, 4)
            old = backend.converter
            backend.touch("DOWN", 1, 1)
            backend._resize(8, 2)
            self.assertEqual((8, 2), (backend.width, backend.height))
            self.assertTrue(old.closed)
            self.assertEqual(2, factory.return_value.execute.call_count)
            self.assertEqual("end", factory.return_value.execute.call_args.args[1]["events"][0]["data"]["type"])
            with self.assertRaisesRegex(ValueError, "requires DOWN"):
                backend.touch("MOVE", .5, .5)
            self.assertFalse(old.publish(bytes(24), 1))
            backend.touch("DOWN", .5, .5)
            self.assertEqual(1, factory.return_value.execute.call_args.args[1]["events"][0]["data"]["tracking-id"])

    def test_touchscreen_resize_callback_can_drain_input_from_another_thread(self):
        with self.touchscreen() as (backend, _factory):
            backend.touch("DOWN", .5, .5)
            completed, errors = threading.Event(), []

            def release():
                try:
                    backend.touch("UP", .5, .5)
                except BaseException as error:
                    errors.append(error)
                finally:
                    completed.set()

            def reconfigure(_width, _height):
                thread = threading.Thread(target=release, daemon=True)
                thread.start()
                self.assertTrue(completed.wait(1), "resize callback held a lock needed by input draining")
                thread.join(1)
                return lambda _frame, _timestamp: None

            backend.set_resize_handler(reconfigure)
            backend._resize(8, 2)
            self.assertEqual([], errors)

    def test_touchscreen_vnc_disconnect_releases_contact_without_rfb_pointer_packets(self):
        disconnect, requested = threading.Event(), threading.Event()
        received = []

        def script(peer):
            received.append(client_message(peer))
            send_fragmented(peer, update(rectangle(0, 0, 4, 4, payload=bytes(64))))
            received.append(client_message(peer))
            requested.set()
            self.assertTrue(disconnect.wait(2))
            peer.shutdown(socket.SHUT_RDWR)

        with FakeVnc(script) as server, mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            backend = qemu.QemuBackend(4, 4, 30, server.port, qmp_port=4444, input_mode="touchscreen")
            try:
                backend.start(lambda _frame, _timestamp: None)
                self.assertTrue(backend.first_frame.wait(1))
                self.assertTrue(requested.wait(1))
                backend.touch("DOWN", .25, .75)
                disconnect.set()
                backend.thread.join(2)
                self.assertFalse(backend.thread.is_alive(), "disconnect did not finish touch cleanup")
                with self.assertRaisesRegex(RuntimeError, "disconnected"):
                    backend.check_health()
                self.assertEqual(2, factory.return_value.execute.call_count)
                self.assertEqual("end", factory.return_value.execute.call_args.args[1]["events"][0]["data"]["type"])
                self.assertTrue(all(packet[0] == 3 for packet in received))
                self.assertTrue(all(converter.closed for converter in MockConverter.instances))
                with self.assertRaisesRegex(RuntimeError, "failed|disconnected|stopped"):
                    backend.touch("DOWN", .5, .5)
                self.assertEqual(2, factory.return_value.execute.call_count)
            finally:
                disconnect.set()
                backend.stop()
            self.assertEqual(2, factory.return_value.execute.call_count)

    def test_pull_rate_and_all_rectangles_are_applied_before_conversion(self):
        request_times = []
        def script(peer):
            for number in range(3):
                self.assertEqual(3, client_message(peer)[0])
                request_times.append(time.monotonic())
                send_fragmented(peer, update(rectangle(0, 0, 2, 4, payload=bytes([0, 0, number + 1, 0]) * 8),
                                             rectangle(2, 0, 2, 4, payload=bytes([0, number + 1, 0, 0]) * 8)))

        with FakeVnc(script) as server:
            backend = qemu.QemuBackend(4, 4, 20, server.port)
            try:
                backend.start(lambda _frame, _timestamp: None)
                self.wait_for(lambda: backend.frames_received == 3, "three updates were not received")
                frames = MockConverter.instances[0].frames
                self.assertEqual(3, len(frames))
                for index, rgb in enumerate(frames, 1):
                    expected_row = bytes([index, 0, 0]) * 2 + bytes([0, index, 0]) * 2
                    self.assertEqual(expected_row * 4, rgb)
                self.assertTrue(all(right - left >= .045 for left, right in zip(request_times, request_times[1:])))
            finally:
                backend.stop()

    def test_stop_interrupts_pending_frame_and_does_not_report_expected_disconnect(self):
        pending = threading.Event()
        def script(peer):
            self.assertEqual(3, client_message(peer)[0])
            pending.set()
        with FakeVnc(script) as server:
            backend = qemu.QemuBackend(4, 4, 30, server.port)
            backend.start(lambda _frame, _timestamp: None)
            try:
                self.assertTrue(pending.wait(1))
                started = time.monotonic()
                backend.stop()
                self.assertLess(time.monotonic() - started, 1)
                self.assertFalse(backend.thread.is_alive())
                self.assertFalse(backend.first_frame.is_set())
                backend.check_health()
            finally:
                backend.stop()

    def test_protocol_failure_propagates_to_health(self):
        def script(peer):
            client_message(peer)
            peer.sendall(b"\xff")
        with FakeVnc(script) as server:
            backend = qemu.QemuBackend(4, 4, 30, server.port)
            try:
                backend.start(lambda _frame, _timestamp: None)
                self.wait_for(lambda: backend.error is not None, "protocol failure was not retained")
                with self.assertRaisesRegex(RuntimeError, "Unsupported VNC server message"):
                    backend.check_health()
            finally:
                backend.stop()

    def test_initial_full_frame_has_a_deadline(self):
        with FakeVnc() as server:
            backend = qemu.QemuBackend(4, 4, 30, server.port)
            backend.rfb.timeout = .05
            publish = mock.Mock()
            try:
                backend.start(publish)
                self.wait_for(lambda: backend.error is not None, "initial full-frame deadline was not enforced")
                backend.thread.join(1)
                self.assertFalse(backend.thread.is_alive(), "failed frame worker did not finish cleanup")
                with self.assertRaisesRegex(RuntimeError, "timed out"):
                    backend.check_health()
                self.assertFalse(backend.first_frame.is_set())
                publish.assert_not_called()
            finally:
                backend.stop()

    def test_qmp_connects_on_start_and_closes_after_vnc_failure(self):
        with FakeVnc(size=(8, 4)) as server, mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            backend = qemu.QemuBackend(4, 4, 30, server.port, qmp_port=4444)
            with self.assertRaisesRegex(RuntimeError, "resize handler"):
                backend.start(lambda _frame, _timestamp: None)
            factory.assert_called_once_with(4444, timeout=2)
            factory.return_value.connect.assert_called_once_with()
            factory.return_value.close.assert_called_once_with()
            factory.return_value.execute.assert_not_called()

    def test_qmp_startup_probe_releases_monitor_before_vnc_connects(self):
        with FakeVnc() as server, mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            backend = qemu.QemuBackend(4, 4, 30, server.port, qmp_port=4444)
            connect_vnc = backend.rfb.connect

            def checked_connect(handler):
                factory.return_value.close.assert_called_once_with()
                return connect_vnc(handler)

            try:
                with mock.patch.object(backend.rfb, "connect", side_effect=checked_connect):
                    backend.start(lambda _frame, _timestamp: None)
                factory.assert_called_once_with(4444, timeout=2)
                factory.return_value.execute.assert_not_called()
            finally:
                backend.stop()
            factory.return_value.close.assert_called_once_with()

    def test_qmp_connection_failure_does_not_leave_vnc_or_converter_running(self):
        with mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            factory.return_value.connect.side_effect = ConnectionError("QMP unavailable")
            backend = qemu.QemuBackend(4, 4, 30, qmp_port=4444)
            with self.assertRaisesRegex(ConnectionError, "QMP unavailable"):
                backend.start(lambda _frame, _timestamp: None)
            factory.return_value.close.assert_called_once_with()
            self.assertIsNone(backend.rfb.socket)
            self.assertIsNone(backend.thread)
            self.assertEqual([], MockConverter.instances)

    def test_mismatched_native_size_requires_handler_instead_of_scaling(self):
        with FakeVnc(size=(8, 4)) as server:
            backend = qemu.QemuBackend(4, 4, 30, server.port)
            with self.assertRaisesRegex(RuntimeError, "resize handler"):
                backend.start(lambda _frame, _timestamp: None)
            self.assertEqual([], MockConverter.instances)

    def test_navigation_and_ascii_validation(self):
        backend = qemu.QemuBackend(4, 4, 30)
        backend.rfb = mock.Mock()
        with self.assertRaisesRegex(RuntimeError, "--qmp-port"):
            backend.key("HOME")
        backend.qmp_port = 4444
        controllers = [mock.Mock() for _ in range(3)]
        with mock.patch("mcandroid_bridge.qmp.QmpClient", side_effect=controllers) as factory:
            for name in ("BACK", "HOME", "APP_SWITCH"):
                backend.key(name)
            self.assertEqual([mock.call(4444, timeout=2)] * 3, factory.call_args_list)
        for controller in controllers:
            controller.connect.assert_called_once_with()
            controller.close.assert_called_once_with()
        self.assertEqual([
            mock.call("send-key", {"keys": [{"type": "qcode", "data": "ac_back"}], "hold-time": 80}),
            mock.call("send-key", {"keys": [{"type": "qcode", "data": "ac_home"}], "hold-time": 80}),
            mock.call("send-key", {"keys": [{"type": "qcode", "data": "alt"},
                                          {"type": "qcode", "data": "tab"}], "hold-time": 80}),
        ], [controller.execute.call_args for controller in controllers])
        backend.rfb.key_chord.assert_not_called()
        for value in ("中文", "\n", "a" * 257):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "printable ASCII"):
                backend.text(value)
        backend.rfb.key_chord.assert_not_called()
        backend.text("Ab!")
        self.assertEqual([mock.call((65,)), mock.call((98,)), mock.call((33,))], backend.rfb.key_chord.call_args_list)

    def test_navigation_failure_closes_monitor_and_allows_next_transaction(self):
        for operation in ("connect", "execute"):
            with self.subTest(operation=operation):
                backend = qemu.QemuBackend(4, 4, 30, qmp_port=4444)
                failed, succeeding = mock.Mock(), mock.Mock()
                getattr(failed, operation).side_effect = ConnectionError("monitor unavailable")
                with mock.patch("mcandroid_bridge.qmp.QmpClient", side_effect=(failed, succeeding)):
                    with self.assertRaisesRegex(ConnectionError, "monitor unavailable"):
                        backend.key("BACK")
                    failed.close.assert_called_once_with()
                    backend.check_health()
                    backend.key("HOME")
                    succeeding.connect.assert_called_once_with()
                    succeeding.execute.assert_called_once()
                    succeeding.close.assert_called_once_with()
                backend.stop()

    def test_stop_waits_for_active_qmp_transaction_and_rejects_later_keys(self):
        backend = qemu.QemuBackend(4, 4, 30, qmp_port=4444)
        entered, release, stopped = threading.Event(), threading.Event(), threading.Event()
        errors = []

        def execute(*_args):
            entered.set()
            if not release.wait(2):
                raise TimeoutError("test did not release QMP command")

        def navigate():
            try:
                backend.key("HOME")
            except BaseException as error:
                errors.append(error)

        def stop():
            backend.stop()
            stopped.set()

        with mock.patch("mcandroid_bridge.qmp.QmpClient") as factory:
            controller = factory.return_value
            controller.execute.side_effect = execute
            key_thread = threading.Thread(target=navigate, daemon=True)
            stop_thread = threading.Thread(target=stop, daemon=True)
            key_thread.start()
            try:
                self.assertTrue(entered.wait(1))
                stop_thread.start()
                self.assertTrue(backend.stop_event.wait(1))
                self.assertFalse(stopped.wait(.03), "stop returned before its QMP connection closed")
                controller.close.assert_not_called()
            finally:
                release.set()
                key_thread.join(1)
                if stop_thread.ident is not None:
                    stop_thread.join(1)
                backend.stop()
            self.assertFalse(key_thread.is_alive())
            self.assertFalse(stop_thread.is_alive())
            self.assertEqual([], errors)
            controller.close.assert_called_once_with()
            with self.assertRaisesRegex(RuntimeError, "stopped"):
                backend.key("BACK")
            factory.assert_called_once_with(4444, timeout=2)


if __name__ == "__main__":
    unittest.main()
