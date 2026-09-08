"""Shared-surface and direct-input tests without QEMU, Android, or Win32 mappings."""
import contextlib
import ctypes
import shutil
import threading
import unittest
from types import SimpleNamespace
from unittest import mock

from mcandroid_bridge import qemu, qemu_dbus as dbus


def message(member, body=(), interface=dbus.MAP_INTERFACE):
    return SimpleNamespace(path=dbus.LISTENER_PATH, interface=interface, member=member, body=body)


def backend(**kwargs):
    with mock.patch.object(dbus.os, "name", "nt"):
        return dbus.QemuDbusBackend(4, 2, 120, 123, 4444, **kwargs)


class LayoutTests(unittest.TestCase):
    def test_pixman_byte_order_alpha_and_android_channel_override(self):
        cases = ((0x20020888, "bgr0", "rgb0"), (0x20028888, "bgra", "rgba"),
                 (0x20030888, "rgb0", "bgr0"), (0x20038888, "rgba", "bgra"))
        for pixman, native, corrected in cases:
            with self.subTest(pixman=hex(pixman)):
                self.assertEqual(native, dbus.pixel_format(pixman, "rgb"))
                self.assertEqual(corrected, dbus.pixel_format(pixman, "bgr"))
        with self.assertRaisesRegex(ValueError, "pixman format"):
            dbus.pixel_format(0x10020565, "rgb")

    def test_padded_layout_size_and_invalid_dimensions_stride_offset(self):
        self.assertEqual((64, "bgr0"), dbus.validate_layout(16, 4, 2, 24, 0x20020888, "rgb"))
        for offset, width, height, stride in ((-1,4,2,16), (True,4,2,16), (0,4,2,True),
                                             (0,4,2,12), (0,4,2,17), (0,4,2,16388),
                                             (0,3,2,16), (0,4,3,16), (128*1024*1024,4,2,16)):
            with self.subTest(layout=(offset,width,height,stride)), self.assertRaises((ValueError, RuntimeError)):
                dbus.validate_layout(offset, width, height, stride, 0x20020888, "rgb")

    def test_read_only_surface_strips_padding_and_releases_handle_once(self):
        first, second = bytes(range(16)), bytes(range(32, 48))
        memory = ctypes.create_string_buffer(b"header!!" + first + b"padding!" + second + b"padding!")
        kernel = SimpleNamespace(MapViewOfFile=mock.Mock(return_value=ctypes.addressof(memory)),
                                 UnmapViewOfFile=mock.Mock(), CloseHandle=mock.Mock())
        with mock.patch.object(dbus.ctypes, "WinDLL", return_value=kernel, create=True):
            surface = dbus.ReadOnlySurface(42, 8, 4, 2, 24, 0x20020888, "rgb")
            try:
                kernel.MapViewOfFile.assert_called_once_with(42, 4, 0, 0, 56)
                self.assertEqual(first + second, surface.snapshot())
            finally:
                surface.close()
            surface.close()
            kernel.UnmapViewOfFile.assert_called_once_with(ctypes.addressof(memory))
            kernel.CloseHandle.assert_called_once_with(42)
            with self.assertRaisesRegex(RuntimeError, "closed"):
                surface.snapshot()

    def test_contiguous_snapshot_respects_offset(self):
        pixels = bytes(range(32))
        memory = ctypes.create_string_buffer(b"skip" + pixels)
        kernel = SimpleNamespace(MapViewOfFile=mock.Mock(return_value=ctypes.addressof(memory)),
                                 UnmapViewOfFile=mock.Mock(), CloseHandle=mock.Mock())
        with mock.patch.object(dbus.ctypes, "WinDLL", return_value=kernel, create=True):
            surface = dbus.ReadOnlySurface(43, 4, 4, 2, 16, 0x20038888, "rgb")
            try:
                self.assertEqual("rgba", surface.format)
                self.assertEqual(pixels, surface.snapshot())
            finally:
                surface.close()

    def test_validation_or_mapping_failure_closes_received_handle(self):
        for invalid_layout in (True, False):
            kernel = SimpleNamespace(MapViewOfFile=mock.Mock(return_value=None),
                                     UnmapViewOfFile=mock.Mock(), CloseHandle=mock.Mock())
            with self.subTest(invalid_layout=invalid_layout), \
                 mock.patch.object(dbus.ctypes, "WinDLL", return_value=kernel, create=True), \
                 mock.patch.object(dbus.ctypes, "WinError", return_value=OSError("mapping failed"), create=True), \
                 mock.patch.object(dbus.ctypes, "get_last_error", return_value=6, create=True):
                with self.assertRaises((ValueError, OSError)):
                    dbus.ReadOnlySurface(44, 0, 4, 2, 12 if invalid_layout else 16, 0x20020888, "rgb")
                kernel.CloseHandle.assert_called_once_with(44)
                kernel.UnmapViewOfFile.assert_not_called()
                self.assertEqual(not invalid_layout, kernel.MapViewOfFile.called)


