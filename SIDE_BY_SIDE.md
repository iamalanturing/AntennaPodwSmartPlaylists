# Installing both forks on one device

**Resolved.** This branch now builds under its own applicationId, so it installs alongside the
Smart Playlists build without touching it. Kept as a record of what happened and why, in case the
Smart Playlists side ever needs the same treatment for a different pair of builds.

## The goal

Have the multiple-queues build and the Smart Playlists build installed at the same time, so they
can be compared and used side by side rather than one replacing the other.

## Why they collided

Both branches inherit the same base identity from upstream:

- `app/build.gradle`: `namespace "de.danoeh.antennapod"`
- `app/build.gradle` debug build type, originally: `applicationIdSuffix ".debug"`

So both originally produced **`de.danoeh.antennapod.debug`**. Android keys installs by
`applicationId`, so the second install replaces the first.

## What actually happened, and why the plan changed mid-stream

The first version of this note recommended the *opposite* of what is now in place: that the Smart
Playlists fork should take a new identity and this branch should stay on stock
`de.danoeh.antennapod.debug`, since this branch is aimed at an upstream PR and every fork-only
change here is one more thing to drop before that PR.

That reasoning was sound but rested on an unchecked assumption: that the Smart Playlists build
*installed on the device* matched what was in the `claude/v2-add-smart-playlists` branch's current
source, which by then already used `applicationIdSuffix ".smart.debug"`. It didn't. The installed
Smart Playlists app -- 5.49 GB of real subscriptions and downloads -- predated that rename and was
still running on the plain `de.danoeh.antennapod.debug` id. So it was the live blocker the whole
time, not a stray unrelated build as first suspected (a `beta6` debug build with no relation to
either fork's version history was briefly the leading suspect, and had to be ruled out first).

Renaming the *other* fork's source doesn't change what's already installed. Getting a working
install without touching that data meant changing the identity actually being built here, not the
identity declared in a branch never rebuilt on this device.

## What changed on this branch

`app/build.gradle`, debug build type:

```gradle
debug {
    applicationIdSuffix ".mq.debug"
    resValue "string", "provider_authority", "de.danoeh.antennapod.mq.debug.provider"
    resValue "string", "app_name", "AntennaPod MQ Debug"
    signingConfig signingConfigs.forkDebug
}
```

Giving `de.danoeh.antennapod.mq.debug`, its own provider authority, and its own label -- so it can
install next to *anything* already using `de.danoeh.antennapod.debug`, regardless of which fork or
which historical build put it there.

`app_name` is set here via `resValue` in the app module rather than by editing the shared
`common.gradle`, which defines the base `"AntennaPod Debug"` value read by every module. AGP applies
`common.gradle`'s `android {}` block first (it is applied at the top of `app/build.gradle`, before
the app module's own `android {}` block runs), so the app module's later `resValue` call for the
same name overrides it for this module's own debug builds. No shared file is touched, and no other
module's build is affected.

`provider_authority` is the one resource that must move in lockstep with the applicationId: it
backs `android:authorities` in `AndroidManifest.xml` and four `FileProvider.getUriForFile` callers
(`ShareUtils`, `ImportExportPreferencesFragment`, `BugReportFragment`, `FinalShareScreen`). Two
installed apps cannot declare the same authority -- changing the applicationId without changing this
produces `INSTALL_FAILED_CONFLICTING_PROVIDER`, a confusing error if you are not expecting it.

The debug signing key (`app/fork-debug.keystore`, `forkDebug` signing config, committed earlier for
unrelated reasons -- so consecutive builds of this branch would install over one another instead of
each CI runner minting a throwaway key) needed no change. Signature verification is keyed on the
certificate, not the applicationId; different applicationIds never collide on signature regardless
of which key signed them.

All of this is fork-only and must be dropped before any upstream PR, same as `fork-checks.yml`,
the keystore, and the MQ launcher icon.

## If the Smart Playlists fork ever needs this too

The `.smart.debug` suffix already exists on `claude/v2-add-smart-playlists`, so a *fresh build* of
that branch already carries its own identity. Nothing further should be needed there unless a
similar situation recurs: some already-installed build on a device turns out to predate whatever
the current source declares. If that happens, check what is actually installed
(`pm list packages`, then `dumpsys package <package> | grep versionName` or the Settings > Apps > App info page)
before assuming the branch's source reflects reality.

## Two things that are easy to get wrong, regardless of which side renames

**Side-by-side installs do not test the database migration.** A fresh install runs `onCreate`,
which builds the current schema directly and never executes `DBUpgrader`. Testing the migration
means installing an APK *over* an existing install carrying a pre-`3120000` database -- deliberately
not side by side.

**Custom intent actions stay shared.** The manifest declares actions like
`de.danoeh.antennapod.intents.MAIN_ACTIVITY`. These are plain strings, not namespaced by
applicationId, so with multiple AntennaPod-derived apps installed an implicit intent matching one
of them can resolve to any of them and Android may show a chooser. Not worth fixing for a test
build, but worth recognising rather than being puzzled by.
