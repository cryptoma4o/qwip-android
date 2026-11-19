# QWIP VPN for Android

Native Kotlin and Jetpack Compose client for Android 9 and later. It imports an existing HTTPS subscription, Xray JSON or a node link. You can use your own provider. To import from the QWIP cabinet, select the device, open **Открыть в QWIP** or scan its QR code in the app, review the subscription and confirm. Select a location, tap Connect and accept Android's VPN permission.

QWIP uses Android VpnService and libXray. The app keeps profiles in a device-local encrypted vault. The subscription provider receives requests when its profiles are imported or refreshed. VPN and DNS servers handle the traffic sent to them. Optional endpoint checks contact the selected test sites. [Privacy](https://qwipvpn.com/client/privacy) · [Help](https://qwipvpn.com/client/help).

## Build from source

Use JDK 17, Android SDK 36, NDK 29.0.13599879, Go 1.27.3 and Python 3.12 or later. Set JAVA_HOME and ANDROID_HOME for your machine. Pass the bin directory of the pinned Go installation.

    python3 tools/build_engine.py --go-bin /absolute/path/to/go1.27.3/bin --sdk "$ANDROID_HOME"
    python3 tools/verify_engine.py
    ./gradlew :core:test :app:lintRelease :app:assembleDebug :app:bundleRelease

The engine builder downloads the pinned public sources, verifies their hashes and compiles the AAR for four ABIs. The Gradle wrapper pins its distribution checksum. The release bundle is written to app/build/outputs/bundle/release/app-release.aab. CI builds from the tagged sources using public dependencies. The test APK has a locally generated debug signature.

For Google Play, build the selected tag with your own upload key. The Gradle release configuration reads QWIP_RELEASE_KEYSTORE (an absolute file path), QWIP_RELEASE_KEY_ALIAS, QWIP_RELEASE_STORE_PASSWORD and QWIP_RELEASE_KEY_PASSWORD from the build environment. Play App Signing uses the published app-signing certificate for Android App Links. The application ID is com.qwipvpn.app.

## Emulator verification

Use a dedicated Android 15 arm64 emulator named `Codex_QWIP_API35`. From `tools/fixture`, run `go build -o ../../Support/fixture-server .` with the pinned Go version. From the repository root run `python3 tools/start_fixture.py` and keep it running. Build the test APK with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, then run `python3 tools/test_emulator.py --serial emulator-5582 --adb-port 5051 --adb /path/to/adb`, substituting the emulator and adb port you started. The fixture uses ephemeral credentials and loopback on the host. The tests exercise JNI, routing, real VpnService, the cabinet link and the connection consent screen.

## Upstream and licenses

Derived from Solafon's MIT-licensed Android client, revision 15a62ea39a6edc286942a94fdd65a2a79299b79f. The original copyright and permission notice are in [LICENSE](LICENSE). The original QWIP changes use MIT. The engine is [libXray](https://github.com/XTLS/libXray) (MIT) with [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0). [Exact engine sources](Config/Engine.lock.json), [GeoData pins](Config/GeoData.lock.json) and [third-party notices](ThirdParty/) identify separately licensed code and data. Engine updates ship as application updates.

## QWIP subscription links

Both clients accept the standard HTTPS subscription or an official link of the form https://www.qwipvpn.com/open#d/TOKEN, where TOKEN denotes a 64-character lowercase hexadecimal subscription token. The app converts it locally to a QWIP Xray JSON feed after user confirmation. Legacy account feeds use s in place of d. Do not include an actual link in an issue or screenshot.
