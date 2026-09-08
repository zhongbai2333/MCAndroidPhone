import socket
import struct
import threading
import time
import unittest

from mcandroid_bridge.dbus_transport import (
    DbusError, MAX_BODY, MAX_HEADER, Peer, Reader, Variant, Writer,
    decode_message, encode_message, signature_types,
)


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("test socket closed")
        data.extend(chunk)
    return bytes(data)


def receive_message(sock):
    fixed = exact(sock, 16)
    body, _, header = struct.unpack(("<" if fixed[0] == 108 else ">") + "III", fixed[4:])
    return decode_message(fixed, exact(sock, header + (-(16 + header) % 8) + body))


class CodecTests(unittest.TestCase):
    def test_big_endian_external_fixture(self):
        # Big-endian METHOD_RETURN, serial 7, replies to 42, body uint32 0x01020304.
        data = bytes.fromhex("42 02 00 01 00000004 00000007 0000000f "
                             "05 01 75 00 0000002a 08 01 67 00 01 75 00 00 01020304")
        result = decode_message(data[:16], data[16:])
        self.assertEqual((result.kind, result.serial, result.headers[5], result.signature, result.body),
                         (2, 7, 42, "u", (0x01020304,)))

    def test_nested_variants_and_array_alignment(self):
        payload = ({"Interfaces": Variant("as", ["org.qemu.Display1.Listener.Win32.Map"]),
                    "Geometry": Variant("(tuu)", (0x100000003, 720, 1280)),
                    "Bytes": Variant("ay", bytes(range(256)))},)
        data = encode_message(2, 7, [(5, Variant("u", 42))], "a{sv}", payload)
        self.assertEqual(decode_message(data[:16], data[16:]).body, payload)

    def test_signed_and_double_values_both_endiannesses(self):
        signature = "ynqiuxtdb"
        values = (254, -32000, 65000, -2147483640, 4294967290,
                  -0x100000001, 0x100000002, 123.75, True)
        for endian in ("<", ">"):
            writer = Writer(endian)
            for node, value in zip(signature_types(signature), values):
                writer.value(node, value)
            reader = Reader(writer.data, endian)
            self.assertEqual(tuple(reader.value(node) for node in signature_types(signature)), values)
            self.assertEqual(reader.position, len(writer.data))

    def test_rejects_invalid_signature_and_arity(self):
        for signature in ("a", "(", "()", "a{u}", "a" * 34 + "u", "z"):
            with self.subTest(signature=signature), self.assertRaises(DbusError):
                signature_types(signature)
        with self.assertRaises(DbusError):
            encode_message(1, 1, [], "u", ())
        with self.assertRaises(DbusError):
            encode_message(1, 1, [], "v", (Variant("uu", (1, 2)),))

    def test_rejects_trailing_or_truncated_message(self):
        data = encode_message(2, 1, [(5, Variant("u", 42))], "s", ("abc",))
        for rest in (data[16:-1], data[16:] + b"garbage"):
            with self.assertRaises(DbusError):
                decode_message(data[:16], rest)

    def test_rejects_unterminated_strings_and_invalid_boolean(self):
        with self.assertRaises(DbusError):
            Reader(b"\x01\0\0\0aX").value(("s",))
        with self.assertRaises(DbusError):
            Reader(b"\x02\0\0\0").value(("b",))
        with self.assertRaises(DbusError):
            Reader(b"\x80\0\0\0").value(signature_types("ay")[0])


