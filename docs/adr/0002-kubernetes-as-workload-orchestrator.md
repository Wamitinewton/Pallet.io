# 2. Kubernetes as the workload orchestrator

- Status: accepted
- Date: 2026-09-07

## Context

Pallet has to run both its own control-plane services and arbitrary tenant
workloads. The brief originally left the orchestration layer open (raw
containers, Nomad, Kubernetes, a hand-rolled scheduler). Several other patterns
the project wants to demonstrate — progressive delivery, per-tenant resource
quotas, in-cluster certificate issuance, horizontal autoscaling — already have
mature Kubernetes-native implementations.

## Decision

Kubernetes is the orchestration layer for tenant workloads and for Pallet's own
services. Tenant apps run as a namespaced `Deployment` + `Service` + `Ingress`.
Tenant workloads land on one of two registered clusters (EKS on AWS, GKE on GCP),
chosen by the tenant at app-creation time; the control plane runs in a single
place regardless. `scheduler-service` owns the cluster registry;
`deploy-orchestrator-service` acts through the Kubernetes API of the resolved
cluster, holding a Fabric8 client per registered cluster.

This pulls in cert-manager (ACME), Argo Rollouts (blue/green + canary), the
HorizontalPodAutoscaler, and an in-cluster ingress controller (Traefik or
Contour) as native controllers rather than hand-rolled services.

## Consequences

- Local development needs a real Kubernetes API, not just docker-compose — a
  `kind` cluster (`deploy/local/`) covers this.
- Several services shrink to thin wrappers over Kubernetes objects
  (`autoscaler-service`, `ingress-config-service`, `tls-service`).
- Still open: the sandboxed `RuntimeClass` for tenant pods (gVisor vs Kata), the
  cluster provisioning model (static Terraform vs Cluster API), and network
  isolation (`NetworkPolicy` alone vs a service mesh with mTLS).
