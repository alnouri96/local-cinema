# Local Cinema release procedure

Read [../AGENTS.md](../AGENTS.md) first. This procedure covers Android builds, ADB checks, and the handoff to the website. Read `docs/RELEASE.md` in the sibling MEEDX WEB checkout for the website procedure.

## Inputs and release records

- Build source and Git tags live in the public `alnouri96/local-cinema` repository. Track the publication source by commit ID.
- Publish APKs through public GitHub Releases in `alnouri96/meedx`. The website and OTA clients use those public URLs.
- `CHANGELOG-CINEMA.md` holds the bilingual Local Cinema release history. `version.json` holds the current distribution metadata.
- `releases/v<version>/checksums.txt` identifies the exact published files. Keep the TV APK, Phone APK, and manifest together.
- Keep SDK paths, ADB addresses, signing configuration, and credentials in local settings. Keep the release signing certificate and Android package IDs stable.

## Build and stage both Android packages

1. Use JDK 17 and the Android SDK with Build Tools. Check that the private signing configuration is present for both apps. On a fresh Windows checkout, run `.\script\build-cinema.ps1 -NativeOnly` from the monorepo root to populate the pinned TV ARMv7 libraries.
2. Run the TV command from `Video/` and the Phone command from `Phone/`.

~~~powershell
# Video/
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleNoamazonRelease -x :FileCoreLibrary:ndkBuild -x :MediaLib:ndkBuild --console=plain

# Phone/
.\gradlew.bat :app:assembleBaseRelease --console=plain
~~~

3. From the root, stage with `.\script\stage-clean-release.ps1 -Version <version>`. It checks package names, ABIs, versions, certificates, and known provider credentials. It writes the two APKs and `checksums.txt` to `releases/_local/`.
4. For an approved release, increase `versionCode` and set the same new `versionName` in both Gradle files before building. Stage with `-ForRelease` while `version.json` still identifies the previous published release. The script creates a new `releases/v<version>/` directory.
5. Keep published files immutable. If a local build differs under an existing version, use `releases/_local/` for testing. Give new public binaries a new version and tag. The local `beta.8` and `beta.9` folders contain historical local builds with different hashes from GitHub.

## Verify both devices

1. Run `adb devices -l`. If needed, connect to the TV or Phone with `adb connect <IP:PORT>`. On Android wireless debugging, use the connection address from the main screen after pairing.
2. Review the crash buffer before installation. Wait for active playback to finish. Install the audited candidate in place with `adb -s <device> install -r --user 0 <staged-apk>`.
3. Resolve the launcher with `adb -s <device> shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c <category> <package-id>`. Use `android.intent.category.LEANBACK_LAUNCHER` for TV and `android.intent.category.LAUNCHER` for Phone.
4. Launch the resolved component with `adb -s <device> shell am start -W --user 0 -n <component>`. Check that the expected home or TV Hub opens. Exercise the behavior you changed and use `adb -s <device> logcat -d -b crash` to check for new crashes.

When the owner reserves UI testing, skip the launcher and UI steps. Install in place, verify the installed package version, and compare the crash buffer before and after installation without clearing it.
5. If a device is unavailable, record the missing check and keep the candidate local. Preserve the device's existing logs and data.

## Hand off a publishable release

1. Confirm the user authorized publishing the exact version. Verify both device checks and retain the previous known-good release.
2. Write the Arabic and English notes in `CHANGELOG-CINEMA.md`. Apply the repository prose rules. Wrap the Arabic GitHub release section in `<div dir="rtl" align="right">…</div>` and begin each Arabic bullet with an Arabic word.
3. Update `version.json` from the staged pair. Use the file lengths, `checksums.txt`, package version and English notes. Use exact public APK URLs for the new tag.
4. Prepare the website and publish only through its release procedure. Record the exact Android source commit in the website release notes or handoff. Link to the corresponding public source tag in the release notes.
5. Publish each approved public build as a regular GitHub Release with `--latest`. Keep any beta suffix in the tag and app version. GitHub excludes pre-releases from Latest. Use the public Downloads page for evergreen links and keep previous releases and tags addressable.

After publishing, run `npm run verify -- --live` in MEEDX WEB. It checks the public Latest tag, published assets, live OTA, and both APK redirects against the local release manifest. Verify via `gh api` that `alnouri96/local-cinema` keeps `upgrade-6.6.4` as its sole default branch with no stale branches or dead tool artifacts. Update the version and date in AGENTS.md. Record exceptions or unfinished checks in [CONTINUATION.md](CONTINUATION.md).
