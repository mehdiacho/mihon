# Fork notes

This is a personal fork of [mihonapp/mihon](https://github.com/mihonapp/mihon).
It exists to add behaviour upstream does not want, while staying close enough to
`upstream/main` that merging releases stays a non-event.

## Git layout

| Remote | Points at |
| --- | --- |
| `upstream` | `mihonapp/mihon` — read-only, never pushed to |
| `origin` | `mehdiacho/mihon` — the personal fork, where `custom` is pushed |

Work happens on the long-lived `custom` branch. `main` is left alone as a clean
mirror of upstream so a merge is always `custom` ← `main`:

```bash
git checkout main && git fetch upstream && git merge --ff-only upstream/main
git checkout custom && git merge main
```

## Rules that keep merging cheap

The whole point of the structure below is that a fork diff which only *adds*
files, and touches existing ones in a handful of well-separated places, almost
never conflicts. In rough order of preference:

1. **Add a file rather than edit one.** Anything that can live in
   `app/src/custom/` (a build-type source set) costs zero merge risk, because
   upstream has no such directory and will never write to it.
2. **When an existing file must change, change it in one contiguous block** with
   a comment saying why. Scattered one-line edits across a file are what
   actually causes conflicts.
3. **Prefer a new build type over editing shared config.** Upstream already has
   four (`debug`, `release`, `foss`, `nightly`, `benchmark`); a fifth sits
   alongside them instead of modifying them.
4. **Don't reformat, don't reorder imports, don't "fix" unrelated style.**
   Every touched line is a future conflict.

## What this fork changes

### 1. `custom` build type — `app/build.gradle.kts`

A fifth build type, `custom`, derived from `release`:

- `applicationId` = `app.mihon.custom`, so it installs *alongside* upstream
  stable (`app.mihon`) and preview (`app.mihon.debug`) rather than replacing
  either. All three can be on the device at once.
- `versionNameSuffix` = `-custom-<commit count>`.

### 2. Launcher colourway — `app/src/custom/res/`

Upstream distinguishes stable from preview purely by icon colour, via a
`src/debug/res` overlay. This fork follows the same pattern with its own
directory, so the three installs are told apart at a glance:

| Build | Accent | Background |
| --- | --- | --- |
| stable | `#0058A0` dark blue | `#FAFAFA` white |
| preview (`nightly`) | `#7EBBED` light blue | `#2E3943` slate |
| **`custom`** | **`#F5A524` amber** | **`#1C1B1F` charcoal** |

`app_name` is overridden to `Mihon Custom` in the same source set, so the
launcher labels differ too. The app module's resources win over the `:i18n`
module's, and a build type's resources win over `main`'s.

To change the colour, edit two files and nothing else:
`app/src/custom/res/values/colors.xml` and
`app/src/custom/res/drawable/ic_launcher_background.xml`.

### 3. ABI splits — `app/build.gradle.kts`

Upstream ships five APKs (four ABIs plus a universal). Only `arm64-v8a` is ever
installed here. Pass `-PallAbis` to restore the full upstream matrix, which is
needed when producing artifacts for an upstream PR.

Measured, on this machine: the saving is ~42 s of a ~4.5 min warm build (~14%),
*not* the bulk of it. The change is kept for artifact clarity — one APK in
`outputs/apk/`, no ambiguity about which to install — rather than for speed.
The real cost of a cold build is Kotlin compile and R8.

## Signing

There is no `keystore.properties` and no fork keystore. Every local build falls
back to the generic Android debug key (`~/.android/debug.keystore`,
`CN=Android Debug, O=Android, C=US`).

This is fine *specifically because* `app.mihon.custom` is a new applicationId
that has never been installed under any other key. The first install defines the
signature, and every later `adb install -r` matches it. Contrast `app.mihon.debug`,
which is already installed from upstream's signed preview releases — a local
build of that package fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**The catch:** the debug keystore is per-machine and expires (1 year by default,
or whenever the file is regenerated). If it is ever lost or rotated, updates to
an installed `app.mihon.custom` stop working and the app must be uninstalled and
reinstalled, losing its data unless a backup is restored. Generating a dedicated
long-lived keystore avoids that:

```bash
keytool -genkey -v -keystore fork.keystore -alias mihon-custom \
        -keyalg RSA -keysize 2048 -validity 10000
```

then point `keystore.properties` at it (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). That file is already gitignored upstream. Do this
*before* the first install if it is going to be done at all, otherwise it means
a reinstall.

## Updater

`Config.enableUpdater` is false unless `-Penable-updater` is passed, so local
builds never hit the update checker and `AppUpdateChecker.kt` needs no fork
change. If the updater is ever enabled, note `GITHUB_REPO` there resolves to
`mihonapp/mihon-preview` for the `nightly` build type only — `custom` falls
through to `mihonapp/mihon`, which would offer upstream stable builds that
cannot install over this package. Repoint it before enabling.

## Building

```bash
./gradlew assembleCustom
```

Output: `app/build/outputs/apk/custom/app-arm64-v8a-custom.apk`.

```bash
adb install -r app/build/outputs/apk/custom/app-arm64-v8a-custom.apk
```

The *first* install of a new package over adb is refused by HyperOS with
`INSTALL_FAILED_USER_RESTRICTED` and needs a physical confirmation on the
device. Re-installs over the top afterwards are unattended.
