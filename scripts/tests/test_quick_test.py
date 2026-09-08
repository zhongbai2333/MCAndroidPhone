"""Focused launcher regressions; no emulator, Minecraft, or package installation."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "quick-test.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("quick_test_launcher", SCRIPT)
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="phone-launcher-tests-")
        self.project = Path(self.temporary.name)
        self.root = mock.patch.object(launcher, "ROOT", self.project)
        self.root.start()
        self.quiet = mock.patch.object(launcher, "say")
        self.quiet.start()
        self.jobs = mock.patch.object(launcher, "WindowsJob")
        self.jobs.start()

    def tearDown(self):
        self.jobs.stop()
        self.quiet.stop()
        self.root.stop()
        self.temporary.cleanup()

    def file(self, relative, content=""):
        path = self.project / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def jdk(self, version="25.0.1", directory="JDK with spaces"):
        home = self.project / directory
        extension = ".exe" if launcher.WINDOWS else ""
        self.file(f"{directory}/bin/java{extension}")
        self.file(f"{directory}/bin/javac{extension}")
        self.file(f"{directory}/release", f'JAVA_VERSION="{version}"\n')
        return home

    def test_properties_keep_spaces_equals_bom_and_ignore_comments(self):
        path = self.file("properties.ini", "\ufeff# comment=ignored\n!another=ignored\nkey = C:\\A B\\thing=x\n")
        self.assertEqual({"key": "C:\\A B\\thing=x"}, launcher.read_properties(path))

    def test_explicit_jdk25_with_spaces_is_accepted(self):
        home = self.jdk()
        self.assertEqual(home.resolve(), launcher.find_java(home))

    def test_explicit_wrong_major_does_not_silently_fall_back(self):
        for version in ("21.0.10", "250.0.1", "26.0.0"):
            with self.subTest(version=version):
                home = self.jdk(version)
                with self.assertRaisesRegex(RuntimeError, "JDK 25"):
                    launcher.find_java(home)

    def test_incomplete_jdk_rejected_even_with_valid_release(self):
        home = self.jdk()
        (home / ("bin/javac.exe" if launcher.WINDOWS else "bin/javac")).unlink()
        with self.assertRaisesRegex(RuntimeError, "JDK 25"):
            launcher.find_java(home)

    def test_cached_gradle_matches_declared_distribution_and_version(self):
        self.file("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n")
        match = self.file("gradle home/wrapper/dists/gradle-9.5.0-bin/hash/gradle-9.5.0/bin/gradle.bat")
        self.file("gradle home/wrapper/dists/gradle-9.6.0-bin/hash/gradle-9.6.0/bin/gradle.bat")
        with mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.project / "gradle home")}):
            self.assertEqual(match, launcher.gradle_command(self.project))

    def test_uncached_gradle_falls_back_to_project_wrapper(self):
        self.file("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-99.0.0-bin.zip\n")
        with mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.project / "empty cache")}):
            self.assertEqual(self.project / ("gradlew.bat" if launcher.WINDOWS else "gradlew"), launcher.gradle_command(self.project))

    def test_gradle_invocation_uses_cached_java_cli_and_instrumentation_agent(self):
        self.file("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n")
        self.file("cache/wrapper/dists/gradle-9.5.0-bin/hash/gradle-9.5.0/bin/gradle.bat")
        main = self.file("cache/wrapper/dists/gradle-9.5.0-bin/hash/gradle-9.5.0/lib/gradle-gradle-cli-main-9.5.0.jar")
        agent = self.file("cache/wrapper/dists/gradle-9.5.0-bin/hash/gradle-9.5.0/lib/agents/gradle-instrumentation-agent-9.5.0.jar")
        java = self.jdk(directory="JDK & spaces")
        with mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.project / "cache")}):
            command = launcher.gradle_invocation(self.project, java)
        self.assertEqual(java / "bin/java.exe", command[0])
        self.assertIn(f"-javaagent:{agent}", command)
        self.assertEqual(["-jar", main], command[-2:])

    def test_gradle_invocation_uses_java_wrapper_when_cache_missing(self):
        wrapper = self.file("gradle/wrapper/gradle-wrapper.jar")
        java = self.jdk()
        with mock.patch.object(launcher, "gradle_command", return_value=self.project / "gradlew.bat"):
            command = launcher.gradle_invocation(self.project, java)
        self.assertEqual(java / "bin/java.exe", command[0])
        self.assertEqual(["-classpath", wrapper, "org.gradle.wrapper.GradleWrapperMain"], command[-3:])

    def test_default_entry_boots_qemu_from_mod_and_external_mode_is_explicit(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT)]), mock.patch.object(launcher, "launch") as launch:
            self.assertEqual(0, launcher.main())
        args=launch.call_args.args[0]
        self.assertEqual("qemu",args.mode)
        self.assertTrue(launcher.use_managed_runtime(args))
        args.external_bridge=True
        self.assertFalse(launcher.use_managed_runtime(args))

    def test_project_lock_rejects_overlap_and_releases_on_error(self):
        with self.assertRaisesRegex(ValueError, "test exception"):
            with launcher.project_lock(self.project):
                with self.assertRaisesRegex(RuntimeError, "already using"):
                    with launcher.project_lock(self.project):
                        self.fail("overlapping lock was accepted")
                raise ValueError("test exception")
        with launcher.project_lock(self.project):
            pass

    def test_start_passes_each_path_argument_intact_and_tracks_process(self):
        session = launcher.Session(self.project, {"PATH": "a path"}, "pattern")
        process = mock.Mock(pid=48291)
        process.poll.return_value = None
        events = []
        session.job.add.side_effect = lambda child: events.append("assigned")
        process.stdin.write.side_effect = lambda data: events.append(data)
        command = [self.project / "Gradle with spaces/gradle.bat", "--no-daemon", "-PbridgeConfig=C:/A B/bridge.properties"]
        with mock.patch.object(launcher.subprocess, "Popen", return_value=process) as popen:
            returned, log = session.start("minecraft", command)
        self.assertIs(process, returned)
        record = session.child_records[process.pid]
        self.assertEqual(session.runtime, record.parent)
        self.assertEqual([getattr(sys, "_base_executable", None) or sys.executable,
                          "-m", "mcandroid_bridge._launch.gate", "--pid-file", str(record), "--",
                          *map(str, command)], popen.call_args.args[0])
        self.assertEqual(["assigned", b"G"], events)
        process.stdin.close.assert_called_once_with()
        self.assertNotIn("shell", popen.call_args.kwargs)
        self.assertEqual(self.project, popen.call_args.kwargs["cwd"])
        self.assertEqual([("minecraft", process)], session.processes)
        self.assertTrue(log.is_file())

    def test_failed_job_assignment_never_releases_gate_and_kills_wrapper(self):
        session = launcher.Session(self.project, {}, "pattern")
        process = mock.Mock(pid=48294)
        session.job.add.side_effect = OSError("job assignment failed")
        with mock.patch.object(launcher.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(OSError, "job assignment failed"):
                session.start("emulator", ["no-real-launch.exe"])
        process.stdin.write.assert_not_called()
        process.kill.assert_called_once_with()
        self.assertEqual([], session.processes)

    def test_child_pid_accepts_only_the_matching_owned_gate_record(self):
        session = launcher.Session(self.project, {}, "qemu")
        process = mock.Mock(pid=41001)
        process.poll.return_value = None
        with mock.patch.object(launcher.subprocess, "Popen", return_value=process):
            session.start("qemu", ["not-launched.exe"])
        record = session.child_records[process.pid]
        record.write_text(json.dumps({"wrapperPid": process.pid, "childPid": 42002}), encoding="utf-8")
        self.assertEqual(42002, session.child_pid(process))
        for value in ({"wrapperPid": 9999, "childPid": 42002},
                      {"wrapperPid": process.pid, "childPid": process.pid},
                      {"wrapperPid": process.pid, "childPid": True},
                      {"wrapperPid": process.pid, "childPid": -1},
                      {"wrapperPid": process.pid, "childPid": 0x100000000}, []):
            with self.subTest(value=value):
                record.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "Invalid managed child PID"):
                    session.child_pid(process)
        record.write_bytes(b"x" * 4097)
        with self.assertRaisesRegex(RuntimeError, "Invalid managed child PID"):
            session.child_pid(process)
        with self.assertRaisesRegex(ValueError, "outside this session"):
            session.child_pid(mock.Mock(pid=process.pid))
        record.unlink()
        with self.assertRaises(TimeoutError):
            session.child_pid(process, timeout=.02)
        process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "exited"):
            session.child_pid(process)

    def test_qemu_display_auto_and_overrides_forward_only_native_pid_for_dbus(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        defaults = dict(qemu_exe=executable, qemu_bios=True, qemu_iso=None, qemu_disk=None,
                        qemu_kernel=None, qemu_initrd=None, qemu_disk_format="qcow2",
                        qemu_accel="tcg", qemu_memory=512, qemu_cpus=1,
                        qemu_width=1080, qemu_height=1920, qemu_density=480,
                        qemu_color_order="auto", qemu_input="auto", qemu_gpu="auto")
        for windows, requested, expected in ((True, "auto", "dbus"), (False, "auto", "vnc"),
                                              (True, "vnc", "vnc"), (False, "dbus", "dbus")):
            with self.subTest(windows=windows, requested=requested):
                session = launcher.Session(self.project, {}, "qemu")
                process = mock.Mock(pid=41001)
                with mock.patch.object(launcher, "WINDOWS", windows), \
                     mock.patch.object(launcher, "find_qemu", return_value=executable), \
                     mock.patch.object(launcher, "free_vnc_port", return_value=5912) as vnc, \
                     mock.patch.object(launcher, "free_port", return_value=45123), \
                     mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}), \
                     mock.patch.object(session, "child_pid", return_value=42002) as native_pid, \
                     mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")) as start:
                    _, extra = launcher.start_qemu(session, SimpleNamespace(**defaults, qemu_display=requested))
                command = start.call_args.args[1]
                self.assertEqual(expected, extra[extra.index("--qemu-display") + 1])
                if expected == "dbus":
                    self.assertEqual("dbus,p2p=on,gl=off", command[command.index("-display") + 1])
                    self.assertNotIn("-vnc", command)
                    self.assertNotIn("--vnc-port", extra)
                    self.assertEqual("42002", extra[extra.index("--qemu-pid") + 1])
                    self.assertNotIn(str(process.pid), extra)
                    native_pid.assert_called_once_with(process)
                    vnc.assert_not_called()
                else:
                    self.assertIn("-vnc", command)
                    self.assertIn("--vnc-port", extra)
                    self.assertNotIn("--qemu-pid", extra)
                    native_pid.assert_not_called()
                session.job.close()

    def test_qemu_parser_rejects_unsupported_display_transport(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-display", "spice"]), \
             mock.patch.object(sys, "stderr", new_callable=io.StringIO), \
             mock.patch.object(launcher, "launch") as launch:
            with self.assertRaises(SystemExit) as error:
                launcher.main()
            self.assertEqual(2, error.exception.code)
            launch.assert_not_called()

    def test_run_surfaces_nonzero_exit_and_log_tail(self):
        session = launcher.Session(self.project, {}, "quick")
        process = mock.Mock(pid=48292, returncode=7)
        process.poll.return_value = 7
        log = self.file("error.log", "Specific failure explanation\n")
        with mock.patch.object(session, "start", return_value=(process, log)):
            with self.assertRaisesRegex(RuntimeError, "failed \\(exit 7\\)"):
                session.run("mock-build", ["no-process-is-launched"])
        launcher.say.assert_any_call("Specific failure explanation")

    def test_run_timeout_shows_log_tail(self):
        session = launcher.Session(self.project, {}, "pattern")
        process = mock.Mock(pid=48301)
        process.poll.return_value = None
        log = self.file("timeout.log", "World creation stalled\n")
        with mock.patch.object(session, "start", return_value=(process, log)), \
             mock.patch.object(launcher.time, "monotonic", side_effect=(0, 301)):
            with self.assertRaisesRegex(RuntimeError, "timed out after 300s"):
                session.run("minecraft", ["mock"], timeout=300)
        launcher.say.assert_any_call("World creation stalled")

    def test_log_guard_checks_only_owned_session_and_rejects_growth_past_limit(self):
        session = launcher.Session(self.project, {}, "pattern")
        owned = session.runtime / "emulator.log"
        owned.write_bytes(b"x" * 1024)
        self.file("unrelated/emulator.log", "outside this session\n" * 1000)
        with mock.patch.object(launcher, "MAX_LOG_BYTES", 1024):
            session.check_logs()
            with owned.open("ab") as output:
                output.write(b"\nRepeated emulator lock failure\n")
            with self.assertRaisesRegex(RuntimeError, "Log exceeded.*Stopping the owned test session"):
                session.check_logs()
        self.assertTrue((self.project / "unrelated/emulator.log").is_file())
        self.assertIn("Repeated emulator lock failure", launcher.say.call_args_list[-1].args[0])

    def test_run_polls_background_log_guard_and_cleanup_is_scoped_to_its_job(self):
        session = launcher.Session(self.project, {}, "pattern")
        process = mock.Mock(pid=48302)
        process.poll.return_value = None
        log = session.runtime / "emulator.log"
        log.write_bytes(b"flood" * 300)
        with mock.patch.object(session, "start", return_value=(process, log)), \
             mock.patch.object(launcher, "MAX_LOG_BYTES", 1024):
            try:
                with self.assertRaisesRegex(RuntimeError, "Log exceeded"):
                    session.run("minecraft", ["mock"])
            finally:
                session.stop()
        session.job.close.assert_called_once_with()
        process.kill.assert_not_called()

    def test_tail_reads_bounded_bytes_even_for_a_single_huge_line(self):
        log = self.file("large.log", "x" * (256 * 1024) + "FINAL FAILURE")
        launcher.show_log_tail(log)
        tail = launcher.say.call_args_list[-1].args[0]
        self.assertTrue(tail.endswith("FINAL FAILURE"))
        self.assertLessEqual(len(tail), 64 * 1024)

    def test_world_smoke_parser_accepts_android_and_pattern(self):
        for mode in ("android", "pattern", "qemu"):
            with self.subTest(mode=mode), \
                 mock.patch.object(sys, "argv", [str(SCRIPT), mode, "--world-smoke"]), \
                 mock.patch.object(launcher, "launch") as launch:
                self.assertEqual(0, launcher.main())
                args = launch.call_args.args[0]
                self.assertTrue(args.world_smoke)
                self.assertFalse(args.smoke)
                self.assertEqual(mode, args.mode)

    def test_world_smoke_parser_rejects_title_smoke_and_non_game_modes(self):
        for arguments in (("pattern", "--smoke", "--world-smoke"),
                          ("quick", "--world-smoke"), ("check", "--world-smoke")):
            with self.subTest(arguments=arguments), \
                 mock.patch.object(sys, "argv", [str(SCRIPT), *arguments]), \
                 mock.patch.object(sys, "stderr", new_callable=io.StringIO), \
                 mock.patch.object(launcher, "launch") as launch:
                with self.assertRaises(SystemExit) as error:
                    launcher.main()
                self.assertEqual(2, error.exception.code)
                launch.assert_not_called()

    def test_world_smoke_launch_uses_unique_session_screenshot_and_300s_deadline(self):
        session = launcher.Session(self.project, {}, "pattern")
        (session.runtime / "bridge").mkdir()
        (session.runtime / "bridge/bridge.properties").write_text("ready", encoding="utf-8")
        args = SimpleNamespace(mode="pattern", smoke=False, world_smoke=True, java_home=None,
                               sdk=None, ffmpeg=None)
        bridge = mock.Mock()
        bridge.poll.return_value = None
        expected_screenshot = session.runtime / "android-phone.png"

        def fake_run(name, command, **kwargs):
            log = session.runtime / f"{name}.log"
            log.write_text("mock output", encoding="utf-8")
            if name == "minecraft":
                self.assertIn("-PphoneWorldSmoke=true", command)
                self.assertIn("-PphonePatternSmoke=true", command)
                self.assertIn(f"-PphoneSmokeScreenshot={expected_screenshot}", command)
                self.assertNotIn("-PphoneSmoke=true", command)
                self.assertIn("-PphoneRuntimeBackend=pattern", command)
                self.assertFalse(any(str(arg).startswith("-PbridgeConfig=") for arg in command))
                self.assertFalse(any("ncpb" in str(arg).lower() for arg in command))
                self.assertEqual(300, kwargs["timeout"])
                self.file("run/logs/latest.log", "ANDROIDPHONE_WORLD_SMOKE_OK\n")
                expected_screenshot.write_bytes(b"mock screenshot")
            return log

        with mock.patch.object(launcher, "find_java", return_value=self.project / "jdk"), \
             mock.patch.object(launcher, "find_sdk", return_value=None), \
             mock.patch.object(launcher, "find_ffmpeg", return_value=None), \
             mock.patch.object(launcher, "gradle_invocation", return_value=["java", "gradle"]), \
             mock.patch.object(launcher, "Session", return_value=session), \
             mock.patch.object(session, "start", return_value=(bridge, session.runtime / "bridge.log")) as start, \
             mock.patch.object(session, "run", side_effect=fake_run) as run:
            launcher.launch(args)
        self.assertTrue(expected_screenshot.is_file())
        start.assert_not_called()
        self.assertEqual(["minecraft"], [call.args[0] for call in run.call_args_list])
        session.job.close.assert_called_once_with()
        launcher.say.assert_any_call(f"[PASS] Phone world smoke completed. Screenshot: {expected_screenshot}")

    def test_qemu_firmware_start_uses_owned_process_and_loopback_control(self):
        executable = self.file("runtime with spaces/qemu-system-x86_64.exe")
        args = SimpleNamespace(qemu_exe=executable, qemu_bios=True, qemu_iso=None, qemu_disk=None,
                               qemu_kernel=None, qemu_initrd=None, qemu_disk_format="qcow2",
                               qemu_accel="tcg", qemu_memory=512, qemu_cpus=1,
                               qemu_width=1080, qemu_height=1920, qemu_density=480,
                               qemu_color_order="auto", qemu_input="auto", qemu_display="vnc", qemu_gpu="auto")
        session = launcher.Session(self.project, {}, "qemu")
        process = mock.Mock()
        with mock.patch.object(launcher, "find_qemu", return_value=executable), \
             mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
             mock.patch.object(launcher, "free_port", return_value=45123), \
             mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}) as ready, \
             mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")) as start:
            owned, extra = launcher.start_qemu(session, args)
        self.assertIs(owned, process)
        command = start.call_args.args[1]
        self.assertEqual(str(executable), command[0])
        self.assertIn("127.0.0.1:12", command)
        self.assertIn("VGA,xres=1080,yres=1920,vgamem_mb=64", command)
        self.assertNotIn("-drive", command)
        self.assertIn("usb-tablet,bus=usb.0", command)
        self.assertNotIn("virtio-multitouch-pci,id=phone-touch,display=phone-display", command)
        self.assertEqual(45123, session.qemu_port)
        self.assertEqual(["--vnc-port", "5912", "--qmp-port", "45123", "--width", "640", "--height", "480",
                          "--qemu-color-order", "rgb", "--qemu-input", "mouse", "--qemu-display", "vnc"], extra)
        ready.assert_called_once_with(45123, process, timeout=30, check=session.check_logs)
        session.job.close()

    def test_qemu_parser_defaults_to_phone_resolution_and_density(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu"]), \
             mock.patch.object(launcher, "launch") as launch:
            self.assertEqual(0, launcher.main())
        args = launch.call_args.args[0]
        self.assertEqual((1080, 1920, 480), (args.qemu_width, args.qemu_height, args.qemu_density))
        self.assertEqual("auto", args.qemu_color_order)
        self.assertEqual("auto", args.qemu_input)
        self.assertEqual("auto", args.qemu_display)
        self.assertEqual("auto", args.qemu_gpu)

    def test_qemu_parser_display_options_reach_the_launched_guest(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        iso = self.file("images/android.iso")
        self.file("images/android/kernel")
        self.file("images/android/initrd.img")
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-width", "720",
                                             "--qemu-height", "1280", "--qemu-density", "320", "--qemu-display", "vnc"]), \
             mock.patch.object(launcher, "launch") as launch:
            self.assertEqual(0, launcher.main())
        args = launch.call_args.args[0]
        session = launcher.Session(self.project, {}, "qemu")
        process = mock.Mock()
        with mock.patch.object(launcher, "find_qemu", return_value=executable), \
             mock.patch.object(launcher, "find_iso", return_value=iso), \
             mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
             mock.patch.object(launcher, "free_port", return_value=45123), \
             mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}), \
             mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")) as start:
            _, extra = launcher.start_qemu(session, args)
        command = start.call_args.args[1]
        self.assertIn("VGA,xres=720,yres=1280,vgamem_mb=64,id=phone-display", command)
        boot_options = command[command.index("-append") + 1].split()
        self.assertIn("video=Virtual-1:720x1280-32@60e", boot_options)
        self.assertIn("DPI=320", boot_options)
        self.assertIn("virtio-multitouch-pci,id=phone-touch,display=phone-display", command)
        self.assertNotIn("usb-tablet,bus=usb.0", command)
        # Startup dimensions remain provisional; RFB negotiates the actual framebuffer.
        self.assertEqual(["--vnc-port", "5912", "--qmp-port", "45123", "--width", "640", "--height", "480",
                          "--qemu-color-order", "bgr", "--qemu-input", "touchscreen", "--qemu-display", "vnc"], extra)
        session.job.close()

    def test_qemu_invalid_display_settings_do_not_spawn_a_vm(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        defaults = dict(qemu_exe=executable, qemu_bios=True, qemu_iso=None, qemu_disk=None,
                        qemu_kernel=None, qemu_initrd=None, qemu_disk_format="qcow2",
                        qemu_accel="tcg", qemu_memory=512, qemu_cpus=1,
                        qemu_width=1080, qemu_height=1920, qemu_density=480,
                        qemu_color_order="auto", qemu_input="auto", qemu_display="vnc", qemu_gpu="auto")
        session = launcher.Session(self.project, {}, "qemu")
        for override in ({"qemu_width": 319}, {"qemu_width": 4098}, {"qemu_height": 1281},
                         {"qemu_height": 4098}, {"qemu_density": 119}, {"qemu_density": 641},
                         {"qemu_color_order": "rgb24"}, {"qemu_color_order": None},
                         {"qemu_input": "keyboard"}, {"qemu_input": None},
                         {"qemu_display": "spice"}, {"qemu_display": None},
                         {"qemu_gpu": "unsupported"}, {"qemu_gpu": None}):
            with self.subTest(override=override), \
                 mock.patch.object(launcher, "find_qemu", return_value=executable), \
                 mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
                 mock.patch.object(launcher, "free_port", return_value=45123), \
                 mock.patch.object(launcher, "wait_qmp") as ready, \
                 mock.patch.object(session, "start") as start:
                with self.assertRaises(ValueError):
                    launcher.start_qemu(session, SimpleNamespace(**(defaults | override)))
                start.assert_not_called()
                ready.assert_not_called()
        session.job.close()

    def test_qemu_explicit_color_order_overrides_the_guest_profile(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        iso = self.file("images/android.iso")
        kernel = self.file("images/android/kernel")
        initrd = self.file("images/android/initrd.img")
        cases = (("rgb", ["--qemu-kernel", str(kernel), "--qemu-initrd", str(initrd)]),
                 ("bgr", ["--qemu-bios"]))
        for order, boot_options in cases:
            with self.subTest(order=order, boot_options=boot_options):
                with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-color-order", order,
                                                     "--qemu-display", "vnc", *boot_options]), \
                     mock.patch.object(launcher, "launch") as launch:
                    self.assertEqual(0, launcher.main())
                args = launch.call_args.args[0]
                session = launcher.Session(self.project, {}, "qemu")
                process = mock.Mock()
                with mock.patch.object(launcher, "find_qemu", return_value=executable), \
                     mock.patch.object(launcher, "find_iso", return_value=iso), \
                     mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
                     mock.patch.object(launcher, "free_port", return_value=45123), \
                     mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}), \
                     mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")):
                    _, extra = launcher.start_qemu(session, args)
                self.assertEqual(order, extra[extra.index("--qemu-color-order") + 1])
                session.job.close()

    def test_qemu_parser_rejects_unsupported_color_order(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-color-order", "rgba"]), \
             mock.patch.object(sys, "stderr", new_callable=io.StringIO), \
             mock.patch.object(launcher, "launch") as launch:
            with self.assertRaises(SystemExit) as error:
                launcher.main()
            self.assertEqual(2, error.exception.code)
            launch.assert_not_called()

    def test_qemu_explicit_input_overrides_the_guest_profile(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        iso = self.file("images/android.iso")
        kernel = self.file("images/android/kernel")
        initrd = self.file("images/android/initrd.img")
        cases = (("mouse", ["--qemu-kernel", str(kernel), "--qemu-initrd", str(initrd)]),
                 ("touchscreen", ["--qemu-bios"]))
        for input_mode, boot_options in cases:
            with self.subTest(input_mode=input_mode):
                with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-input", input_mode,
                                                     "--qemu-display", "vnc", *boot_options]), \
                     mock.patch.object(launcher, "launch") as launch:
                    self.assertEqual(0, launcher.main())
                args = launch.call_args.args[0]
                session = launcher.Session(self.project, {}, "qemu")
                process = mock.Mock()
                with mock.patch.object(launcher, "find_qemu", return_value=executable), \
                     mock.patch.object(launcher, "find_iso", return_value=iso), \
                     mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
                     mock.patch.object(launcher, "free_port", return_value=45123), \
                     mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}), \
                     mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")) as start:
                    _, extra = launcher.start_qemu(session, args)
                self.assertEqual(input_mode, extra[extra.index("--qemu-input") + 1])
                command = start.call_args.args[1]
                self.assertEqual(input_mode == "touchscreen", "virtio-multitouch-pci,id=phone-touch,display=phone-display" in command)
                display = next(argument for argument in command if argument.startswith("VGA,"))
                self.assertEqual(input_mode == "touchscreen", "id=phone-display" in display.split(","))
                self.assertEqual(input_mode == "mouse", "usb-tablet,bus=usb.0" in command)
                session.job.close()

    def test_qemu_parser_rejects_unsupported_input_mode(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-input", "keyboard"]), \
             mock.patch.object(sys, "stderr", new_callable=io.StringIO), \
             mock.patch.object(launcher, "launch") as launch:
            with self.assertRaises(SystemExit) as error:
                launcher.main()
            self.assertEqual(2, error.exception.code)
            launch.assert_not_called()

    def test_gpu_auto_combines_resolved_display_and_direct_kernel_and_allows_overrides(self):
        executable = self.file("runtime/qemu-system-x86_64.exe")
        iso = self.file("images/android.iso")
        self.file("images/android/kernel")
        self.file("images/android/initrd.img")
        disk = self.file("images/phone.qcow2")
        for display, boot, gpu, expected in (("dbus", "kernel", "auto", "virtio"),
                                             ("vnc", "kernel", "auto", "vga"),
                                             ("dbus", "bios", "auto", "vga"),
                                             ("dbus", "disk", "auto", "vga"),
                                             ("dbus", "kernel", "vga", "vga"),
                                             ("vnc", "bios", "virtio", "virtio")):
            with self.subTest(display=display, boot=boot, gpu=gpu):
                media = ["--qemu-bios"] if boot == "bios" else ["--qemu-disk", str(disk)] if boot == "disk" else []
                with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-display", display,
                                                     "--qemu-gpu", gpu, *media]), \
                     mock.patch.object(launcher, "launch") as launch:
                    self.assertEqual(0, launcher.main())
                args = launch.call_args.args[0]
                session = launcher.Session(self.project, {}, "qemu")
                process = mock.Mock(pid=41001)
                with mock.patch.object(launcher, "find_qemu", return_value=executable), \
                     mock.patch.object(launcher, "find_iso", return_value=iso), \
                     mock.patch.object(launcher, "free_vnc_port", return_value=5912), \
                     mock.patch.object(launcher, "free_port", return_value=45123), \
                     mock.patch.object(launcher, "wait_qmp", return_value={"status": "running"}), \
                     mock.patch.object(session, "child_pid", return_value=42002), \
                     mock.patch.object(session, "start", return_value=(process, session.runtime / "qemu.log")) as start:
                    _, extra = launcher.start_qemu(session, args)
                command = start.call_args.args[1]
                self.assertEqual(expected == "virtio", any(argument.startswith("virtio-vga,") for argument in command))
                self.assertEqual(expected == "vga", any(argument.startswith("VGA,") for argument in command))
                self.assertNotIn("--qemu-gpu", extra)
                self.assertEqual("bgr" if boot == "kernel" else "rgb", extra[extra.index("--qemu-color-order") + 1])
                session.job.close()

    def test_qemu_parser_rejects_unsupported_gpu(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), "qemu", "--qemu-gpu", "unsupported"]), \
             mock.patch.object(sys, "stderr", new_callable=io.StringIO), \
             mock.patch.object(launcher, "launch") as launch:
            with self.assertRaises(SystemExit) as error:
                launcher.main()
            self.assertEqual(2, error.exception.code)
            launch.assert_not_called()

    def test_qemu_missing_guest_fails_before_spawning(self):
        args = SimpleNamespace(qemu_exe=None, qemu_bios=False, qemu_iso=None, qemu_disk=None,
                               qemu_color_order="auto", qemu_input="auto", qemu_display="vnc", qemu_gpu="auto")
        session = launcher.Session(self.project, {}, "qemu")
        with mock.patch.object(launcher, "find_qemu", return_value=self.project / "qemu.exe"), \
             mock.patch.object(launcher, "find_iso", return_value=None), mock.patch.object(session, "start") as start:
            with self.assertRaisesRegex(RuntimeError, "No Android-x86 ISO/disk"):
                launcher.start_qemu(session, args)
            start.assert_not_called()
        session.job.close()

    def test_qemu_stop_checks_owned_vm_identity_before_quit(self):
        for same_vm in (True, False):
            with self.subTest(same_vm=same_vm):
                session = launcher.Session(self.project, {}, "qemu")
                session.job.close.reset_mock()
                session.qemu_port, session.qemu_uuid = 45123, "owned-vm-id"
                session.qemu_process = mock.Mock()
                session.qemu_process.poll.return_value = None
                with mock.patch.object(launcher, "QmpClient") as factory:
                    control = factory.return_value.__enter__.return_value
                    control.execute.return_value = {"UUID": "owned-vm-id" if same_vm else "unrelated-vm-id"}
                    session.stop()
                    self.assertEqual(2 if same_vm else 1, control.execute.call_count)
                    control.execute.assert_any_call("query-uuid")
                    if same_vm:
                        control.execute.assert_any_call("quit")
                    session.job.close.assert_called_once()

    def test_run_aborts_when_watched_bridge_exits(self):
        session = launcher.Session(self.project, {}, "pattern")
        process = mock.Mock(pid=48293)
        process.poll.return_value = None
        bridge = mock.Mock()
        bridge.poll.return_value = 1
        with mock.patch.object(session, "start", return_value=(process, self.file("client.log"))):
            with self.assertRaisesRegex(RuntimeError, "bridge exited"):
                session.run("minecraft", ["mock"], watch=[("bridge", bridge)])

    def test_stop_closes_owned_job_and_waits_only_tracked_processes(self):
        session = launcher.Session(self.project, {}, "pattern")
        exited, first, last = mock.Mock(pid=48101), mock.Mock(pid=48102), mock.Mock(pid=48103)
        exited.poll.return_value = 0
        first.poll.return_value = last.poll.return_value = None
        session.processes = [("exited", exited), ("bridge", first), ("minecraft", last)]
        session.stop()
        session.job.close.assert_called_once_with()
        exited.wait.assert_called_once()
        first.wait.assert_called_once()
        last.wait.assert_called_once()
        exited.kill.assert_not_called()
        first.kill.assert_not_called()
        last.kill.assert_not_called()

    def test_cleanup_continues_if_one_process_wait_times_out(self):
        session = launcher.Session(self.project, {}, "pattern")
        bridge, game = mock.Mock(pid=48201), mock.Mock(pid=48202)
        bridge.poll.return_value = game.poll.return_value = None
        game.wait.side_effect = [subprocess.TimeoutExpired("minecraft", 10), 0]
        session.processes = [("bridge", bridge), ("minecraft", game)]
        session.stop()
        game.kill.assert_called_once_with()
        bridge.wait.assert_called_once_with(timeout=10)

    def test_cleanup_tries_remaining_processes_before_reporting_unrecoverable_failure(self):
        session = launcher.Session(self.project, {}, "pattern")
        bridge, game = mock.Mock(pid=48211), mock.Mock(pid=48212)
        game.wait.side_effect = subprocess.TimeoutExpired("minecraft", 10)
        session.processes = [("bridge", bridge), ("minecraft", game)]
        with self.assertRaisesRegex(RuntimeError, "minecraft"):
            session.stop()
        bridge.wait.assert_called_once_with(timeout=10)


if __name__ == "__main__":
    unittest.main()