class PixelSurfaceTests(unittest.TestCase):
    def test_padded_scanout_keeps_native_pixel_order_and_discards_only_row_padding(self):
        first = bytes([255, 0, 0, 73]) * 4
        second = bytes([0, 0, 255, 191]) * 4
        instance = backend()
        try:
            instance._listener_message(message("Scanout", (4, 2, 20, 0x20030888,
                first + b"pad!" + second + b"pad!"), interface=dbus.DISPLAY + "Listener"))
            self.assertIsInstance(instance._surface, dbus.PixelSurface)
            self.assertEqual("rgb0", instance._surface.format)
            self.assertEqual("dbus-pixels", instance.transport_mode)
            self.assertEqual(16, instance._surface.stride)
            self.assertEqual(first + second, instance._surface.snapshot())
            self.assertTrue(instance._dirty.is_set())
        finally:
            instance.stop()

    def test_small_updates_include_edge_pixels_and_preserve_other_pixels(self):
        initial = bytes(range(32))
        surface = dbus.PixelSurface(4, 2, 16, 0x20020888, initial, "rgb")
        try:
            # Dirty rectangles may be one pixel wide/high, even though the whole NV12 display is even.
            surface.update(3, 0, 1, 2, 8, 0x20020888, b"ABCDpad!EFGHpad!", "rgb")
            expected = bytearray(initial)
            expected[12:16], expected[28:32] = b"ABCD", b"EFGH"
            self.assertEqual(bytes(expected), surface.snapshot())
            surface.update(0, 1, 1, 1, 4, 0x20020888, b"IJKL", "rgb")
            expected[16:20] = b"IJKL"
            self.assertEqual(bytes(expected), surface.snapshot())
            for x, y, width, height in ((-1,0,1,1), (4,0,1,1), (0,2,1,1), (3,1,2,1)):
                with self.subTest(rect=(x,y,width,height)), self.assertRaises(ValueError):
                    surface.update(x, y, width, height, width * 4, 0x20020888,
                                   bytes(width * height * 4), "rgb")
                self.assertEqual(bytes(expected), surface.snapshot(), "invalid update changed existing pixels")
        finally:
            surface.close()

    def test_invalid_payload_lengths_fail_before_allocation_or_partial_update(self):
        for size in (31, 33):
            with self.subTest(scanout_bytes=size), mock.patch.object(dbus, "bytearray", create=True) as allocate:
                with self.assertRaisesRegex(ValueError, "scanout pixels"):
                    dbus.PixelSurface(4, 2, 16, 0x20020888, bytes(size), "rgb")
                allocate.assert_not_called()
        original = bytes(range(32))
        surface = dbus.PixelSurface(4, 2, 16, 0x20020888, original, "rgb")
        try:
            for size in (23, 25):
                with self.subTest(update_bytes=size), self.assertRaises(ValueError):
                    surface.update(1, 0, 2, 2, 12, 0x20020888, bytes(size), "rgb")
                self.assertEqual(original, surface.snapshot(), "bad second row allowed a partial first-row write")
        finally:
            surface.close()

    def test_scanout_after_stop_retires_incoming_surface_without_installing_it(self):
        for member, interface, factory in (("ScanoutMap", dbus.MAP_INTERFACE, "ReadOnlySurface"),
                                            ("Scanout", dbus.DISPLAY + "Listener", "PixelSurface")):
            with self.subTest(member=member):
                instance = backend()
                instance.stop()
                incoming = FakeSurface()
                generation = instance._generation
                with mock.patch.object(dbus, factory, return_value=incoming):
                    self.assertEqual(("", ()), instance._listener_message(message(member, (), interface)))
                self.assertEqual(1, incoming.closed)
                self.assertIsNone(instance._surface)
                self.assertIsNone(instance.transport_mode)
                self.assertEqual(generation, instance._generation)
                instance.stop()
                self.assertEqual(1, incoming.closed)


