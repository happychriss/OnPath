# Test signing key

`onpath-test.keystore` signs all debug/test builds (standard debug passwords: `android` / alias `androiddebugkey`).
It is committed on purpose so a test APK built anywhere (cloud session, CI, a laptop) installs as an **update**
over the previous one on the owner's phone – Android refuses updates signed with a different key, and the
only fix would be uninstalling (losing tours).

This key is public and must **never** be used for Google Play releases; create a separate release key
(kept secret, e.g. Play App Signing) when publishing.
