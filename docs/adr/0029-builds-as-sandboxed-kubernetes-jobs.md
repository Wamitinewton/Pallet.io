# 29. Builds as sandboxed Kubernetes Jobs with credential-separated steps

- Status: proposed
- Date: 2026-11-09

## Context

`build-service` is the first Pallet service that runs code it didn't write. A tenant's Dockerfile
`RUN` lines and the buildpacks that compile their app are arbitrary programs, and a build has to
reach the internet for dependencies. A build also needs two credentials: a GitHub token to fetch the
commit and a registry credential to push the image. Whatever runs the tenant's code must never hold
either, because a hostile build would read them and use them against other tenants.

ADR-0002 makes Kubernetes the workload orchestrator, and ADR-0026 runs untrusted workloads under a
sandboxed `RuntimeClass` (gVisor by default). A build is a short, run-to-completion workload, so it
fits a Kubernetes Job. What has to be decided is which build engines run inside it, how the steps
are split so no credential and no tenant code ever share a container or a moment, what the
namespace allows, and whether something other than plain Jobs should drive it.

The full design is `docs/build-service/ARCHITECTURE.md` §The build pod and §Images and the
registry. This record lands with the sandbox spike, which built both strategies by hand in the local
kind cluster before any controller code existed.

## Decision

1. **One Job per build** in the dedicated namespace `pallet-builds`: `backoffLimit: 0`,
   `restartPolicy: Never`, `activeDeadlineSeconds` from the build's maximum duration,
   `ttlSecondsAfterFinished` as the clean-up backstop. Pods run as `pallet-build`, a service account
   with no RoleBindings, with `automountServiceAccountToken: false`, `enableServiceLinks: false`, no
   host namespaces or `hostPath`, and `runtimeClassName` gVisor where the cluster has it.
2. **Steps are ordered containers that hand the image over on disk.** Init containers run one at a
   time and the next starts only if the previous exited 0, which is the step order a build needs.

   | Strategy | Containers (init…, main) | Credential and where |
   |---|---|---|
   | `DOCKERFILE` | `fetch`, `build`, `push` | source token in `fetch`; registry credential in `push` |
   | `BUILDPACKS` | `fetch`, `analyze`, `detect`, `build`, `export`, `report` | source token in `fetch`; registry credential in `analyze` and `export` |

   `build`, `detect` (and the buildpacks `build`) run tenant code and hold no credential and no
   Pallet configuration. A container sees only the volumes mounted into it, `shareProcessNamespace`
   is off, and by the time tenant code runs `fetch` has exited and revoked its token at GitHub.
3. **Rootless BuildKit for Dockerfiles**, daemonless, writing an OCI archive to the shared
   `emptyDir` workspace instead of pushing; Pallet's `push` step copies it to the registry with
   `skopeo`. The container runs as user 1000 with a read-only root filesystem, every capability
   dropped except `SETUID` and `SETGID`, privilege escalation allowed, and unconfined seccomp and
   AppArmor profiles. BuildKit's `noProcessSandbox` comes from a namespace ConfigMap, so the
   container has no environment variable of Pallet's.
4. **The Cloud Native Buildpacks phased lifecycle for buildpacks**, following the platform
   specification's flow for untrusted builders: `analyze` and `export` from the trusted
   `buildpacksio/lifecycle` image, `detect` and `build` from the builder image with no credential,
   platform API `0.14`. The lifecycle's single-container `creator` is not used.
5. **Pallet's own `build-steps` image** (Alpine, `curl`, GNU `tar`, `skopeo`; non-root) for `fetch`,
   `push` and `report`. They report through exit codes and a one-line JSON termination message with
   a fixed vocabulary; nothing GitHub or the tenant produced reaches it.
6. **The namespace is locked down by platform manifests, not by `build-service` at runtime**: Pod
   Security `enforce: privileged` with `warn`/`audit: baseline` (BuildKit's relaxations fail
   baseline), a ValidatingAdmissionPolicy that allows those relaxations only on a container named
   `build` running the configured BuildKit image, a ResourceQuota and LimitRange, NetworkPolicy, and
   a Role for `build-service` with `create` and `delete` but no `get` or `list` on Secrets.
7. **Plain Jobs through the Fabric8 client**, driven by `build-service`. No Tekton, Argo Workflows,
   kpack or Shipwright, and no Temporal (ADR-0009).

### What the spike found

Run on kind `v0.33.0` (Kubernetes `v1.37.0`, containerd `2.3.4`). Images are pinned by digest in
`services/build-service/images/README.md`.