class FakeSurface:
    def __init__(self, width=4, height=2, value=1, fmt="bgr0"):
        self.width, self.height, self.format = width, height, fmt
        self.stride = width * 4
        self.pixels = bytes([value]) * (self.stride * height)
        self.closed = 0

    def snapshot(self):
        if self.closed:
            raise AssertionError("snapshot read retired mapping")
        return self.pixels

    def close(self):
        self.closed += 1


class FakePeer:
    def __init__(self, _socket, handler=None, timeout=None):
        self.handler, self.error = handler, None
        self.closed = threading.Event()
        self.call = mock.Mock(return_value=None)

    def authenticate(self):
        pass

    def close(self):
        pass


class FakeConverter:
    def __init__(self, _ffmpeg, width, height, _fps, publish, input_format=None):
        self.width, self.height, self.input_format = width, height, input_format
        self.frames = []
        self.received = threading.Event()
        self.output_waiting = threading.Event()
        self.release = threading.Event()
        self.block_first = False
        self.closed = 0

    def submit(self, pixels):
        self.frames.append(pixels)
        self.received.set()

    def wait_for_output(self, timeout=5):
        self.output_waiting.set()
        if self.block_first and len(self.frames) == 1:
            if not self.release.wait(2):
                raise AssertionError("test did not release converter")
        return not self.closed

    def check_health(self):
        pass

    def close(self):
        self.closed += 1
        self.release.set()


@contextlib.contextmanager
def running_backend(resize_handler=None, block_first=False, converter_class=FakeConverter, publish=None):
    instance = backend()
    converters = []
    created = threading.Event()

    def converter_factory(*args, **kwargs):
        converter = converter_class(*args, **kwargs)
        converter.block_first = block_first
        converters.append(converter)
        created.set()
        return converter

    if resize_handler is not None:
        instance.set_resize_handler(lambda w, h: resize_handler(instance, w, h))
    with mock.patch.object(dbus, "windows_socketpair", side_effect=lambda *_: (mock.Mock(), mock.Mock())), \
         mock.patch.object(dbus, "Peer", FakePeer), \
         mock.patch.object(dbus, "QmpClient") as qmp_factory, \
         mock.patch.object(qemu, "FFmpegConverter", side_effect=converter_factory):
        qmp_factory.return_value.__enter__.return_value.execute.return_value = None
        # socket.share returns bytes in the Win32 handoff.
        with mock.patch.object(dbus.base64, "b64encode", return_value=b"c29ja2V0"):
            instance.start(publish or (lambda *_: None))
        try:
            yield instance, converters, created
        finally:
            instance.stop()
            if instance.thread.is_alive():
                raise AssertionError("shared frame worker did not stop")


class SharedBackendTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("ffmpeg"), "FFmpeg not on PATH")
    def test_real_ffmpeg_outputs_across_eight_raw_scanouts_at_twenty_fps(self):
        frames = []
        all_output = threading.Event()

        class ObservedConverter(qemu.FFmpegConverter):
            def __init__(self, *args, **kwargs):
                self.accepted = threading.Event()
                super().__init__(*args, **kwargs)

            def submit(self, pixels):
                super().submit(pixels)
                self.accepted.set()

        def publish(frame, _timestamp):
            frames.append(bytes(frame))
            if len(frames) == 8:
                all_output.set()

        with running_backend(resize_handler=lambda *_: publish, converter_class=ObservedConverter,
                             publish=publish) as (instance, converters, created):
            instance.fps = 20
            for index in range(8):
                if converters:
                    converters[0].accepted.clear()
                pixel = bytes([0, 0, 255, 0] if index % 2 == 0 else [255, 0, 0, 0])
                instance._listener_message(message("Scanout", (32, 48, 128, 0x20020888,
                    pixel * (32 * 48)), interface=dbus.DISPLAY + "Listener"))
                self.assertTrue(created.wait(1), "real converter was not created")
                self.assertTrue(converters[0].accepted.wait(2), "Scanout did not reach persistent FFmpeg")
            self.assertTrue(all_output.wait(5), "continuous page flips prevented first/continued FFmpeg output")
            instance.check_health()
            self.assertEqual(1, len(converters))
            self.assertTrue(instance.first_frame.is_set())
            self.assertEqual(8, len(frames))
            for index, frame in enumerate(frames):
                self.assertEqual(32 * 48 * 3 // 2, len(frame))
                self.assertAlmostEqual(63 if index % 2 == 0 else 32, frame[0], delta=2)

    def test_same_layout_raw_scanouts_reuse_converter_and_keep_active_touch(self):
        published = []
        output = threading.Event()

        class PublishingConverter(FakeConverter):
            def __init__(self, ffmpeg, width, height, fps, publish, **kwargs):
                super().__init__(ffmpeg, width, height, fps, publish, **kwargs)
                self.publish = publish

            def submit(self, pixels):
                super().submit(pixels)
                self.publish(pixels, len(self.frames))
                output.set()

        def publish(frame, timestamp):
            published.append((frame, timestamp))

        with running_backend(converter_class=PublishingConverter, publish=publish) as (instance, converters, created):
            def scanout(value):
                instance._listener_message(message("Scanout", (4, 2, 16, 0x20020888,
                    bytes([value]) * 32), interface=dbus.DISPLAY + "Listener"))

            scanout(1)
            self.assertTrue(created.wait(1), "initial scanout did not create converter")
            converter = converters[0]
            self.assertTrue(converter.received.wait(1), "initial raw pixels were not submitted")
            self.assertTrue(output.wait(1), "initial raw scanout did not publish")
            instance.touch("DOWN", .5, .5)
            with mock.patch.object(instance, "_release_touch", wraps=instance._release_touch) as release:
                for value in range(2, 9):
                    output.clear()
                    scanout(value)
                    self.assertTrue(output.wait(1), "page-flip Scanout restarted conversion before output")
                    self.assertEqual(bytes([value]) * 32, published[-1][0])
                    self.assertEqual(1, len(converters), "same layout created another FFmpeg process")
                    self.assertIs(converter, instance.converter)
                    self.assertTrue(instance._touch_active, "page flip released the ongoing gesture")
                release.assert_not_called()
            self.assertEqual(0, converter.closed)
            self.assertEqual(8, len(converter.frames))
            self.assertEqual(8, instance.frames_received)
            self.assertTrue(instance.first_frame.is_set())
            instance.check_health()

    def test_replacing_and_disabling_scanout_retires_each_mapping_once(self):
        instance = backend()
        first, second = FakeSurface(), FakeSurface(8, 2)
        with mock.patch.object(dbus, "ReadOnlySurface", side_effect=[first, second]):
            instance._listener_message(message("ScanoutMap", ()))
            instance._listener_message(message("ScanoutMap", ()))
        self.assertEqual(1, first.closed)
        self.assertEqual(0, second.closed)
        instance._listener_message(message("Disable", interface=dbus.DISPLAY + "Listener"))
        self.assertEqual(1, second.closed)
        self.assertIsNone(instance._surface)
        instance.stop()
        self.assertEqual(1, second.closed)

    def test_resize_callback_can_enter_listener_without_map_lock(self):
        finished = threading.Event()

        def resize(instance, width, height):
            self.assertEqual((8, 2), (width, height))
            def notification():
                instance._listener_message(message("UpdateMap", (0, 0, 8, 2)))
                finished.set()
            thread = threading.Thread(target=notification, daemon=True)
            thread.start()
            self.assertTrue(finished.wait(.5), "resize callback retained the mapping lock")
            thread.join(.5)
            return lambda *_: None

        with running_backend(resize_handler=resize) as (instance, converters, created):
            surface = FakeSurface(8, 2, fmt="rgb0")
            with mock.patch.object(dbus, "ReadOnlySurface", return_value=surface):
                instance._listener_message(message("ScanoutMap", ()))
            self.assertTrue(finished.wait(1), "resize handler was not called")
            self.assertTrue(created.wait(1), "resize did not create a converter")
            self.assertTrue(converters[0].received.wait(1), "resized frame was not submitted")
            instance.check_health()
            self.assertEqual("rgb0", converters[0].input_format)

    def test_dirty_updates_coalesce_to_latest_snapshot_when_conversion_is_busy(self):
        with running_backend(block_first=True) as (instance, converters, created):
            surface = FakeSurface(value=1)
            with mock.patch.object(dbus, "ReadOnlySurface", return_value=surface):
                instance._listener_message(message("ScanoutMap", ()))
            # Submission returned, but output is still in flight: the new completion gate must
            # not retain the surface lock or allow another stale input into FFmpeg.
            self.assertTrue(created.wait(1), "scanout did not create a converter")
            converter = converters[0]
            self.assertTrue(converter.received.wait(1), "first snapshot not delivered")
            self.assertTrue(converter.output_waiting.wait(1), "worker did not wait for output completion")
            for value in range(2, 21):
                surface.pixels = bytes([value]) * 32
                instance._listener_message(message("UpdateMap", (0, 0, 4, 2)))
            self.assertEqual([bytes([1]) * 32], converter.frames, "queued another frame before completion")
            converter.received.clear()
            converter.release.set()
            self.assertTrue(converter.received.wait(1), "latest dirty surface not delivered")
            instance.check_health()
            self.assertEqual([bytes([1]) * 32, bytes([20]) * 32], converter.frames)

    def test_output_completion_failure_stops_worker_and_reaches_backend_health(self):
        for failure in (TimeoutError("frame completion expired"), RuntimeError("converter reader failed")):
            with self.subTest(failure=type(failure).__name__):
                class FailedConverter(FakeConverter):
                    def wait_for_output(self, timeout=5):
                        raise failure

                with running_backend(converter_class=FailedConverter) as (instance, converters, created):
                    instance._listener_message(message("Scanout", (4, 2, 16, 0x20020888, bytes(32)),
                                                       interface=dbus.DISPLAY + "Listener"))
                    self.assertTrue(created.wait(1), "scanout did not create converter")
                    instance.thread.join(1)
                    self.assertFalse(instance.thread.is_alive(), "failed completion left capture running")
                    self.assertIs(failure, instance.error)
                    self.assertEqual(1, converters[0].closed, "failed converter was not retired")
                    with self.assertRaisesRegex(RuntimeError, "QEMU shared display failed") as caught:
                        instance.check_health()
                    self.assertIs(failure, caught.exception.__cause__)

    def test_invalid_dirty_rectangle_fails_health(self):
        instance = backend()
        instance._surface = FakeSurface()
        with self.assertRaisesRegex(ValueError, "outside"):
            instance._listener_message(message("UpdateMap", (3, 0, 2, 2)))
        with self.assertRaisesRegex(RuntimeError, "outside"):
            instance.check_health()
        instance.stop()


class InputTests(unittest.TestCase):
    def test_touch_uses_display_pixels_and_up_can_retry_after_failure(self):
        instance = backend()
        instance.control = mock.Mock(error=None, closed=threading.Event())
        instance.control.call.side_effect = [None, OSError("uncertain release"), None, None]
        instance.touch("DOWN", 1, 1)
        self.assertEqual((0, 0, 3, 1), instance.control.call.call_args.args[-1])
        with self.assertRaisesRegex(OSError, "uncertain release"):
            instance.touch("UP", 1, 1)
        self.assertTrue(instance._touch_active)
        instance.stop()
        self.assertFalse(instance._touch_active)
        self.assertEqual((2, 0, 3, 1), instance.control.call.call_args_list[-2].args[-1])
        self.assertEqual((dbus.CONSOLE_PATH, dbus.DISPLAY + "Mouse", "Release", "u", (9,)),
                         instance.control.call.call_args.args)
        self.assertEqual(4, instance.control.call.call_count)

    def test_failed_touch_button_release_remains_pending_and_stop_retries_without_qmp(self):
        instance = backend()
        instance.control = mock.Mock(error=None, closed=threading.Event())
        release_attempts = []

        def send(path, interface, member, signature, args):
            if member == "Release":
                self.assertTrue(instance._touch_active, "contact cleared before BTN_TOUCH release completed")
                self.assertEqual((dbus.CONSOLE_PATH, dbus.DISPLAY + "Mouse", "u", (9,)),
                                 (path, interface, signature, args))
                release_attempts.append(args)
                if len(release_attempts) == 1:
                    raise TimeoutError("uncertain touch button release")

        instance.control.call.side_effect = send
        with mock.patch.object(dbus, "QmpClient") as direct_qmp, \
             mock.patch.object(instance, "_qmp_transaction") as inherited_qmp:
            instance.touch("DOWN", 0, 0)
            instance.touch("MOVE", 1, 1)
            with self.assertRaisesRegex(TimeoutError, "uncertain touch button release"):
                instance.touch("UP", 1, 1)
            self.assertTrue(instance._touch_active)
            instance.stop()
            self.assertFalse(instance._touch_active)
            direct_qmp.assert_not_called()
            inherited_qmp.assert_not_called()
        calls = [(call.args[1], call.args[2], call.args[3], call.args[4])
                 for call in instance.control.call.call_args_list]
        self.assertEqual([
            (dbus.DISPLAY + "MultiTouch", "SendEvent", "utdd", (0, 0, 0, 0)),
            (dbus.DISPLAY + "MultiTouch", "SendEvent", "utdd", (1, 0, 3, 1)),
            (dbus.DISPLAY + "MultiTouch", "SendEvent", "utdd", (2, 0, 3, 1)),
            (dbus.DISPLAY + "Mouse", "Release", "u", (9,)),
            (dbus.DISPLAY + "MultiTouch", "SendEvent", "utdd", (2, 0, 3, 1)),
            (dbus.DISPLAY + "Mouse", "Release", "u", (9,)),
        ], calls)

    def test_uncertain_down_retains_contact_for_explicit_release(self):
        instance = backend()
        instance.control = mock.Mock(error=None, closed=threading.Event())
        instance.control.call.side_effect = [TimeoutError("lost reply"), None, None]
        with self.assertRaises(TimeoutError):
            instance.touch("DOWN", .5, .5)
        self.assertTrue(instance._touch_active)
        instance.error = OSError("capture failed")
        instance.touch("UP", .5, .5)
        self.assertFalse(instance._touch_active)
        instance.stop()

    def test_printable_ascii_qnums_and_shift_press_release_order(self):
        # Independent PC set-1 reference for letters, digits, and punctuation.
        key_numbers = dict(zip("abcdefghijklmnopqrstuvwxyz", (30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)))
        key_numbers.update(dict(zip("1234567890", (2,3,4,5,6,7,8,9,10,11))))
        key_numbers.update({"-":12, "=":13, "[":26, "]":27, ";":39, "'":40, "`":41,
                            "\\":43, ",":51, ".":52, "/":53, " ":57})
        shifted = dict(zip("!@#$%^&*()_+{}:\"~|<>?", "1234567890-=[];'`\\,./"))
        instance = backend()
        instance.control = mock.Mock(error=None, closed=threading.Event())
        for codepoint in range(32, 127):
            char = chr(codepoint)
            with self.subTest(char=char):
                instance.control.call.reset_mock()
                instance.text(char)
                plain = shifted.get(char, char.lower())
                keys = ([42] if char in shifted or char.isupper() else []) + [key_numbers[plain]]
                actual = [(call.args[2], call.args[-1][0]) for call in instance.control.call.call_args_list]
                self.assertEqual([("Press", code) for code in keys], actual[:len(keys)])
                self.assertCountEqual([("Release", code) for code in keys], actual[len(keys):])
        for value in ("\n", "中文", "a" * 257):
            with self.subTest(invalid=value[:8]), self.assertRaises(ValueError):
                instance.text(value)
        instance.stop()

    def test_failed_character_release_still_attempts_shift_release(self):
        instance = backend()
        instance.control = mock.Mock(error=None, closed=threading.Event())
        failures = [30]

        def send(_path, _interface, member, _signature, args):
            if member == "Release" and args[0] in failures:
                failures.remove(args[0])
                raise OSError("letter release failed")

        instance.control.call.side_effect = send
        with self.assertRaises(OSError):
            instance.text("A")
        calls = [(call.args[2], call.args[-1][0]) for call in instance.control.call.call_args_list]
        self.assertIn(("Release", 42), calls, "failed letter release left Shift pressed")
        self.assertEqual({30}, instance._pressed_keys)
        instance.stop()
        self.assertFalse(instance._pressed_keys, "stop did not retry the uncertain letter release")


if __name__ == "__main__":
    unittest.main()
