# Installing both forks on one device

Handoff note for the Smart Playlists branch (`claude/continue-previous-3hqkf6`).
Written from `claude/multiple-queues-impl-m2jnxy`, which is the *other* fork — clean upstream
`develop` plus manual multiple queues, aimed at an upstream PR.

**Nothing in this file has been executed or tested.** The multiple-queues side is done and pushed;
the Smart Playlists side is described but not written.

## The goal

Have the multiple-queues build and the Smart Playlists build installed at the same time, so they
can be compared and used side by side rather than one replacing the other.

## Why they collide today

Both branches inherit the same identity from upstream:

- `app/build.gradle`: `namespace "de.danoeh.antennapod"`
- `app/build.gradle` debug build type: `applicationIdSuffix ".debug"`

So both produce **`de.danoeh.antennapod.debug`**. Android keys installs by `applicationId`, so the
second install replaces the first. Nothing else about the two builds matters — not the version, not
the signing key, not the app name.

## The decision, and why it falls on this branch

**The Smart Playlists fork should take the new identity. The multiple-queues branch stays stock
`de.danoeh.antennapod.debug`.**

The reason is not fairness, it is PR hygiene. The multiple-queues branch exists to become a pull
request against `AntennaPod/AntennaPod`, and is currently clean upstream `develop` plus the queue
work. Every fork-only change there is one more thing that must be dropped before the PR and one
more chance for fork scaffolding to leak into it. `BRANCH.md` on that branch treats this as its
main rule. The Smart Playlists fork has no such constraint — it is a fork and intends to stay one,
so a permanent identity of its own costs it nothing.

## What the Smart Playlists branch needs to change

Four things. The first two are mandatory; skipping either means the install fails or the app
misbehaves.

### 1. A different applicationId — mandatory

In `app/build.gradle`, in the `debug` build type:

```gradle
debug {
    applicationIdSuffix ".smart.debug"
    ...
}
```

Giving `de.danoeh.antennapod.smart.debug`. Any distinct suffix works.

### 2. A different provider authority — mandatory

In the same block, next to the suffix:

```gradle
resValue "string", "provider_authority", "de.danoeh.antennapod.smart.debug.provider"
```

**Two installed apps cannot declare the same provider authority.** Change the applicationId without
changing this and the install fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`, which is a confusing
error if you are not expecting it. Upstream already varies this per build type, so the pattern is
right there in the file.

This one `resValue` is the only place the authority is defined. It feeds
`android:authorities="@string/provider_authority"` in `app/src/main/AndroidManifest.xml`, and four
`FileProvider.getUriForFile` callers (`ShareUtils`, `ImportExportPreferencesFragment`,
`BugReportFragment`, `FinalShareScreen`). Change it once and they all follow.

### 3. A different app label — strongly recommended

Otherwise you get two launcher entries both called "AntennaPod Debug" with identical icons, and no
way to tell which is which.

`app_name` is not in `strings.xml`; it is a `resValue` in **`common.gradle`** at the repository
root:

```gradle
debug {
    resValue "string", "app_name", "AntennaPod Debug"
}
```

Change that string on the Smart Playlists branch, e.g. to `"AntennaPod Smart"`. Note `common.gradle`
is applied by every module, so this is a repo-wide edit rather than an app-module one.

### 4. A stable debug signing key — recommended

Not needed for coexistence, but needed to iterate. Without a fixed key, every CI runner generates
its own, so two consecutive builds cannot update each other and installing a new one means
uninstalling the old one first, destroying its database each time.

## What the multiple-queues branch did about signing, if you want to mirror it

Commit `2e60aba` on `claude/multiple-queues-impl-m2jnxy`:

- `app/fork-debug.keystore`, committed to the branch — RSA 2048, alias `multiple-queues`,
  10,000 day validity
- Password is `sha256("multiple-queues")` =
  `03cb1126c7ebb473113a434e8a33c109d99c70110e730bb655c6c782a1826487` — reproducible rather than
  secret
- Certificate SHA-256, as `apksigner verify --print-certs` reports it:
  `326da8de774254e67ed68479071d7b095b966c5845e33ca5288dc98cfbe18eae`
- A `forkDebug` entry in `signingConfigs`, referenced from the `debug` build type
- A `!app/fork-debug.keystore` exception in `.gitignore`, because `*.keystore` is ignored repo-wide
- A CI step asserting the APK carries that fingerprint, so a wrong key is caught before an install
  destroys anything

Generated with:

```sh
PW=$(printf 'multiple-queues' | sha256sum | cut -d' ' -f1)
keytool -genkeypair -v -keystore app/fork-debug.keystore -storetype PKCS12 \
  -alias multiple-queues -storepass "$PW" -keypass "$PW" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=AntennaPod Multiple Queues fork debug, OU=fork, O=AntennaPodwSmartPlaylists, L=none, ST=none, C=US"
keytool -exportcert -keystore app/fork-debug.keystore -alias multiple-queues -storepass "$PW" \
  | openssl dgst -sha256
```

Substitute `smart-playlists` for `multiple-queues` throughout for the other branch.

**This key provides no security whatsoever.** The password is derived from a published string, so
anyone can reproduce it and sign an APK with it. That is normal and acceptable for a debug key —
the stock Android debug key's password is literally `android` — but never reuse this arrangement
for anything installed outside your own device. It was committed rather than kept in a repository
secret because a debug key is not sensitive, and the earlier secret-based version silently failed:
nothing on the branch read the properties it wrote, so every APK was signed with the runner's
throwaway key anyway.

The two forks do **not** need different keys. Different applicationIds never conflict on signature.
Separate keys are only tidier.

## Your existing data

Changing the applicationId makes Android treat the result as a brand new app. It installs empty,
and no automatic migration exists — Android's backup system will not carry data between two
different applicationIds.

The old install keeps working under the old id until you remove it, so:

1. Open the current Smart Playlists build, export the database
   (Settings → Import/Export)
2. Install the renamed build — it appears as a separate, empty app
3. Import the database into it
4. Once satisfied, uninstall the old one

Do the export **before** uninstalling anything.

## Two things that are easy to get wrong

**Side-by-side installs do not test the database migration.** A fresh install runs `onCreate`,
which builds the current schema directly and never executes `DBUpgrader`. So a side-by-side install
exercises everything except the migration, which is the riskiest part of the multiple-queues work.
Testing that specifically means installing the multiple-queues APK *over* an existing
`de.danoeh.antennapod.debug` install carrying a pre-`3120000` database — deliberately not side by
side. Both are worth doing; they are separate exercises.

**Custom intent actions stay shared.** The manifest declares actions like
`de.danoeh.antennapod.intents.MAIN_ACTIVITY` and `de.danoeh.antennapod.intents.VIDEO_PLAYER`. These
are plain strings, not namespaced by applicationId, so with both apps installed an implicit intent
matching one of them can resolve to either and Android may show a chooser. This blocks nothing and
is not worth fixing for a test build, but it is worth recognising rather than being puzzled by.

## The zero-change alternative

Android multi-user or a work profile allows one applicationId to exist once per user, so both
builds can be installed with no changes to either repository. Switching between them is clunky and
storage is separate, but for a quick comparison it beats editing build files.