class PeerTests(unittest.TestCase):
    def make_peer(self, handler=None, timeout=1):
        local, server = socket.socketpair()
        local.settimeout(2)
        server.settimeout(2)
        self.addCleanup(server.close)
        peer = Peer(local, handler, timeout)
        self.addCleanup(peer.close)
        auth_errors = []

        def auth_server():
            try:
                data = bytearray()
                while not data.endswith(b"\r\n"):
                    data.extend(exact(server, 1))
                self.assertTrue(data.startswith(b"\0AUTH ANONYMOUS "))
                server.sendall(b"OK 0123456789abcdef0123456789abcdef\r\n")
                self.assertEqual(exact(server, 7), b"BEGIN\r\n")
            except BaseException as error:
                auth_errors.append(error)

        worker = threading.Thread(target=auth_server, daemon=True)
        worker.start()
        peer.authenticate()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(auth_errors, [])
        return peer, server

    def run_call(self, peer, timeout=None):
        output = []

        def execute():
            try:
                output.append(peer.call("/org/qemu/Display1/VM", "org.freedesktop.DBus.Properties",
                                        "Get", "ss", ("org.qemu.Display1.VM", "Name"), timeout))
            except Exception as error:
                output.append(error)

        worker = threading.Thread(target=execute, daemon=True)
        worker.start()
        self.addCleanup(lambda: worker.join(2))
        return worker, output

    def test_fragmented_socket_response_matches_pending_call(self):
        peer, server = self.make_peer()
        worker, output = self.run_call(peer)
        request = receive_message(server)
        self.assertEqual(request.body, ("org.qemu.Display1.VM", "Name"))
        response = encode_message(2, 200, [(5, Variant("u", request.serial))], "v", (Variant("s", "phone"),))
        for start in range(0, len(response), 3):
            server.sendall(response[start:start + 3])
        worker.join(2)
        self.assertEqual(output, [(Variant("s", "phone"),)])
        self.assertFalse(peer.closed.is_set())

    def test_interleaved_signal_and_unrelated_reply_do_not_complete_call(self):
        peer, server = self.make_peer()
        worker, output = self.run_call(peer)
        request = receive_message(server)
        server.sendall(encode_message(4, 200, [(1, Variant("o", "/")), (2, Variant("s", "test.Signal")),
                                              (3, Variant("s", "Changed"))]))
        server.sendall(encode_message(2, 201, [(5, Variant("u", request.serial + 100))]))
        server.sendall(encode_message(2, 202, [(5, Variant("u", request.serial))], "u", (17,)))
        worker.join(2)
        self.assertEqual(output, [(17,)])

    def test_handler_returns_typed_properties(self):
        observed = []

        def handler(message):
            observed.append((message.path, message.interface, message.member, message.body))
            return "a{sv}", ({"Interfaces": Variant("as", ["org.qemu.Display1.Listener.Win32.Map"])},)

        peer, server = self.make_peer(handler)
        server.sendall(encode_message(1, 50, [(1, Variant("o", "/org/qemu/Display1/Listener")),
                                              (2, Variant("s", "org.freedesktop.DBus.Properties")),
                                              (3, Variant("s", "GetAll"))], "s", ("org.qemu.Display1.Listener",)))
        reply = receive_message(server)
        self.assertEqual(reply.headers[5], 50)
        self.assertEqual(reply.body[0]["Interfaces"].value, ["org.qemu.Display1.Listener.Win32.Map"])
        self.assertEqual(observed[0][2:], ("GetAll", ("org.qemu.Display1.Listener",)))

    def test_handler_error_is_reply_and_reader_survives(self):
        peer, server = self.make_peer(lambda _: (_ for _ in ()).throw(ValueError("bad geometry")))
        server.sendall(encode_message(1, 50, [(1, Variant("o", "/")),
                                              (2, Variant("s", "test.Listener")), (3, Variant("s", "Map"))]))
        reply = receive_message(server)
        self.assertEqual(reply.kind, 3)
        self.assertEqual(reply.headers[5], 50)
        self.assertIn("bad geometry", reply.body[0])
        self.assertFalse(peer.closed.is_set())

    def test_call_timeout_closes_reader_and_pending_entry(self):
        peer, server = self.make_peer()
        worker, output = self.run_call(peer, timeout=.1)
        receive_message(server)
        worker.join(2)
        self.assertIsInstance(output[0], TimeoutError)
        self.assertTrue(peer.closed.is_set())
        self.assertTrue(peer.join(.2))
        self.assertEqual(peer._pending, {})

    def test_disconnect_wakes_pending_caller(self):
        peer, server = self.make_peer(timeout=2)
        worker, output = self.run_call(peer)
        receive_message(server)
        server.close()
        worker.join(1)
        self.assertFalse(worker.is_alive())
        self.assertIsInstance(output[0], DbusError)
        self.assertTrue(peer.closed.is_set())

    def test_oversized_header_or_body_rejected_before_reading_payload(self):
        for body, header in ((MAX_BODY + 1, 0), (0, MAX_HEADER + 1)):
            with self.subTest(body=body, header=header):
                peer, server = self.make_peer()
                server.sendall(struct.pack("<BBBBIII", 108, 1, 0, 1, body, 1, header))
                self.assertTrue(peer.closed.wait(1))
                self.assertIsInstance(peer.error, DbusError)
                self.assertIn("exceeded limit", str(peer.error))

    def test_partial_message_times_out_and_closes(self):
        peer, server = self.make_peer(timeout=.08)
        server.sendall(b"l\x01")
        self.assertTrue(peer.closed.wait(1))
        self.assertIn("timed out", str(peer.error))

    def test_close_joins_running_handler_outside_peer_lock(self):
        entered, release = threading.Event(), threading.Event()

        def handler(_):
            entered.set()
            release.wait(2)
            return "", ()

        peer, server = self.make_peer(handler)
        server.sendall(encode_message(1, 50, [(1, Variant("o", "/")),
                                              (2, Variant("s", "test.Listener")), (3, Variant("s", "Map"))]))
        self.assertTrue(entered.wait(1))
        self.assertFalse(peer.close(timeout=.01))
        release.set()
        self.assertTrue(peer.join(1))

    def test_close_from_handler_does_not_join_itself(self):
        entered = threading.Event()

        def handler(_):
            peer.close()
            entered.set()
            return "", ()

        peer, server = self.make_peer(handler)
        server.sendall(encode_message(1, 50, [(1, Variant("o", "/")),
                                              (2, Variant("s", "test.Listener")), (3, Variant("s", "Map"))]))
        self.assertTrue(entered.wait(1))
        self.assertTrue(peer.join(1))


if __name__ == "__main__":
    unittest.main()
