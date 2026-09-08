import base64
import mmap
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest

from mcandroid_bridge.pattern import PatternBackend
from mcandroid_bridge.protocol import BridgeServer, MAX_LINE, ProtocolError, Session, lines


class RecordingBackend:
    def __init__(self):
        self.events = []

    def touch(self, *values):
        self.events.append(values)

    def key(self, value):
        self.events.append(("KEY", value))

    def text(self, value):
        self.events.append(("TEXT", value))

    def start(self, publish):
        self.publish = publish

    def stop(self):
        pass

    def check_health(self):
        pass


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.writer, self.reader = socket.socketpair()
        self.backend = RecordingBackend()
        self.session = Session(self.writer, self.temp.name, 4, 4, self.backend)

    def tearDown(self):
        self.session.close()
        self.writer.close()
        self.reader.close()
        self.temp.cleanup()

    def test_three_leases_drop_without_overwrite_and_ack_reuses_only_that_slot(self):
        for value in (1, 2, 3):
            self.assertTrue(self.session.publish(bytes([value]) * 24))
        before = self.session.mapping[:]
        for _ in range(100):
            self.assertFalse(self.session.publish(bytes([99]) * 24))
        self.assertEqual(before, self.session.mapping[:])
        self.assertEqual(100, self.session.dropped)
        self.session.command(["ACK", "2", "1"])
        self.assertTrue(self.session.publish(bytes([7]) * 24))
        self.assertEqual(bytes([1]) * 24, self.session.mapping[:24])
        self.assertEqual(bytes([7]) * 24, self.session.mapping[24:48])
        self.assertEqual(bytes([3]) * 24, self.session.mapping[48:])
        with self.assertRaises(ProtocolError):
            self.session.command(["ACK", "2", "1"])

    def test_cross_mapping_snapshot_stays_immutable_until_ack(self):
        with self.session.path.open("rb") as handle:
            mapping = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
            try:
                expected = bytes(range(24))
                self.session.publish(expected)
                self.session.publish(bytes([50]) * 24)
                self.session.publish(bytes([60]) * 24)
                for _ in range(100):
                    self.session.publish(bytes([100]) * 24)
                    self.assertEqual(expected, mapping[:24])
                owned = bytes(mapping[:24])
                self.session.command(["ACK", "1", "0"])
                self.session.publish(bytes([200]) * 24)
                self.assertEqual(expected, owned)
                self.assertNotEqual(expected, mapping[:24])
            finally:
                mapping.close()

    def test_invalid_input_never_reaches_backend(self):
        for fields in (["TOUCH", "DOWN", "nan", "0"], ["TOUCH", "DOWN", "0", "inf"],
                       ["TOUCH", "DOWN", "1.1", "0"], ["TOUCH", "MOVE", "0", "0"],
                       ["TOUCH", "UP", "0", "0"], ["KEY", "POWER"], ["TEXT", "%%%"],
                       ["ACK", "0", "-1"]):
            with self.assertRaises(ProtocolError):
                self.session.command(fields)
        self.assertEqual([], self.backend.events)

    def test_disconnect_releases_last_valid_touch_once(self):
        self.session.command(["TOUCH", "DOWN", "0.25", "0.75"])
        self.session.command(["TOUCH", "MOVE", "0.5", "0.3"])
        self.session.close()
        self.session.close()
        self.assertEqual([("DOWN", 0.25, 0.75), ("MOVE", 0.5, 0.3), ("UP", 0.5, 0.3)], self.backend.events)
        self.assertFalse(self.session.path.exists())

    def test_fragmented_lines_and_line_limit(self):
        stop = threading.Event()
        self.writer.sendall(b"TOUCH\tDOWN\t0.")
        self.writer.sendall(b"5\t0.4\nKEY\tHOME\n")
        reader = lines(self.reader, stop)
        self.assertEqual(["TOUCH", "DOWN", "0.5", "0.4"], next(reader))
        self.assertEqual(["KEY", "HOME"], next(reader))
        # Darwin socketpair buffers can be smaller than MAX_LINE. Feed and read
        # concurrently so this tests the parser limit rather than OS buffer capacity.
        errors=[]
        def oversized_line():
            try:self.writer.sendall(b"x" * (MAX_LINE + 1))
            except Exception as error:errors.append(error)
        sender=threading.Thread(target=oversized_line,daemon=True)
        self.writer.settimeout(3)
        sender.start()
        try:
            with self.assertRaises(ProtocolError):next(reader)
        finally:
            sender.join(4)
        self.assertFalse(sender.is_alive())
        self.assertEqual([],errors)

    def test_pattern_responds_to_touch_navigation_and_text(self):
        pattern = PatternBackend(32, 48, 30)
        first = pattern.frame(0)
        pattern.touch("DOWN", 0.1, 0.1)
        self.assertNotEqual(first, pattern.frame(0))
        touched = pattern.frame(0)
        pattern.key("APP_SWITCH")
        self.assertNotEqual(touched, pattern.frame(0))
        switched = pattern.frame(0)
        pattern.text("test")
        self.assertNotEqual(switched, pattern.frame(0))
        self.assertEqual(32 * 48 * 3 // 2, len(pattern.frame(3)))

    def test_text_accepts_4096_utf8_bytes_and_rejects_larger(self):
        value = "a" * 4096
        self.session.command(["TEXT", base64.b64encode(value.encode()).decode()])
        self.assertEqual(("TEXT", value), self.backend.events[-1])
        with self.assertRaises(ProtocolError):
            self.session.command(["TEXT", base64.b64encode(b"a" * 4097).decode()])


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.backend = RecordingBackend()
        self.server = BridgeServer(self.temp.name, self.backend, 4, 4, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def connect(self, token=None):
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=2)
        file = sock.makefile("rb")
        sock.sendall(f"HELLO\t1\t{token or self.server.token}\n".encode("ascii"))
        return sock, file, file.readline().decode("ascii").strip().split("\t")

    def wait_disconnected(self):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            with self.server.lock:
                if self.server.connection is None:
                    return
            time.sleep(0.005)
        self.fail("session did not disconnect")

    def test_auth_rejection_and_reconnect_new_mapping(self):
        sock, stream, result = self.connect("wrong")
        self.assertEqual(["ERROR", "authentication_failed"], result)
        stream.close()
        sock.close()
        self.wait_disconnected()
        paths = []
        for _ in range(2):
            sock, stream, result = self.connect()
            self.assertEqual("WELCOME", result[0])
            paths.append(Path(base64.b64decode(result[6]).decode("utf-8")))
            self.assertTrue(paths[-1].exists())
            sock.shutdown(socket.SHUT_RDWR)
            stream.close()
            sock.close()
            self.wait_disconnected()
        self.assertNotEqual(paths[0], paths[1])
        self.assertTrue(all(not path.exists() for path in paths))

    def test_single_client_busy_and_socket_disconnect_releases_touch(self):
        sock, stream, result = self.connect()
        other = socket.create_connection(("127.0.0.1", self.server.port), timeout=2)
        self.assertEqual(b"ERROR\tbusy\n", other.recv(100))
        other.close()
        sock.sendall(b"TOUCH\tDOWN\t0.2\t0.3\n")
        deadline = time.monotonic() + 2
        while not self.backend.events and time.monotonic() < deadline:
            time.sleep(0.005)
        sock.shutdown(socket.SHUT_RDWR)
        stream.close()
        sock.close()
        self.wait_disconnected()
        self.assertEqual([("DOWN", 0.2, 0.3), ("UP", 0.2, 0.3)], self.backend.events)

    def test_new_client_receives_latest_static_frame(self):
        self.server.publish(bytes([123]) * 24, 42)
        sock, stream, result = self.connect()
        self.assertEqual("WELCOME", result[0])
        self.assertEqual(b"FRAME\t1\t0\t42\n", stream.readline())
        sock.shutdown(socket.SHUT_RDWR)
        stream.close()
        sock.close()
        self.wait_disconnected()

    def test_reconnect_does_not_overwrite_a_still_open_old_mapping(self):
        sock, stream, result = self.connect()
        old_path = Path(base64.b64decode(result[6]).decode())
        handle = old_path.open("rb")
        mapping = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            self.server.publish(bytes([17]) * 24)
            self.assertEqual(b"FRAME\t1\t0", stream.readline().strip().rsplit(b"\t", 1)[0])
            old_snapshot = mapping[:]
            sock.shutdown(socket.SHUT_RDWR)
            stream.close()
            sock.close()
            self.wait_disconnected()
            sock, stream, result = self.connect()
            new_path = Path(base64.b64decode(result[6]).decode())
            self.assertNotEqual(old_path, new_path)
            self.server.publish(bytes([99]) * 24)
            self.assertEqual(old_snapshot, mapping[:])
            sock.shutdown(socket.SHUT_RDWR)
            stream.close()
            sock.close()
            self.wait_disconnected()
        finally:
            mapping.close()
            handle.close()
        self.server._cleanup()
        self.assertFalse(old_path.exists())


if __name__ == "__main__":
    unittest.main()
