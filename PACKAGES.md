# Publishing and consuming platform-common

`platform-common-*` is published to GitHub Packages so a repo outside this monorepo can depend
on it without cloning the whole reactor. `services/*` is never published: services are
applications, not libraries, and their POMs inherit `pallet-parent` directly rather than
`platform-common`, so they have no `distributionManagement` and `mvn deploy` correctly refuses
to run against them.

The normal path is: bump the version, tag it, push the tag. CI takes it from there and publishes
using the token GitHub injects into every workflow run automatically (`secrets.GITHUB_TOKEN`),
scoped to this repository and expiring when the run ends. A personal access token is only needed
for the manual publish path further down, for when you want to push a build from your own
machine without going through a tag.

## How versioning works here

All modules in the reactor share one version, set once on `pallet-parent` and inherited
everywhere via `${project.version}`. A release bumps that one version, tags it, and lets CI
publish; then the version is bumped again to the next `-SNAPSHOT` so ongoing work keeps building
locally without colliding with anything already published.

Releases are tagged `platform-common-v<version>` (not a bare `v<version>`), so this stays
distinct from any future deployment tag a service might use. That tag is what
`.github/workflows/release.yml` listens for.

## Cutting a release

Run these from the repository root, on a clean `main`.

```bash
# 1. Confirm you're releasing what you think you're releasing.
git checkout main
git pull
git status   # must be clean

# 2. Drop the -SNAPSHOT suffix across every module in one shot.
#    Example: 0.1.0-SNAPSHOT -> 0.1.0
./mvnw versions:set -DnewVersion=0.1.0 -DprocessAllModules=true -DgenerateBackupPoms=false

# 3. Sanity-check the release version builds clean before it's tagged.
./mvnw clean verify

# 4. Commit the version bump.
git add -A
git commit -m "chore: release platform-common 0.1.0"

# 5. Tag and push. Pushing the tag is what triggers the release workflow.
git tag platform-common-v0.1.0
git push origin main
git push origin platform-common-v0.1.0

# 6. Immediately move back to the next development version so `main` never
#    sits on a released, non-SNAPSHOT version.
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules=true -DgenerateBackupPoms=false
git add -A
git commit -m "chore: bump version to 0.2.0-SNAPSHOT"
git push origin main
```

Watch the run under the repo's **Actions** tab (`release` workflow). Once it's green, the
published artifacts show up under **Packages** on the repository's GitHub page.

## Publishing by hand instead (no tag, no CI)

Useful for a one-off test, or for publishing from a machine that isn't running the CI workflow.
This path needs a personal access token with `write:packages` scope, generated at
<https://github.com/settings/tokens>.

```bash
# One-time: tell Maven how to authenticate to the "github" server id used in
# platform-common/pom.xml's distributionManagement. This file lives outside
# the repo (~/.m2) and is never committed - keep the token itself out of it
# too, by referencing an environment variable instead of a literal value.
mkdir -p ~/.m2
cat >> ~/.m2/settings.xml <<'EOF'
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>Wamitinewton</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
EOF
```

If `~/.m2/settings.xml` already exists, merge the `<server>` block into its existing `<servers>`
rather than appending a second `<settings>` root.

```bash
# Set the token for this shell session only. Needs write:packages scope.
export GITHUB_TOKEN=<your-token>

# Deploy just platform-common and its modules, not the whole reactor.
./mvnw -f platform-common/pom.xml clean deploy

# Clear it once you're done.
unset GITHUB_TOKEN
```

## Consuming a published module from another repository

A repo outside this monorepo (or a service here that deliberately wants the published jar
instead of building from the reactor) adds the GitHub Packages repository and the dependency:

```xml
<repositories>
    <repository>
        <id>pallet-github</id>
        <url>https://maven.pkg.github.com/Wamitinewton/Pallet.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.pallet</groupId>
    <artifactId>platform-common-events</artifactId>
    <version>0.1.0</version>
</dependency>
```

GitHub Packages requires authentication to *read* a Maven package even when the repository is
public - there's no anonymous pull the way Maven Central allows. Whoever builds against this
dependency needs their own `~/.m2/settings.xml` entry for server id `pallet-github`, with a
token that has at least `read:packages` scope:

```xml
<settings>
  <servers>
    <server>
      <id>pallet-github</id>
      <username>THEIR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

## Checklist for the very first release

- [ ] Confirm `platform-common/pom.xml` has the `distributionManagement` block pointing at
      `https://maven.pkg.github.com/Wamitinewton/Pallet.io`.
- [ ] Confirm `.github/workflows/release.yml` exists and triggers on `platform-common-v*` tags.
- [ ] Run the release steps above for `0.1.0`.
- [ ] Check the repository's **Packages** tab for `platform-common-api`,
      `platform-common-exception`, `platform-common-events`, `platform-common-security`,
      `platform-common-observability`, `platform-common-messaging`,
      `platform-common-resilience`, and `platform-common-test`.
- [ ] From a scratch directory, try adding one of them as a dependency with a fresh
      `~/.m2/settings.xml` to confirm the consumer-side story actually works end to end.
