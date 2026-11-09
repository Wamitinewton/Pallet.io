#!/usr/bin/env bash
# Create (or find) the local kind cluster used by the build and deploy paths, wire its nodes to the
# local registry, and apply the builds namespace.
# Requires: kind, kubectl, docker, and the registry from `make up`.
set -euo pipefail

CLUSTER_NAME="pallet"
CONTEXT="kind-${CLUSTER_NAME}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_CONTAINER="pallet-registry"
REGISTRY_HOST="pallet-registry:5000"
REGISTRY_HOST_PORT="localhost:5001"
BUILDS_DIR="${SCRIPT_DIR}/k8s/builds"
BUILDS_NAMESPACE="pallet-builds"
SYSTEM_NAMESPACE="pallet-system"
PUSH_SECRET="pallet-registry-push"
PUSH_CREDENTIAL="${SCRIPT_DIR}/registry/auth/dockerconfig.json"
SERVICE_KUBECONFIG="${SCRIPT_DIR}/build-service.kubeconfig"
SERVICE_TOKEN_DURATION="24h"

fail() {
  echo "$*" >&2
  exit 1
}

for tool in kind kubectl docker; do
  command -v "${tool}" >/dev/null 2>&1 || fail "${tool} is not installed."
done

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  echo "Cluster '${CLUSTER_NAME}' already exists."
else
  kind create cluster --config "${SCRIPT_DIR}/kind-cluster.yaml"
fi
kubectl cluster-info --context "${CONTEXT}" >/dev/null

nodes="$(kind get nodes --name "${CLUSTER_NAME}")"

for node in ${nodes}; do
  if ! docker exec "${node}" grep -q 'config_path = "/etc/containerd/certs.d"' /etc/containerd/config.toml; then
    fail "Node ${node} was created without the containerd certs.d patch in kind-cluster.yaml.
Recreate the cluster once: kind delete cluster --name ${CLUSTER_NAME} && $0"
  fi
done

if [[ "$(docker inspect -f '{{.State.Running}}' "${REGISTRY_CONTAINER}" 2>/dev/null)" != "true" ]]; then
  fail "The ${REGISTRY_CONTAINER} container isn't running. Start it with: make up"
fi
if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${REGISTRY_CONTAINER}" | grep -q '"kind"'; then
  docker network connect kind "${REGISTRY_CONTAINER}"
fi

for node in ${nodes}; do
  docker exec "${node}" mkdir -p "/etc/containerd/certs.d/${REGISTRY_HOST}"
  docker exec -i "${node}" cp /dev/stdin "/etc/containerd/certs.d/${REGISTRY_HOST}/hosts.toml" <<EOF
server = "http://${REGISTRY_HOST}"

[host."http://${REGISTRY_HOST}"]
  capabilities = ["pull", "resolve"]
EOF
done

kubectl --context "${CONTEXT}" apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "${REGISTRY_HOST_PORT}"
    hostFromContainerRuntime: "${REGISTRY_HOST}"
    hostFromClusterNetwork: "${REGISTRY_HOST}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF

kubectl --context "${CONTEXT}" apply -f "${BUILDS_DIR}/namespace.yaml" -f "${BUILDS_DIR}/service-accounts.yaml"
kubectl --context "${CONTEXT}" apply -f "${BUILDS_DIR}"

[[ -s "${PUSH_CREDENTIAL}" ]] || fail "No push credential at ${PUSH_CREDENTIAL}. Run: make registry-auth"
kubectl --context "${CONTEXT}" -n "${BUILDS_NAMESPACE}" create secret generic "${PUSH_SECRET}" \
  --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson="${PUSH_CREDENTIAL}" \
  --dry-run=client -o yaml | kubectl --context "${CONTEXT}" apply -f -

server="$(kubectl config view --raw --minify --context "${CONTEXT}" -o jsonpath='{.clusters[0].cluster.server}')"
ca_file="$(mktemp)"
trap 'rm -f "${ca_file}"' EXIT
kubectl config view --raw --minify --context "${CONTEXT}" \
  -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > "${ca_file}"
token="$(kubectl --context "${CONTEXT}" -n "${SYSTEM_NAMESPACE}" create token build-service --duration "${SERVICE_TOKEN_DURATION}")"

rm -f "${SERVICE_KUBECONFIG}"
(
  umask 077
  export KUBECONFIG="${SERVICE_KUBECONFIG}"
  kubectl config set-cluster "${CLUSTER_NAME}" --server "${server}" \
    --certificate-authority "${ca_file}" --embed-certs=true >/dev/null
  kubectl config set-credentials build-service --token "${token}" >/dev/null
  kubectl config set-context build-service --cluster "${CLUSTER_NAME}" --user build-service \
    --namespace "${BUILDS_NAMESPACE}" >/dev/null
  kubectl config use-context build-service >/dev/null
)

echo
echo "Cluster '${CLUSTER_NAME}' is wired to ${REGISTRY_HOST} and has the ${BUILDS_NAMESPACE} namespace."
echo "build-service kubeconfig (its own RBAC, valid ${SERVICE_TOKEN_DURATION}): ${SERVICE_KUBECONFIG}"
echo "Refresh it by rerunning this script."
echo "Admin kubeconfig for other tooling:"
echo "  kind get kubeconfig --name ${CLUSTER_NAME} > ${SCRIPT_DIR}/${CLUSTER_NAME}.kubeconfig"
