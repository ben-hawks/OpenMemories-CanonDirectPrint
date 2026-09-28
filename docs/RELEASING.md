# Releasing

Releases are built and published by `.github/workflows/release.yml` when a
`v*` tag is pushed. It runs the unit tests, builds both apps in release mode,
and publishes a GitHub release with the two APKs, `SHA256SUMS.txt`, and
`docs/releases/<tag>.md` as the release notes.

## Making a release

1. Set the same `versionName` in `app/build.gradle` and `bridge-android/build.gradle`,
   and increase `versionCode` in both (Android only installs an update with a higher code).
2. Write `docs/releases/v<version>.md`.
3. Commit, push to `main`, and wait for CI.
4. Tag and push: `git tag v<version> && git push origin v<version>`.

The workflow refuses to publish if the tag does not match both `versionName`s
or if the release notes are missing.

## Signing key (set up once)

Android only installs an update if it is signed with the same key as the
installed app. Without a fixed key, the workflow signs with a throwaway debug
key that is different on every run, so each release would have to be
uninstalled before installing the next. To sign every release with the same key:

```sh
keytool -genkeypair -keystore release.jks -alias canondirectprint \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=CanonDirectPrint"
```

Encode it as a single line of plain base64. Line breaks or `-----BEGIN` header
lines make the workflow fail with `base64: invalid input`.

```sh
# macOS / Linux
openssl base64 -A -in release.jks -out release.jks.b64
# check: decodes back to a keystore that lists the alias
openssl base64 -d -A -in release.jks.b64 -out check.jks && keytool -list -keystore check.jks
```

```powershell
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content -NoNewline release.jks.b64
```

Copy the file straight to the clipboard instead of from the terminal: the
file has no trailing newline, so when it is printed your shell prompt (for
example conda's `(base)`) follows on the same line and is easily copied
along, which breaks the secret.

```sh
pbcopy < release.jks.b64                          # macOS
xclip -selection clipboard < release.jks.b64      # Linux
```

```powershell
Get-Content -Raw release.jks.b64 | Set-Clipboard  # Windows
```

Add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `canondirectprint` |
| `SIGNING_KEY_PASSWORD` | the key password (the same as the keystore password unless you chose otherwise) |

If a release run fails at the *Signing key* step, fix the secret and use
**Re-run all jobs** on that run: the tag does not need to be pushed again.

Keep `release.jks` and its password somewhere safe and out of the repository.
If the key is lost, users have to uninstall the apps to install new releases.
Local builds use the same mechanism through the `SIGNING_*` environment
variables (see `gradle/release-signing.gradle`).
