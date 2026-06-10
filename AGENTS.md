# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android project named `NB4A`; `settings.gradle.kts` includes only `:app`. Kotlin and Java sources live in `app/src/main/java`, AIDL contracts in `app/src/main/aidl`, Android resources in `app/src/main/res`, and bundled runtime assets in `app/src/main/assets`. Room schema snapshots are stored in `app/schemas` and should be updated with database migrations. The Go-based core is under `libcore`, with related build scripts in `buildScript/lib`. Shared Gradle setup lives in `buildSrc/src/main/kotlin/Helpers.kt`.

External modules:
- `../sing-box` - proxy core source specified in `libcore`'s `go.mod`, access without sandbox is ALLOWED.
- `../amneziawg-go` - AmneziaWG fork used in `../sing-box`, read-only access unless task requires changes there.
- `../libneko` - used by `libcore`, read-only access unless task requires changes there.

Any `../sing-box` edits are ALLOWED EXPLICITLY when task requires to do so.

## Build, Test, and Development Commands

### Android app

- `./gradlew assembleOssDebug` builds a local debug APK for the `oss` flavor.
- `./gradlew assembleFdroidRelease` builds the F-Droid release variant; release signing uses `local.properties` or matching environment variables.
- `./gradlew lintOssDebug` runs Android lint. Lint is configured with all warnings enabled and warnings treated as errors.
- `./gradlew clean` removes Gradle build outputs.
- `bash buildScript/lib/core.docker.sh` rebuilds `libcore` in Docker, as documented in `BUILDING.md`.

Use Android Studio for device runs and debugging, selecting the needed flavor (`oss`, `fdroid`, `play`, `plus`, or `preview`).

Run ALL builds without sandbox and with Android Studio's Java:
- `/opt/android-studio/jbr` - contains `bin` directory with all java-related tools

### Android Virtual Device workflow

Codex is explicitly allowed to use the locally configured Android Virtual Device for deploy-and-test loops. This includes starting the emulator, installing debug builds, driving the UI, changing app configuration, taking screenshots, reading the rendered UI hierarchy, using `adb`, `logcat`, emulator console/serial access, and repeating rebuild/redeploy/retest cycles as needed. Keep device inspection scoped to this app and the active test; do not dump broad unrelated logs or device data.

Debug info:
- Main debug app id: `com.nb4a.plus.debug` (`PACKAGE_NAME=com.nb4a.plus` plus debug suffix `debug`)
- Useful tools: `~/Android/Sdk/emulator/emulator`, `~/Android/Sdk/platform-tools/adb`, `~/Android/Sdk/cmdline-tools/latest/bin/avdmanager`

Use the currently configured AVD only. Before emulator work, verify it exists:
```
export ANDROID_HOME=~/Android/Sdk
export ANDROID_SDK_ROOT=~/Android/Sdk
export JAVA_HOME=/opt/android-studio/jbr
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

emulator -list-avds
```
If there are more than one AVD's - use the active one. If multiple are active - use the first active. 

To start the configured AVD when it is not already active:
```
nohup "$ANDROID_HOME/emulator/emulator" -avd AVD_NAME > /tmp/nb4a-emulator.log 2>&1 &
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 2; done
adb devices -l
```

Build and deploy the debug app without sandbox:
```
JAVA_HOME=/opt/android-studio/jbr ./gradlew installOssDebug
adb shell monkey -p com.nb4a.plus.debug 1
```
If the Gradle install task is unavailable, build then install the APK explicitly:
```
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleOssDebug
adb install -r app/build/outputs/apk/oss/debug/*.apk
adb shell monkey -p com.nb4a.plus.debug 1
```

Manual checks may use:
```
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 DURATION_MS
adb shell input text 'escaped_text'
adb shell input keyevent KEYCODE_BACK
adb exec-out screencap -p > /tmp/nb4a-screen.png
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml /tmp/nb4a-window.xml
adb logcat -c
adb shell pidof com.nb4a.plus.debug
adb logcat -d -v time --pid="$(adb shell pidof com.nb4a.plus.debug | tr -d '\r')"
```
Prefer targeted `logcat` by app pid or relevant tags after clearing the buffer; avoid collecting large unrelated logs. Use screenshots and `uiautomator` XML together to detect obvious rendering, navigation, and layout problems. Low-level `adb shell`, emulator console, and serial-style debugging are allowed when useful, but keep commands focused on the app/debugging task.

