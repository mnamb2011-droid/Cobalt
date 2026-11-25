# Build status

Source project created. The current execution environment does not contain Android SDK / `android.jar`, so a local APK compile could not be performed here. A GitHub Actions workflow is included to compile `app-debug.apk` on a standard Ubuntu runner with Android SDK 35 and JDK 17.

Do not treat this source ZIP as an installable APK. The APK must be produced by the included workflow or a local Android SDK build, then installed and paired with the actual television for hardware verification.
