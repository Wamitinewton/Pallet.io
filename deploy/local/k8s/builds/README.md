# Builds namespace (local)

The namespace `build-service` runs build pods in, and the hand-written Jobs that proved the build
pod works before any controller existed. `deploy/local/bootstrap-kind.sh` applies everything here
except `examples/`.

| Manifest | What it's for |
|---|---|
| `namespace.yaml` | `pallet-builds`. Pod Security `enforce: privileged`, `warn`/`audit: baseline`: rootless BuildKit needs unconfined seccomp and AppArmor, which baseline forbids, so the namespace's admission policy is what constrains pods here. Every Dockerfile build prints a baseline warning on purpose. |
| `service-accounts.yaml` | `pallet-build` (build pods; no bindings, no token) and `build-service` in `pallet-system`. |
| `rbac.yaml` | `build-service`'s Role: Jobs `create get list watch delete`; Pods `get list watch`; `pods/log get`; Secrets `create delete` only. Nothing cluster-scoped. |
| `quota.yaml` | `ResourceQuota` for four concurrent builds plus headroom; `LimitRange` defaults for any container without limits. |
| `buildkitd-config.yaml` | `pallet-buildkitd-config`: BuildKit's `noProcessSandbox`, as a file rather than an environment variable on the container that runs tenant code. |
| `examples/` | One Job per strategy, `render.sh` to fill their placeholders. Not applied by the bootstrap. |

The bootstrap also creates the `pallet-registry-push` Secret from
`deploy/local/registry/auth/dockerconfig.json` and writes `deploy/local/build-service.kubeconfig`,
which carries the `build-service` account's own token, so the service runs locally with exactly its
deployed RBAC.

NetworkPolicy and the admission policy come later; kind's default network plugin doesn't enforce
NetworkPolicy anyway.

## What the spike established

Run on kind `v0.33.0` (Kubernetes `v1.37.0`, containerd `2.3.4`), Fedora host with SELinux.

- **Registry name.** Pods resolve and reach `pallet-registry:5000` through CoreDNS and the node's
  resolver on the `kind` network, and nodes pull from it through the `hosts.toml` the bootstrap
  writes. containerd 2 migrates the `config_path` patch in `kind-cluster.yaml` on its own.
- **Pulls need credentials.** The registry's htpasswd auth covers reads too, so a pod whose image
  lives there needs an `imagePullSecrets` entry. Build pods reference `pallet-registry-push` only
  because Pallet's step image lives in the local registry; the kubelet reads it and no container
  sees it. Deployed, step images come from a registry the nodes can already pull from.
