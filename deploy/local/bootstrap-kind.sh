#!/usr/bin/env bash
# Create (or recreate) the local kind cluster used by the deploy path.
# Requires: kind, kubectl.
set -euo pipefail

CLUSTER_NAME="pallet"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v kind >/dev/null 2>&1; then
  echo "kind is not installed: https://kind.sigs.k8s.io/docs/user/quick-start/#installation" >&2
  exit 1
fi

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
  echo "Cluster '${CLUSTER_NAME}' already exists. Delete it with: kind delete cluster --name ${CLUSTER_NAME}"
else
  kind create cluster --config "${SCRIPT_DIR}/kind-cluster.yaml"
fi

kubectl cluster-info --context "kind-${CLUSTER_NAME}"

echo
echo "Export the kubeconfig for tooling that needs it on disk:"
echo "  kind get kubeconfig --name ${CLUSTER_NAME} > ${SCRIPT_DIR}/${CLUSTER_NAME}.kubeconfig"
