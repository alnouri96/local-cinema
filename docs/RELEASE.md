# Local Cinema release procedure

Read [../AGENTS.md](../AGENTS.md) first. This procedure covers Android builds, ADB verification, and publication to `alnouri96/local-cinema` (`main`) plus handoff to `MEEDX WEB`.

## Inputs and release records

- **Official Repository:** `alnouri96/local-cinema` (`main`) holds both Android source code, Git tags, and public GitHub Release APKs (`LocalCinema-TV-<tag>.apk`, `LocalCinema-Manager-Phone-<tag>.apk`, `checksums.txt`).
- **OTA Channel:** `https://meedx.pages.dev/api/v2/version.json` (allowlist strictly restricted to `github.com/alnouri96/local-cinema` and `meedx.pages.dev`).
- **Versioning Roadmap:** `versionCode 19` (`v1.0.0-beta.19`) is the final beta release. The next release (`versionCode 20`) will be the official stable release **`v1.0.0`** (`versionName = "1.0.0"`).
- `CHANGELOG-CINEMA.md` holds the bilingual release history. `version.json` holds the current distribution metadata.
- `releases/v<version>/checksums.txt` identifies the exact published files. Keep the release signing certificate and Android package IDs stable.

## Build and stage both Android packages

1. Use JDK 17 and the Android SDK with Build Tools. Ensure private signing configs (`Video/keystore.properties`, `Phone/key.properties`) are present.
2. Run Gradle commands **sequentially** (never in parallel) with `--no-daemon --max-workers=2`:

~~~powershell
# 1. Video/
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleNoamazonRelease -x :FileCoreLibrary:ndkBuild -x :MediaLib:ndkBuild --no-daemon --max-workers=2 --console=plain

# 2. Phone/
.\gradlew.bat :app:assembleBaseRelease --no-daemon --max-workers=2 --console=plain
~~~

3. Stage and verify certificates + key audit from the repo root:

~~~powershell
.\script\stage-clean-release.ps1 -Version <version> -ForRelease
~~~

   The script checks package names, ABIs (`armeabi-v7a` for TV, `arm64-v8a` for Phone), version code/name, signer certificate continuity, and `python script/audit-provider-keys.py` (`PASS`), writing the staged APKs and `checksums.txt` to `releases/v<version>/`.

## Verify both devices via ADB

1. Run `adb connect <IP:PORT>` and `adb devices -l`.
2. Install in place without wiping data: `adb -s <device> install -r --user 0 <apk>`.
3. Launch the resolved launcher activity and confirm zero crashes in `adb -s <device> logcat -d -b crash`.

## Publish an approved release

1. Update `version.json` from the staged `releases/v<version>/` pair using exact `sizeBytes`, `sha256`, and `https://github.com/alnouri96/local-cinema/releases/download/v<version>/...` URLs.
2. Commit and push to `origin main` in `alnouri96/local-cinema`.
3. Create the GitHub Release on `alnouri96/local-cinema` (`--latest --prerelease=false`):

~~~powershell
gh release create $tag $tvApk $phoneApk $checksums --repo alnouri96/local-cinema --target main --title "Local Cinema $tag" --notes-file $notes --latest --prerelease=false
~~~

4. In sibling `G:/MEEDX WEB`, update `src/data/catalog.json` patch notes, run `npm run sync:release`, `npm run verify -- --published`, `npm run build`, push `main`, deploy to Cloudflare Pages (`npx wrangler pages deploy dist --project-name=meedx --branch=main`), and verify live with `npm run verify -- --live`.
5. Migrate the active patch summary from `HANDOFF.md` into `CHANGELOG-CINEMA.md`, update the published release version in `AGENTS.md` and `HANDOFF.md`, and reset the `Active Unreleased Patch` section in `HANDOFF.md` to empty.
