# Windows Android Emulator setup

The first prototype uses the Android SDK Emulator, which is based on QEMU. It runs Android on the host and exposes frames and input through a local bridge. Minecraft does not execute Android guest code itself.

## Check this computer

From the project directory, run:

```powershell
.\scripts\doctor.ps1
```

The script discovers executables on `PATH`, checks the Android SDK environment variables and the standard SDK directory, lists AVD metadata, and runs the emulator's read-only acceleration check. Optional `-Emulator`, `-Adb`, `-Ffmpeg`, `-Python`, and `-AvdHome` parameters override discovery. It does not download packages, edit AVD configuration, or change Windows features.

Observed on the development host on 2026-09-07:

| Component | Observed value |
| --- | --- |
| Android Emulator | `E:\AndroidSdk\emulator\emulator.exe`, version 37.1.11.0 |
| Android SDK | `E:\AndroidSdk` |
| ADB | `E:\Program Files\platform-tools\adb.exe` |
| FFmpeg | `E:\Program Files\ffmpeg\bin\ffmpeg.exe`, build 2026-01-29 |
| Python | Python 3.13.7; its global environment lacked `grpcio` |
| Hardware acceleration | `WHPX(10.0.26200) is installed and usable` |
| Existing AVD | `Medium_Tablet`, Android 35, x86_64, Google Play tablet |
| Native display | 2560 × 1600 landscape, 320 dpi |
| AVD resources | 4 virtual CPUs, 1907 MB RAM |
| System image | Installed under `system-images\android-35\google_apis_playstore_tablet\x86_64` |

`emulator -list-avds` initially returned no names on this host. Setting `ANDROID_AVD_HOME` explicitly to the existing `%USERPROFILE%\.android\avd` resolved discovery. Both scripts do this for their child commands and restore the caller's environment afterward.

## Start the existing AVD

```powershell
.\scripts\start-emulator.ps1 -Avd Medium_Tablet
```

If the executable is not on `PATH`:

```powershell
.\scripts\start-emulator.ps1 -Avd Medium_Tablet -Emulator 'E:\AndroidSdk\emulator\emulator.exe'
```

The script launches a hidden, headless emulator with audio and cameras disabled. It uses `-read-only`, `-no-snapshot-load`, and `-no-snapshot-save` so the experiment does not persist guest disk or snapshot changes. The emulator still needs normal permission to create temporary lock files in its AVD directory. An agent sandbox can block those files even in read-only mode; launching the same command through the normal authorized host environment resolved that during development.

The default console/ADB serial is `emulator-5556`; the default gRPC endpoint is `127.0.0.1:8554`. Override `-ConsolePort` (an even number) and `-GrpcPort` when needed. Occupied ports cause an error; the script does not terminate an existing emulator. GPU mode defaults to `auto`, with `-Gpu host` and `-Gpu software` available when testing another graphics backend.

Launch metadata goes to `.runtime/emulator.json`, including the launcher PID, actual emulator PID, discovery-file path, and logs. The launcher PID and actual QEMU process PID can differ. gRPC discovery being ready does not mean Android has finished booting. The first verified launch finished booting in approximately 41 seconds and selected SwiftShader graphics automatically.

The actual launch log confirmed `Started GRPC server at 127.0.0.1:8554, security: Local, auth: +token`; the Windows TCP listener was also verified as loopback only.

## Connect the bridge

Pass the saved `grpc_discovery_file` to the bridge's `--grpc-discovery-file` option. The bridge reads the bearer credential directly from that INI file. Do not paste its contents into source control, screenshots, logs, or chat. Launch metadata contains the file path, not the token.

This prototype selects `-grpc-use-token` because the installed emulator and bridge support it. Android's gRPC documentation marks this static bearer-token mode as deprecated. A future version should generate and refresh signed JWTs through `-grpc-use-jwt`. Do not remove authentication and leave only `-grpc`: that changes the emulator's security behavior.

Match the bridge's native display settings to the AVD (`--width 2560 --height 1600` for this tablet). To test a different resolution, use an AVD configured with that native display size; this bridge does not resize screenshots. The official service supports RGB screenshots, screenshot streaming, touch events, and key events. Touch coordinates use the native display coordinates; each active touch identifier must eventually receive pressure zero. Although the bundled protocol comment describes RGB storage as bottom-up, SDK 37.1.11.0 was visually verified to return top-down rows. The bridge defaults to top-down; `--rgb-bottom-up` supports implementations that require flipping.

During this run ADB reported the device as unauthorized. Authenticated gRPC is independent of that ADB authorization and is the intended frame/input transport. No ADB server reset or device authorization changes were made to resolve that condition.

## Stop only the experiment

Use the exact emulator serial saved by the launch:

```powershell
$run = Get-Content .\.runtime\emulator.json -Raw | ConvertFrom-Json
adb -s $run.serial emu kill
```

If ADB authorization prevents shutdown, inspect the saved process ID and start time before stopping that process. Do not use a command that kills all `emulator.exe` or QEMU processes: another instance may belong to an unrelated task.

## Official references

- [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline): launching AVDs and headless/snapshot options.
- [Hardware acceleration](https://developer.android.com/studio/run/emulator-acceleration): WHPX configuration and `-accel-check`.
- [AOSP gRPC security documentation](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/docs/): bearer tokens, signed JWTs, discovery, and local transport.
- [AOSP EmulatorController protocol](https://android.googlesource.com/platform/external/qemu/+/89b154f521ba3ba13406d60c47c4c01da8ff219e/android/android-grpc/emulator_controller.proto): screenshot and input semantics. The installed SDK also includes its matching `emulator/lib/emulator_controller.proto`.

The gRPC interface is experimental. Prefer the protocol shipped with the installed emulator when resolving differences between versions.
