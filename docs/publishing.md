# Publishing

Releases are cut from CI by pushing a tag. Nothing is published from a developer machine.

## What a release publishes

| Artifact | Published | Where |
| --- | --- | --- |
| `com.kitakkun.kotrail:kotrail-compiler-plugin:<kotlin>-<version>` | once per Kotlin version in `publishedKotlinVersions` | Maven Central |
| `com.kitakkun.kotrail:kotrail-annotations:<version>` | once | Maven Central |
| `com.kitakkun.kotrail:kotrail-gradle-plugin:<version>` and its plugin marker | once | Maven Central and the Gradle Plugin Portal |

The compiler plugin JAR links against one Kotlin compiler, which is why it is built and published
once per supported Kotlin version, with that version leading the artifact version. The Gradle
plugin composes the coordinate from the Kotlin version a consumer applies (see
[gradle-plugin.md](gradle-plugin.md)).

The Plugin Portal is what lets `id("com.kitakkun.kotrail") version "..."` resolve with no
repository configuration; Maven Central carries the same plugin so that a build which only trusts
Central can add it under `pluginManagement { repositories { mavenCentral() } }`.

## Cutting a release

1. Make sure `main` is green on both Kotlin versions.
2. Tag and push:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. `.github/workflows/release.yml` derives the version from the tag (`v0.1.0` → `0.1.0`), builds
   and tests once per Kotlin version, and publishes. Every Maven Central upload is a separate
   Central Portal deployment that the workflow releases as soon as the portal validates it.

The version otherwise defaults to `0.1.0-SNAPSHOT` (`kotrail.version` in the root build script),
and a snapshot version is never signed, so local builds and the functional test publish to their
local test repository without any credentials.

## Snapshots

`.github/workflows/snapshot.yml` publishes that default snapshot version to the
[Central Portal snapshot repository](https://central.sonatype.org/publish/publish-portal-snapshots/).
It is started by hand: Actions → Snapshot → Run workflow, picking the branch to publish from. The
workflow does not run the tests, so pick a commit CI has already passed. It publishes the same
artifacts as a release, except that the Gradle plugin goes to the snapshot repository only: the
Plugin Portal refuses snapshot versions. Snapshots are unsigned and overwrite the previous one.

The snapshot version is the default `kotrail.version` in the root build script, so bump it to the
next version right after cutting a release, or `main` keeps overwriting the snapshot of a version
that has already shipped.

A consumer opts in by adding the snapshot repository for both plugin and dependency resolution:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("com.kitakkun.kotrail") version "0.1.0-SNAPSHOT"
}
```

## One-time setup

These are outside the repository and have to be done by the owner.

### Maven Central

1. A [Central Portal account](https://central.sonatype.org/register/central-portal/).
2. A verified namespace for the group `com.kitakkun.kotrail`. Central verifies a `com.*`
   namespace through DNS ownership of the domain (`kitakkun.com`). If that domain is not yours,
   the group has to become a GitHub-verified one such as `io.github.kitakkun`, which also means
   changing the plugin id and the package names, so settle this before the first release.
3. A [user token](https://central.sonatype.org/publish/generate-portal-token/) for the account.
   This is not the login password.

### GPG signing key

Central requires signed artifacts. Generate a key, publish the public half to a key server, and
export the private half ASCII-armored for the CI secret:

```bash
gpg --full-generate-key
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>
```

### Gradle Plugin Portal

An account at <https://plugins.gradle.org/> with an API key and secret. The plugin id
`com.kitakkun.kotrail` is claimed on first publication, and the portal may ask for proof of
ownership of the `com.kitakkun` namespace as well.

### The `release` environment and its secrets

The publishing jobs of both the release and the snapshot workflow declare `environment: release`,
so the credentials are **environment secrets** of a GitHub environment named `release`
(Settings → Environments → New environment), not repository secrets. Environment secrets are only
handed to jobs that declare the environment, and the environment is also where an approval gate
goes: add required reviewers to it and every release and snapshot pauses for approval before
anything is uploaded. The snapshot workflow uses only the two `MAVEN_CENTRAL_*` secrets.

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_KEY` | ASCII-armored private GPG key |
| `SIGNING_KEY_ID` | The key id (last 8 hex characters of the fingerprint) |
| `SIGNING_KEY_PASSWORD` | The key's passphrase, empty if it has none |
| `GRADLE_PUBLISH_KEY` | Plugin Portal API key |
| `GRADLE_PUBLISH_SECRET` | Plugin Portal API secret |

## Verifying without publishing

Every publication is also wired to a local `test` repository under `build/test-repo`, which is
what the Gradle plugin's functional test consumes:

```bash
./gradlew publishAllPublicationsToTestRepository
```

To exercise signing exactly as CI does, pass a key as Gradle properties and a non-snapshot version
(signing is required for one, so the key cannot be left out):

```bash
./gradlew publishAllPublicationsToTestRepository -Pkotrail.version=0.0.1-local \
  -PsigningInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)" \
  -PsigningInMemoryKeyId=<KEY_ID> -PsigningInMemoryKeyPassword=...
```

`.asc` signature files then appear next to every artifact under `build/test-repo`. A throwaway
key is enough for this; the release workflow uses the real one from the repository secrets.

The Plugin Portal descriptor can be validated without uploading, but only with a Portal account:
the portal refuses snapshot versions and asks for the API key even to validate, so this takes the
fixed version, the signing key, and the portal credentials:

```bash
./gradlew :gradle-plugin:publishPlugins --validate-only -Pkotrail.version=0.0.1-local \
  -PsigningInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)" \
  -PsigningInMemoryKeyId=<KEY_ID> -PsigningInMemoryKeyPassword=... \
  -Pgradle.publish.key=... -Pgradle.publish.secret=...
```

Everything up to the upload itself has been exercised this way. The Central Portal upload and the
Portal publication are the two steps that can only be proven by the first real release.

## Adding a Kotlin version

See [supported-kotlin-versions.md](supported-kotlin-versions.md). The release and snapshot
workflows' matrices list the same versions as `publishedKotlinVersions` in the root build script;
all three have to change.
