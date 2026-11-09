# Build samples

Small apps that exercise each build path. Each directory is one app's root directory.

| Directory | Strategy | Expected result |
|---|---|---|
| `dockerfile-node` | `DOCKERFILE` | Image serving `hello from pallet` on `$PORT` (8080) |
| `buildpacks-node` | `BUILDPACKS` (no Dockerfile) | The same app, built by the Paketo Node.js buildpacks |
| `buildpacks-java` | `BUILDPACKS` (no Dockerfile) | Spring Boot app, built by the Paketo Java buildpacks with Maven |
| `failing-build` | `DOCKERFILE` | The `build` step exits 1; nothing is pushed |

They're plain source on purpose: no lockfiles, wrappers or build output. Push them to a throwaway
public GitHub repository to build them through the real fetch path, or serve them as tarballs
(see `deploy/local/k8s/builds/README.md`).
