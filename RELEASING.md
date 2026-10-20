# Releasing

## The signing key

The release build is signed with a key that **must never be lost**: Android will only install an
update over an app if it is signed with the same key. Lose it and every future version needs a new
application ID — that is a different app to the phone, with no library carried over.

On this machine the key is here, outside the repository:

```
C:\Users\cristifcp\AndroidStudioProjects\MoonlightPages-keystore\moonlight-release.jks
```

and it is named in `keystore.properties` in the project root, which `.gitignore` keeps out of the
repository. Its password is in that same file.

**Keep two copies of both the `.jks` and its password somewhere that is not this PC** — a password
manager, or an encrypted archive in cloud storage. Without them, no update can ever be published.

Without `keystore.properties`, `assembleRelease` still succeeds; it simply produces an unsigned APK.
That is what a fresh clone and the build server get unless the key is provided, so the project always
builds.

## Building a release by hand

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Check the signature before
publishing:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Checking a release build before publishing

The release build is shrunk and rewritten, so it can fail where an ordinary build does not — that is
exactly how the first 1.0 build came to crash on opening: the optimizer had repackaged Kotlin's own
classes, which is why `packageScope` is no longer set.

Build it as a separate app beside your library and try it:

```bash
./gradlew :app:assembleRelease -Psmoke
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-Psmoke` adds `.smoke` to the application ID, so it installs *alongside* Moonlight Pages instead of
replacing it, with its own empty library — it offers the bundled Moby Dick on first run, which is
enough to prove import, layout and reading in the shipped build. When done:

```bash
adb uninstall com.moonlightpages.app.smoke
```

and build again without `-Psmoke` before publishing, so the artifact carries the real ID.

## Publishing

The release workflow runs on every tag beginning with `v`, builds the APK, checks its signature and
attaches it to a GitHub release.

```bash
# 1. Write the release notes at the top of CHANGELOG.md
# 2. Raise versionCode and versionName in app/build.gradle.kts
# 3. Commit, then tag and push the tag
git commit -am "Release 1.1"
git tag v1.1
git push
git push origin v1.1
```

The workflow needs the key in two repository secrets, under **Settings → Secrets and variables →
Actions**:

- `RELEASE_KEYSTORE_BASE64` — the `.jks` file, base64-encoded
- `RELEASE_KEYSTORE_PASSWORD` — its store password (the key password is the same)

To produce the base64 on Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(
  "C:\Users\cristifcp\AndroidStudioProjects\MoonlightPages-keystore\moonlight-release.jks"))
```

and on macOS or Linux:

```bash
base64 -w0 moonlight-release.jks     # macOS: base64 -i moonlight-release.jks
```

Without those secrets the workflow stops and says what is missing, rather than publishing an
unsigned APK.

## The application ID

`com.moonlightpages.app` is permanent from the first release. Changing it later makes the app a
different app to Android: no updates, and no library carried over.