| Image | Version |
|---|---|
| `moby/buildkit` | `v0.34.2-rootless` |
| `buildpacksio/lifecycle` | `0.21.28` (platform API `0.7`–`0.15`) |
| `paketobuildpacks/ubuntu-noble-builder` | `0.0.217` (embeds lifecycle `0.21.28`) |
| `paketobuildpacks/ubuntu-noble-run` | `0.0.157` |
| `build-steps` base | `alpine:3.24.4` |

- The Dockerfile sample, the buildpacks Node sample and the buildpacks Spring Boot sample all built
  and pushed. In each case the digest in the termination message matched the registry's, and the
  built images ran from the registry in another namespace. A failing `RUN` stopped at `build` with
  exit 1 and `push` never ran. A 20-second `activeDeadlineSeconds` ended a running build with
  `DeadlineExceeded`.
- The BuildKit minimum is exactly as in decision 3. With `allowPrivilegeEscalation: false`, or with
  every capability dropped, `newuidmap` can't set up the user namespace. With `RuntimeDefault`
  seccomp, rootlesskit can't re-exec into it. The AppArmor relaxation couldn't be tested because the
  spike host has no AppArmor; it stays because BuildKit documents it for AppArmor hosts.
- **The analyzer holds the registry credential.** The architecture assumed it needed none. The
  lifecycle refuses to start `analyze` unless it can read the output image's repository. It runs the
  lifecycle image before any buildpack code runs, so the separation property holds. No `restore`
  phase is needed while there's no cache and no previous image.
- The builder runs as `1001:1001`, and Paketo's npm buildpack writes into the app directory, so the
  pod's `fsGroup` is 1001 and `fetch` makes the extracted tree group-writable. `CNB_PLATFORM_API`
  is the one environment variable on the builder containers, because the lifecycle has no flag for
  it.
- `fetch` downloaded by repository id from GitHub inside the namespace, anonymously. Against crafted
  archives it refused absolute members, `..` members and a root directory or Dockerfile that
  resolves outside the source tree through a symlink. GNU `tar` refused to write through a symlink
  member. A token mounted into `fetch` never appeared in any container's log, and the revoke call
  followed the download.
- Pods resolve and reach `pallet-registry:5000` on the kind network. The local registry
  authenticates pulls as well as pushes, so a pod whose image lives there needs an image pull
  Secret. Locally, build pods reference the push Secret for Pallet's own step image. Only the
  kubelet reads it.

### gVisor

**Still open.** No node with the `runsc` runtime was available for the spike, and kind can't run
gVisor. Until the Dockerfile Job has run once under gVisor, this record stays `proposed`, and
`ARCHITECTURE.md` §Open questions keeps the item. The deployed profile can't go live without that
run. If rootless BuildKit doesn't work there, the Dockerfile path switches to Buildah with chroot
isolation and the Job is rewritten before the launcher is built on it.

## Alternatives considered

- **Docker socket on a host.** Anything that reaches the socket is root on the host.
- **Kaniko.** Archived upstream in 2025.
- **Buildah with chroot isolation.** Works rootless and without the user-namespace relaxations, but
  it's less faithful to `docker build` than BuildKit. It's the fallback if BuildKit fails under
  gVisor.
- **The `pack` CLI.** Needs a Docker daemon.
- **The lifecycle's single-container `creator`.** Runs buildpack code in the same container as the
  registry credential.
- **Tekton, Argo Workflows, kpack, Shipwright.** Controllers and CRDs Pallet doesn't own, to run
  what one Job already does. kpack is also buildpacks-only. Their scheduling and retry models would
  have to be reconciled with the queue's leases (ADR-0028).
- **A dedicated VM per build.** The strongest isolation, deferred with Firecracker under ADR-0026.
- **Temporal.** A build is one Job with one outcome. ADR-0009 keeps Temporal to the deploy and
  billing sagas.

## Consequences

- Every build is cold: no layer cache until per-app registry credentials exist, and each node pulls
  the BuildKit, lifecycle and builder images (the builder is over 1 GB) the first time it runs a
  build.
- The namespace runs at Pod Security `privileged`. Its safety rests on the admission policy and the
  sandboxed runtime, and baseline warnings on every Dockerfile build are expected.
- The `push` and `export` parsers read tenant-produced files while holding the platform's single
  push credential. A hostile archive that exploited them could push into another tenant's
  repository. Per-build credentials from `registry-service` close that.
- The analyzer holding the registry credential is recorded in `ARCHITECTURE.md`. Admission policy
  must allow the credential mount on `analyze`, `export` and `push` only.
- The Job templates in `deploy/local/k8s/builds/examples/` are the reference the launcher's pod spec
  snapshot test is checked against.
- Follow-up: the gVisor run; NetworkPolicy and the admission policy, written against these pod
  specs.
