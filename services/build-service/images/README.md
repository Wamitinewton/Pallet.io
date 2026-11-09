# Build images

Every image a build pod runs, pinned by digest. Outside the local profile, `build-service` refuses
to start with an image that isn't pinned by digest, so a bump is always a reviewed change here and
in `pallet.build.images.*`.

## Pinned images

| Role | Image | Version | Digest |
|---|---|---|---|
| `fetch`, `push`, `report` | `pallet/build-steps` (this folder) | `0.1.0` | `sha256:ad902a530456387fa7e179f77dd51594a8a96f648a695bf113b5cbd75832060c` (local registry) |
| Dockerfile `build` | `docker.io/moby/buildkit` | `v0.34.2-rootless` | `sha256:c8252268760e1baba80780d63d738ef9f260fb7c7c0973c78494cb9ddd99eda1` |
| Buildpacks `analyze`, `export` | `docker.io/buildpacksio/lifecycle` | `0.21.28` | `sha256:da6997ca53ebfa165a6433db3fefadf651327b13ecb4a896a58c4ac90a29905e` |
| Buildpacks `detect`, `build` | `docker.io/paketobuildpacks/ubuntu-noble-builder` | `0.0.217` | `sha256:d76f917d4a2c74e63874ca4f5e1927ef4c5c3e0c7cfbe57a36212bc02313ca2d` |
| Buildpacks run image | `docker.io/paketobuildpacks/ubuntu-noble-run` | `0.0.157` | `sha256:b32d0ce1fdf06d9a80b2f47b3b452f7079c56ed489525f9bb0951b7beb3fbe50` |
| `build-steps` base | `docker.io/library/alpine` | `3.24.4` | `sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf` |

All digests are of the multi-architecture index, so the same pin resolves on `amd64` and `arm64`
nodes.

The builder embeds lifecycle `0.21.28`, the same version as the lifecycle image, and both support
platform API `0.7` to `0.15`. Builds run with `CNB_PLATFORM_API=0.14`. Keep the lifecycle image
and the builder's embedded lifecycle on the same version when bumping either: the phases share
files in the layers directory, and their formats follow the lifecycle version.

`build-steps` installs pinned Alpine packages (`ca-certificates`, `curl`, `tar`, `skopeo`), so a
rebuild produces the same contents but a new digest. The digest above is the one the sandbox spike
built and pushed to the local registry. Deployed profiles pin the digest that CI publishes.

## Getting a digest

```bash
skopeo inspect --raw docker://docker.io/moby/buildkit:v0.34.2-rootless | sha256sum
skopeo list-tags docker://docker.io/moby/buildkit | jq -r '.Tags[]' | grep -E '^v[0-9.]+-rootless$' | sort -V | tail -3
```

`--raw` hashes the manifest exactly as the registry serves it, which is what a `@sha256:` reference
pins. `crane digest <image>` gives the same answer.

The builder's lifecycle version, supported platform APIs and default run image:

```bash
skopeo inspect --override-os linux --override-arch amd64 \
  docker://docker.io/paketobuildpacks/ubuntu-noble-builder:0.0.217 \
  | jq -r '.Labels["io.buildpacks.builder.metadata"]' | jq '{lifecycle, images}'
```

## Building `build-steps` locally

```bash
docker build -t localhost:5001/pallet/build-steps:0.1.0 services/build-service/images/build-steps
skopeo copy --dest-tls-verify=false \
  --dest-authfile deploy/local/registry/auth/dockerconfig.json \
  --digestfile /dev/stdout \
  docker-daemon:localhost:5001/pallet/build-steps:0.1.0 \
  docker://localhost:5001/pallet/build-steps:0.1.0
```

Pods refer to it as `pallet-registry:5000/pallet/build-steps:0.1.0@<digest>`.

## The step scripts

| Script | Arguments | Exit codes | Termination message |
|---|---|---|---|
| `fetch` | `<repoId> <sha> <rootDirectory> [dockerfilePath]` | 0; 10 source unavailable; 11 source not found; 2 bad arguments | `{"step":"FETCH","bytes":N}` or `{"step":"FETCH","error":WORD}` |
| `push` | `<imageRef> <strategy>` | 0; 20 push failed; 2 bad arguments | `{"step":"PUSH","digest":"sha256:…","strategy":"DOCKERFILE"}` or `{"step":"PUSH","error":WORD}` |
| `report` | `<imageRef>` | 0; 20 report unusable; 2 bad arguments | `{"step":"PUSH","digest":"sha256:…","strategy":"BUILDPACKS"}` or `{"step":"PUSH","error":WORD}` |

The error words are a fixed vocabulary. `fetch`: `SOURCE_UNREACHABLE`, `SOURCE_REFUSED`,
`SOURCE_RATE_LIMITED`, `ARCHIVE_TOO_LARGE`, `ARCHIVE_INVALID` (exit 10); `COMMIT_NOT_FOUND`,
`ROOT_DIRECTORY_NOT_FOUND`, `DOCKERFILE_NOT_FOUND` (exit 11). `push`: `ARCHIVE_MISSING`,
`CREDENTIAL_MISSING`, `PUSH_ERROR`, `DIGEST_INVALID`. `report`: `REPORT_MISSING`, `DIGEST_INVALID`,
`REFERENCE_MISMATCH`. `USAGE` (exit 2) means `build-service` passed bad arguments, which is a
platform bug.

Environment, all optional and none of them secret:

| Variable | Step | Default | Meaning |
|---|---|---|---|
| `PALLET_GITHUB_API_URL` | `fetch` | `https://api.github.com` | GitHub API base; tests point it at WireMock |
| `PALLET_FETCH_MAX_BYTES` | `fetch` | `1073741824` | Largest archive accepted |
| `PALLET_REGISTRY_INSECURE` | `push` | `false` | Plain HTTP to the registry (local only) |
