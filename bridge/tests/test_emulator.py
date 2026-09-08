import contextlib
import shutil
import threading
import unittest
from unittest.mock import patch

from mcandroid_bridge.emulator import FFmpegConverter


@unittest.skipUnless(shutil.which("ffmpeg"), "FFmpeg not on PATH")
class ConverterTests(unittest.TestCase):
    def test_native_phone_frame_completes_before_next_input_without_eof(self):
        frames = []
        converter = FFmpegConverter(shutil.which("ffmpeg"), 1080, 1920, 60,
                                    lambda frame, _stamp: frames.append(bytes(frame)), input_format="bgr0")
        try:
            # One frame at a time must produce an acknowledgement. Requiring a later input
            # would deadlock the live capture gate and recreate the stale FFmpeg queue.
            for index, (pixel, luma) in enumerate(((b"\0\0\xff\0", 63),
                                                  (b"\xff\0\0\xff", 32),
                                                  (b"\xff\xff\xff\0", 235)), 1):
                converter.submit(pixel * (1080 * 1920))
                self.assertTrue(converter.wait_for_output(timeout=5), "native frame did not complete")
                self.assertEqual(index, len(frames), "completion preceded the publish callback")
                self.assertEqual(1080 * 1920 * 3 // 2, len(frames[-1]))
                self.assertAlmostEqual(luma, frames[-1][0], delta=2)
                self.assertAlmostEqual(luma, frames[-1][1080 * 1920 - 1], delta=2)
                self.assertIsNone(converter.process.poll(), "completion required terminating FFmpeg")
            converter.check_health()
        finally:
            converter.close()

    @contextlib.contextmanager
    def paused_publication(self):
        publishing, release = threading.Event(), threading.Event()

        def publish(_frame, _timestamp):
            publishing.set()
            if not release.wait(5):
                raise AssertionError("test did not release publication")

        converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish)
        try:
            converter.submit(bytes([255, 0, 0]) * (32 * 48))
            self.assertTrue(publishing.wait(2), "FFmpeg did not enter the publish callback")
            yield converter, release
        finally:
            release.set()
            converter.close()

    def test_output_timeout_propagates_and_wait_can_resume_after_publish(self):
        with self.paused_publication() as (converter, release):
            with self.assertRaisesRegex(TimeoutError, "submitted display frame"):
                converter.wait_for_output(timeout=.02)
            release.set()
            self.assertTrue(converter.wait_for_output(timeout=2), "timeout poisoned completion state")
            converter.check_health()

    def test_publish_error_propagates_to_output_waiter_with_original_cause(self):
        failure = OSError("frame consumer failed")

        def publish(_frame, _timestamp):
            raise failure

        converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish)
        try:
            converter.submit(bytes(32 * 48 * 3))
            with self.assertRaisesRegex(RuntimeError, "FFmpeg converter failed") as caught:
                converter.wait_for_output(timeout=2)
            self.assertIs(failure, caught.exception.__cause__)
        finally:
            converter.close()

    def test_close_wakes_output_waiter_before_blocked_publish_returns(self):
        with self.paused_publication() as (converter, release):
            waiting, waiter_done, close_done = threading.Event(), threading.Event(), threading.Event()
            results, failures = [], []
            condition_wait = converter.completion.wait

            def observe_wait(timeout):
                waiting.set()
                return condition_wait(timeout)

            def wait_for_output():
                try:
                    results.append(converter.wait_for_output(timeout=30))
                except Exception as error:
                    failures.append(error)
                finally:
                    waiter_done.set()

            def close():
                try:
                    converter.close()
                except Exception as error:
                    failures.append(error)
                finally:
                    close_done.set()

            with patch.object(converter.completion, "wait", side_effect=observe_wait):
                waiter = threading.Thread(target=wait_for_output, daemon=True)
                closer = threading.Thread(target=close, daemon=True)
                waiter.start()
                try:
                    self.assertTrue(waiting.wait(1), "output waiter never slept")
                    closer.start()
                    self.assertTrue(waiter_done.wait(1), "close did not wake pending output wait")
                    self.assertEqual([False], results)
                    self.assertFalse(release.is_set(), "test did not keep publication blocked")
                finally:
                    release.set()
                    waiter.join(2)
                    if closer.ident is not None:
                        closer.join(3)
                self.assertTrue(close_done.is_set(), "close deadlocked with completion or publish")
                self.assertFalse(waiter.is_alive())
                self.assertFalse(closer.is_alive())
                self.assertEqual([], failures)

    def test_persistent_rgb_conversion_frame_boundaries_and_bt709_range(self):
        frames = []
        ready = threading.Event()

        def publish(frame, _timestamp):
            frames.append(bytes(frame))
            if len(frames) >= 3:
                ready.set()

        converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish, bottom_up=True)
        try:
            # Red top half, blue bottom half in source memory; vflip swaps them.
            rgb = bytes([255, 0, 0]) * (32 * 24) + bytes([0, 0, 255]) * (32 * 24)
            for _ in range(3):
                converter.submit(rgb)
            self.assertTrue(ready.wait(5), "persistent converter did not emit frames before EOF")
            converter.check_health()
            self.assertEqual(3, len(frames))
            self.assertTrue(all(len(frame) == 32 * 48 * 3 // 2 for frame in frames))
            self.assertTrue(all(frame == frames[0] for frame in frames))
            # BT.709 limited: blue Y approx32, red Y approx63.
            self.assertAlmostEqual(32, frames[0][0], delta=2)
            self.assertAlmostEqual(63, frames[0][32 * 47], delta=2)
        finally:
            converter.close()

    def test_32bit_framebuffers_match_24bit_nv12_and_ignore_fourth_byte(self):
        # Aligned saturated blocks make R/B channel errors visible in both luma and chroma.
        colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255)]
        for order in ("rgb", "bgr"):
            reference_pixels = b"".join(bytes(color if order == "rgb" else color[::-1]) * (32 * 12)
                                        for color in colors)
            reference = self.convert_frames(order + "24", [reference_pixels])[0]
            for suffix in ("0", "a"):
                input_format = order + suffix
                # Zero alpha must still retain its RGB; a varying fourth byte must not affect NV12.
                packed_zero = b"".join(reference_pixels[i:i + 3] + b"\0"
                                        for i in range(0, len(reference_pixels), 3))
                packed_alpha = b"".join(reference_pixels[i:i + 3] + bytes([(i // 3 * 53) % 256])
                                         for i in range(0, len(reference_pixels), 3))
                with self.subTest(input_format=input_format):
                    frames = self.convert_frames(input_format, [packed_zero, packed_alpha])
                    self.assertEqual([reference, reference], frames)

    def test_32bit_input_size_rejected_without_poisoning_persistent_stream(self):
        for input_format in ("rgb0", "bgr0", "rgba", "bgra"):
            with self.subTest(input_format=input_format):
                ready = threading.Event()
                frames = []

                def publish(frame, _timestamp):
                    frames.append(bytes(frame))
                    ready.set()

                converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish,
                                            input_format=input_format)
                try:
                    for size in (32 * 48 * 3, 32 * 48 * 4 - 1, 32 * 48 * 4 + 1):
                        with self.assertRaisesRegex(ValueError, "frame size mismatch"):
                            converter.submit(bytes(size))
                    converter.submit(bytes([255, 255, 255, 0]) * (32 * 48))
                    self.assertTrue(ready.wait(5), "length rejection damaged the converter stream")
                    converter.check_health()
                    self.assertEqual(1, len(frames))
                    self.assertAlmostEqual(235, frames[0][0], delta=2)
                finally:
                    converter.close()

    def convert_frames(self, input_format, source_frames):
        frames = []
        ready = threading.Event()

        def publish(frame, _timestamp):
            frames.append(bytes(frame))
            if len(frames) >= len(source_frames):
                ready.set()

        converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish, input_format=input_format)
        try:
            for frame in source_frames:
                converter.submit(frame)
            self.assertTrue(ready.wait(5), f"{input_format} did not emit frames before EOF")
            converter.check_health()
            self.assertTrue(all(len(frame) == 32 * 48 * 3 // 2 for frame in frames))
            return frames
        finally:
            converter.close()

    def test_bgr_input_preserves_saturated_color_and_nv12_bt709_layout(self):
        frames = []
        ready = threading.Event()

        def publish(frame, _timestamp):
            frames.append(bytes(frame))
            ready.set()

        converter = FFmpegConverter(shutil.which("ffmpeg"), 32, 48, 30, publish, input_format="bgr24")
        try:
            # The same byte triplets mean blue then red when interpreted as BGR.
            packed = bytes([255, 0, 0]) * (32 * 24) + bytes([0, 0, 255]) * (32 * 24)
            converter.submit(packed)
            self.assertTrue(ready.wait(5), "BGR converter did not emit a frame before EOF")
            converter.check_health()
            frame = frames[0]
            self.assertEqual(32 * 48 * 3 // 2, len(frame))
            # BT.709 limited luma identifies the channel order independently of RGB decoding.
            self.assertAlmostEqual(32, frame[0], delta=2)
            self.assertAlmostEqual(63, frame[32 * 47], delta=2)
            # NV12 has a full Y plane followed by interleaved U,V chroma.
            self.assertAlmostEqual(240, frame[32 * 48], delta=2)
            self.assertAlmostEqual(118, frame[32 * 48 + 1], delta=2)
            self.assertAlmostEqual(102, frame[-2], delta=2)
            self.assertAlmostEqual(240, frame[-1], delta=2)
        finally:
            converter.close()


class ConverterValidationTests(unittest.TestCase):
    def test_unsupported_input_format_is_rejected_before_spawning(self):
        for value in ("", "argb", "abgr", "nv12", "RGB24", "rgb24 -i unwanted", None, 24):
            with self.subTest(input_format=value), \
                 patch("mcandroid_bridge.emulator.subprocess.Popen") as start:
                with self.assertRaisesRegex(ValueError, "FFmpeg input format must be"):
                    FFmpegConverter("not-launched", 32, 48, 30, lambda *_: None, input_format=value)
                start.assert_not_called()


class WireTests(unittest.TestCase):
    def test_protobuf_wire_field_numbers(self):
        try:
            from mcandroid_bridge import emulator_wire as wire
        except ImportError:
            self.skipTest("protobuf optional dependency not installed")
        self.assertEqual(bytes([8, 2]), wire.ImageFormat(format=2).SerializeToString())
        shot = wire.Image.FromString(bytes([34, 3]) + b"rgb")
        self.assertEqual(b"rgb", shot.image)
        key = wire.KeyboardEvent(key="GoHome", eventType=2)
        self.assertEqual(2, wire.KeyboardEvent.FromString(key.SerializeToString()).eventType)


if __name__ == "__main__":
    unittest.main()