The intended loop after app changes is:
1. Build the relevant debug variant.
2. Start or reuse AVD.
3. Deploy the debug APK.
4. Exercise the changed behavior manually with clicks/text/config changes.
5. Inspect screenshots, UI hierarchy, and targeted logs/metrics.
6. If bugs or incorrect behavior are found, update the code and repeat the loop.

### libcore

Should be rebuilt without sandbox and only when changes were made either to `libcore` or to `../sing-box` or both.

If `../sing-box` was not changed - simply rebuild like this:
1. Run `bash buildScript/lib/assets.sh` only ONCE in the session.
2. Run this command to rebuild and wait for it to finish:
```
bash buildScript/lib/core.docker.sh
```

IMPORTANT: If `../sing-box` was changed - update the version in `../sing-box` before running command above:
1. Apply the changes you need in `../sing-box` and verify them using `go build` & `go test`.
2. After doing that, make a commit with those changes inside `../sing-box`.
3. Read current build number here, in Android app dir using this command:
```
sed -n 's/^VERSION_NAME=.*-\([0-9]\+\)$/\1/p' nb4a.properties
```
4. Update build in `var Version` in `../sing-box/constant/version.go` by replacing the last number after dash with the number you've got in step 3.
5. Make a commit with text "chore: bump version" and get it's full hash.
6. Update `buildScript/lib/core/get_source_env.sh`, replace old `COMMIT_SING_BOX` value with the one you've got in step 5.

After that you can rebuild libcore.

## Coding Style & Naming Conventions

Kotlin uses the official Kotlin style (`kotlin.code.style=official`) with JVM target 1.8. Follow existing package organization under `io.nekohasekai.sagernet`; keep class names in `UpperCamelCase`, methods and properties in `lowerCamelCase`, and XML resources in `lower_snake_case`. Prefer existing helpers and patterns in nearby files before adding new abstractions. Do not commit local secrets from `local.properties`.

## Testing Guidelines

There are currently no dedicated `app/src/test` or `app/src/androidTest` directories. When adding tests, use standard Android locations: JVM tests in `app/src/test` and instrumentation tests in `app/src/androidTest`. Name test classes after the unit under test, for example `ProfileManagerTest`. Run relevant checks with `./gradlew testOssDebugUnitTest` for JVM tests and `./gradlew connectedOssDebugAndroidTest` for device tests.

### Manual subscription testing

Use `subs.json` for AVD testing when subscription data is needed. This file is intentionally ignored by git and must never be committed, printed, summarized, or shared. Only read the minimum needed fields locally during a test.

`subs.json` contains an array of objects with:
- `type`: `normal` for stable working servers, or `flaky` for subscriptions that may include dead/down servers.
- `url`: subscription URL to import.

Manual usage in the app:
1. Launch the app on AVD.
2. If the app asks for country on first start, choose `Other`, `Russia`, or `China`; this only affects Routing defaults.
3. Open Groups activity.
4. Put one selected `url` from `subs.json` into the AVD/app clipboard or enter it into the subscription import flow without exposing it in chat or logs.
5. Use import from clipboard in Groups activity.
6. Update the new group servers and verify the behavior under test.

For clipboard/input, prefer a host clipboard bridge if available (`wl-copy` exists on this machine), or use carefully escaped `adb shell input text` only when it will not corrupt the URL. Do not add helper files containing subscription URLs to the repository.

## Commit & Pull Request Guidelines

Use short conventional-style subjects such as `fix: tighten VpnService teardown` or `feat: decouple traffic updates from notification`. Keep commits focused and use `fix:`, `feat:`, `chore:`, or similar prefixes. Pull requests should describe the behavior change, list the variants tested, link related issues when available, and include screenshots for visible UI changes.

## Security & Configuration Tips

Signing values are read from `local.properties` or environment variables including `KEYSTORE_PASS`, `ALIAS_NAME`, and `ALIAS_PASS`. Keep keystore credentials out of commits. Treat bundled assets and generated database schemas as reviewable artifacts, not incidental build output.
