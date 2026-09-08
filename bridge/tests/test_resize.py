import base64
import mmap
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest

from mcandroid_bridge.protocol import BridgeServer, ProtocolError


class ResizeBackend:
    def __init__(self):
        self.events = []
        self.down_entered = threading.Event()
        self.allow_down = threading.Event()
        self.allow_down.set()

    def start(self, publish):
        self.publish = publish

    def check_health(self):
        pass

    def stop(self):
        self.allow_down.set()

    def touch(self, *event):
        self.events.append(event)
        if event[0] == "DOWN":
            self.down_entered.set()
            if not self.allow_down.wait(3):
                raise RuntimeError("test input timeout")

    def key(self, key):
        self.events.append(("KEY", key))

    def text(self, text):
        self.events.append(("TEXT", text))


class ResizeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.backend = ResizeBackend()
        self.server = BridgeServer(self.temp.name, self.backend, 4, 4, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connections = []

    def tearDown(self):
        self.backend.allow_down.set()
        for sock, stream in self.connections:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()
            sock.close()
        self.server.close()
        self.thread.join(3)
        self.server._cleanup()
        self.temp.cleanup()

    def wait_for(self, predicate, message):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.005)
        self.fail(message)

    def connect(self):
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=2)
        stream = sock.makefile("rb")
        self.connections.append((sock, stream))
        sock.sendall(f"HELLO\t1\t{self.server.token}\n".encode("ascii"))
        welcome = stream.readline().decode("ascii").strip().split("\t")
        self.assertEqual("WELCOME", welcome[0])
        path = Path(base64.b64decode(welcome[6]).decode("utf-8"))
        self.wait_for(lambda: self.server.active is not None, "session did not activate")
        return sock, stream, welcome, path

    def test_resize_reconnects_with_new_mapping_releases_touch_and_ignores_old_frames(self):
        old_publish = self.server.reconfigure(4, 4)
        sock, stream, welcome, old_path = self.connect()
        self.assertEqual(["4", "4", "24"], welcome[2:5])
        old_session = self.server.active
        old_publish(bytes([17]) * 24, 10)
        self.assertEqual(b"FRAME\t1\t0\t10\n", stream.readline())
        with old_path.open("rb") as old_file:
            old_mapping = mmap.mmap(old_file.fileno(), 0, access=mmap.ACCESS_READ)
            try:
                before = old_mapping[:]
                sock.sendall(b"TOUCH\tDOWN\t0.25\t0.75\n")
                self.assertTrue(self.backend.down_entered.wait(2))
                new_publish = self.server.reconfigure(8, 6)
                self.assertEqual([("DOWN", 0.25, 0.75), ("UP", 0.25, 0.75)], self.backend.events)
                self.assertFalse(old_publish(bytes([99]) * 24, 11))
                self.assertFalse(self.server.publish(bytes([88]) * 24, 12))
                self.assertIsNone(self.server.latest_frame)
                self.assertFalse(new_publish(bytes([33]) * 72, 20))
                new_sock, new_stream, new_welcome, new_path = self.connect()
                self.assertEqual(["8", "6", "72"], new_welcome[2:5])
                self.assertNotEqual(old_path, new_path)
                self.assertEqual(72 * 3, new_path.stat().st_size)
                self.assertEqual(b"FRAME\t1\t0\t20\n", new_stream.readline())
                self.assertEqual(bytes([33]) * 72, new_path.read_bytes()[:72])
                self.assertEqual(before, old_mapping[:])
                new_session = self.server.active
                for fields in (["ACK", "1", "0"], ["KEY", "HOME"],
                               ["TOUCH", "DOWN", "0", "0"], ["TEXT", "eA=="]):
                    with self.assertRaises(ProtocolError):
                        old_session.command(fields)
                self.assertEqual(1, new_session.leases[0])
                self.assertEqual(2, len(self.backend.events))
                new_sock.sendall(b"ACK\t1\t0\nTOUCH\tDOWN\t0.5\t0.5\nTOUCH\tUP\t0.5\t0.5\n")
                self.wait_for(lambda: len(self.backend.events) == 4, "fresh input did not reach backend")
                self.assertEqual([("DOWN", 0.5, 0.5), ("UP", 0.5, 0.5)], self.backend.events[-2:])
            finally:
                old_mapping.close()

    def test_same_size_keeps_session_and_epoch_and_validates_uncached_frame_size(self):
        publish = self.server.reconfigure(4, 4)
        with self.assertRaises(ValueError):
            publish(bytes(23))
        self.assertIsNone(self.server.latest_frame)
        sock, stream, _, path = self.connect()
        session = self.server.active
        epoch = self.server.epoch
        same_size_publish = self.server.reconfigure(4, 4)
        self.assertEqual(epoch, self.server.epoch)
        self.assertIs(session, self.server.active)
        self.assertTrue(publish(bytes([1]) * 24, 1))
        self.assertTrue(same_size_publish(bytes([2]) * 24, 2))
        self.assertEqual(b"FRAME\t1\t0\t1\n", stream.readline())
        self.assertEqual(b"FRAME\t2\t1\t2\n", stream.readline())
        self.assertEqual(path, session.path)
        self.assertFalse(session.closed)

    def test_invalid_dimensions_do_not_retire_a_valid_session(self):
        self.connect()
        session = self.server.active
        for width, height in ((3, 4), (4, 1), (4098, 4), (4, 0)):
            with self.assertRaises(ValueError):
                self.server.reconfigure(width, height)
        self.assertIs(session, self.server.active)
        self.assertFalse(session.closed)
        self.assertEqual(0, self.server.epoch)

    def test_late_old_worker_cleanup_cannot_clear_new_session(self):
        self.connect()
        old_session, old_worker = self.server.active, self.server.worker
        entered, allow_cleanup = threading.Event(), threading.Event()
        original_close = old_session.close

        def delayed_close():
            original_close()
            if threading.current_thread() is old_worker:
                entered.set()
                allow_cleanup.wait(3)

        old_session.close = delayed_close
        try:
            publish = self.server.reconfigure(6, 8)
            self.assertTrue(entered.wait(2))
            sock, stream, _, _ = self.connect()
            new_session, new_connection = self.server.active, self.server.connection
            allow_cleanup.set()
            old_worker.join(2)
            self.assertFalse(old_worker.is_alive())
            self.assertIs(new_session, self.server.active)
            self.assertIs(new_connection, self.server.connection)
            self.assertTrue(publish(bytes([3]) * 72, 3))
            self.assertEqual(b"FRAME\t1\t0\t3\n", stream.readline())
        finally:
            allow_cleanup.set()

    def test_resize_waits_for_old_input_then_releases_before_returning(self):
        sock, _, _, _ = self.connect()
        self.backend.allow_down.clear()
        sock.sendall(b"TOUCH\tDOWN\t0.1\t0.2\n")
        self.assertTrue(self.backend.down_entered.wait(2))
        finished = threading.Event()
        errors = []

        def resize():
            try:
                self.server.reconfigure(8, 8)
            except Exception as exc:
                errors.append(exc)
            finally:
                finished.set()

        worker = threading.Thread(target=resize, daemon=True)
        worker.start()
        try:
            self.assertFalse(finished.wait(0.1))
            self.backend.allow_down.set()
            self.assertTrue(finished.wait(2))
            self.assertEqual([], errors)
            self.assertEqual([("DOWN", 0.1, 0.2), ("UP", 0.1, 0.2)], self.backend.events)
        finally:
            self.backend.allow_down.set()
            worker.join(3)

    def test_disconnect_retirement_remains_visible_to_resize_until_touch_released(self):
        sock, _, _, _ = self.connect()
        old_session, old_worker = self.server.active, self.server.worker
        sock.sendall(b"TOUCH\tDOWN\t0.2\t0.3\n")
        self.assertTrue(self.backend.down_entered.wait(2))
        old_entered, resize_entered = threading.Event(), threading.Event()
        allow_close, allow_old_cleanup = threading.Event(), threading.Event()
        resized = threading.Event()
        errors = []
        original_close = old_session.close

        def delayed_close():
            if threading.current_thread() is old_worker:
                old_entered.set()
            else:
                resize_entered.set()
            if not allow_close.wait(3):
                raise RuntimeError("test close gate timed out")
            original_close()
            if threading.current_thread() is old_worker:
                allow_old_cleanup.wait(3)

        def resize():
            try:
                self.server.reconfigure(8, 6)
            except Exception as exc:
                errors.append(exc)
            finally:
                resized.set()

        old_session.close = delayed_close
        resize_worker = threading.Thread(target=resize, daemon=True)
        try:
            sock.shutdown(socket.SHUT_RDWR)
            self.assertTrue(old_entered.wait(2))
            # The disconnected worker has reached close but has not released UP.
            # Resize must still discover and retire that same session.
            resize_worker.start()
            self.assertTrue(resize_entered.wait(2), "resize missed a session whose touch is still active")
            self.assertFalse(resized.wait(0.1), "resize completed before the old touch was released")
            allow_close.set()
            self.assertTrue(resized.wait(2))
            self.assertEqual([], errors)
            self.assertEqual([("DOWN", 0.2, 0.3), ("UP", 0.2, 0.3)], self.backend.events)
            new_sock, _, _, _ = self.connect()
            new_sock.sendall(b"TOUCH\tDOWN\t0.7\t0.8\n")
            self.wait_for(lambda: len(self.backend.events) == 3, "fresh gesture did not start")
            allow_old_cleanup.set()
            old_worker.join(2)
            self.assertFalse(old_worker.is_alive())
            self.assertEqual(("DOWN", 0.7, 0.8), self.backend.events[-1],
                             "old retirement released the new session's gesture")
            self.assertEqual(3, len(self.backend.events))
            new_sock.sendall(b"TOUCH\tUP\t0.7\t0.8\n")
            self.wait_for(lambda: len(self.backend.events) == 4, "fresh gesture did not release")
            self.assertEqual(("UP", 0.7, 0.8), self.backend.events[-1])
        finally:
            allow_close.set()
            allow_old_cleanup.set()
            if resize_worker.ident is not None:
                resize_worker.join(3)
            old_worker.join(3)

    def test_resize_retires_a_connection_that_has_not_authenticated(self):
        sock = socket.create_connection(("127.0.0.1", self.server.port), timeout=2)
        stream = sock.makefile("rb")
        self.connections.append((sock, stream))
        self.wait_for(lambda: self.server.connection is not None, "connection was not accepted")
        publish = self.server.reconfigure(8, 4)
        _, new_stream, welcome, _ = self.connect()
        self.assertEqual(["8", "4", "48"], welcome[2:5])
        self.assertTrue(publish(bytes([9]) * 48, 9))
        self.assertEqual(b"FRAME\t1\t0\t9\n", new_stream.readline())
        self.assertEqual([], self.backend.events)


if __name__ == "__main__":
    unittest.main()