- **BuildKit's minimum.** User 1000, `readOnlyRootFilesystem` with emptyDirs for `/tmp`,
  `~/.local/share/buildkit`, `~/.local/tmp`, `~/.docker` (empty: no credential) and
  `/run/user/1000`; `capabilities: drop ALL, add SETUID SETGID` and `allowPrivilegeEscalation: true`
  (`newuidmap`/`newgidmap` rely on file capabilities); `seccompProfile: Unconfined` (with
  `RuntimeDefault`, rootlesskit can't re-exec itself into the new namespaces); `appArmorProfile:
  Unconfined` (untested here: the host has no AppArmor); `noProcessSandbox` from the ConfigMap.
  Without `SETUID`/`SETGID` or with escalation off, `newuidmap` fails.
- **Buildpacks.** The phased flow works without a `restore` phase when there's no cache and no
  previous image. The analyzer refuses to start unless it can read the output image's repository,
  so it holds the registry credential as well as `export`. It runs the lifecycle image before any
  buildpack code, so no credential shares a container with tenant code. Paketo's npm buildpack
  writes into the app directory, so `fetch` makes the tree group-writable and the pod's `fsGroup`
  is the builder's CNB group (1001).
- **`CNB_PLATFORM_API`** is the one environment variable on the builder containers. The lifecycle
  has no flag for it.
- **Quote placeholders.** An all-digit build id (or a UUID prefix like `1e50…`) in an unquoted YAML
  label becomes a number and the API rejects the Job. The templates quote every string; the
  controller builds objects, not YAML, so it can't hit this.

## Rerunning the spike

Prerequisites: `make up` (or at least `docker compose --profile core up -d registry`, after
`make registry-auth`), `deploy/local/bootstrap-kind.sh`, and `build-steps` pushed as in
`services/build-service/images/README.md`.

The Jobs fetch from GitHub by repository id. To build the samples without pushing them to a GitHub
repository, serve them at the same path from a container on the `kind` network:

```bash
spike=$HOME/.cache/pallet-spike
mkdir -p "$spike/www/repositories/1/tarball"
tar --sort=name --owner=0 --group=0 --mtime=@0 -czf "$spike/src.tgz" \
  --transform 's,^,pallet-samples/,' -C services/build-service samples
sha=$(sha1sum "$spike/src.tgz" | cut -c1-40)
mv "$spike/src.tgz" "$spike/www/repositories/1/tarball/$sha"
docker run -d --name pallet-spike-source --network kind \
  -v "$spike/www:/www:ro,z" busybox:1.38.0 httpd -f -vv -p 8080 -h /www
```

Then render and apply a Job. Every value that varies per build is a `${PLACEHOLDER}`, and
`render.sh` refuses to print a manifest with one left over:

```bash
cd deploy/local/k8s/builds/examples
steps=pallet-registry:5000/pallet/build-steps:0.1.0@sha256:ad902a530456387fa7e179f77dd51594a8a96f648a695bf113b5cbd75832060c

./render.sh dockerfile-job.yaml \
  BUILD_ID=0b1d0c2e ORG_ID=org-spike APP_ID=app-dockerfile-node \
  REPO_ID=1 COMMIT_SHA="$sha" GITHUB_API_URL=http://pallet-spike-source:8080 \
  ROOT_DIRECTORY=samples/dockerfile-node DOCKERFILE_PATH=Dockerfile DOCKERFILE_DIR=. DOCKERFILE_NAME=Dockerfile \
  IMAGE_REF="pallet-registry:5000/org-spike/app-dockerfile-node:${sha:0:12}-0b1d0c2e" \
  ACTIVE_DEADLINE_SECONDS=1800 REGISTRY_INSECURE=true BUILD_STEPS_IMAGE="$steps" \
  BUILDKIT_IMAGE=docker.io/moby/buildkit:v0.34.2-rootless@sha256:c8252268760e1baba80780d63d738ef9f260fb7c7c0973c78494cb9ddd99eda1 \
  | kubectl --context kind-pallet apply -f -

./render.sh buildpacks-job.yaml \
  BUILD_ID=3c4d5e6f ORG_ID=org-spike APP_ID=app-buildpacks-node \
  REPO_ID=1 COMMIT_SHA="$sha" GITHUB_API_URL=http://pallet-spike-source:8080 \
  ROOT_DIRECTORY=samples/buildpacks-node \
  IMAGE_REF="pallet-registry:5000/org-spike/app-buildpacks-node:${sha:0:12}-3c4d5e6f" \
  ACTIVE_DEADLINE_SECONDS=1800 INSECURE_REGISTRY=pallet-registry:5000 CNB_PLATFORM_API=0.14 \
  BUILD_STEPS_IMAGE="$steps" \
  LIFECYCLE_IMAGE=docker.io/buildpacksio/lifecycle:0.21.28@sha256:da6997ca53ebfa165a6433db3fefadf651327b13ecb4a896a58c4ac90a29905e \
  BUILDER_IMAGE=docker.io/paketobuildpacks/ubuntu-noble-builder:0.0.217@sha256:d76f917d4a2c74e63874ca4f5e1927ef4c5c3e0c7cfbe57a36212bc02313ca2d \
  RUN_IMAGE=docker.io/paketobuildpacks/ubuntu-noble-run:0.0.157@sha256:b32d0ce1fdf06d9a80b2f47b3b452f7079c56ed489525f9bb0951b7beb3fbe50 \
  | kubectl --context kind-pallet apply -f -
```

Against a real public repository, pass its numeric id (`gh api repos/<owner>/<repo> --jq .id`), a
commit sha, the repository-relative root directory and `GITHUB_API_URL=https://api.github.com`.

Read the result:

```bash
kubectl -n pallet-builds get pods -l pallet.io/build-id=0b1d0c2e -o jsonpath=\
'{range .items[0].status.initContainerStatuses[*]}{.name}: {.state.terminated.exitCode} {.state.terminated.message}{"\n"}{end}{range .items[0].status.containerStatuses[*]}{.name}: {.state.terminated.exitCode} {.state.terminated.message}{"\n"}{end}'
skopeo inspect --tls-verify=false --authfile deploy/local/registry/auth/dockerconfig.json \
  docker://localhost:5001/org-spike/app-dockerfile-node:<tag> | jq -r .Digest
```

Use a poll loop rather than `kubectl wait --for=condition=Complete --for=condition=Failed`: with
two `--for` flags, `kubectl wait` waits for both.

Clean up with `kubectl -n pallet-builds delete jobs --all` and `docker rm -f pallet-spike-source`.
