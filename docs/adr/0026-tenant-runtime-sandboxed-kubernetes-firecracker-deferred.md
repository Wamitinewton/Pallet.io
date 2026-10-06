# 26. Tenant runtime: sandboxed Kubernetes pods behind a `WorkloadRuntime` port, Firecracker deferred

- Status: proposed
- Date: 2026-10-06

## Context

ADR-0002 made Kubernetes the orchestrator for tenant workloads. It left three questions open, and
the first one is the subject of this record: what isolates one tenant's code from another's. A
plain pod shares the host kernel with every other pod on the node. Namespaces and cgroups limit
what a process can see and use, but a kernel exploit in one tenant's container reaches the whole
node. Pallet runs arbitrary code from strangers, so that boundary is not enough on its own.

That question reopened a larger one. The strongest isolation model in use today runs each app as a
Firecracker microVM with its own kernel, on hosts the platform manages directly, with a custom
orchestrator in place of Kubernetes. Fly.io works this way, and AWS Lambda and Fargate use
Firecracker underneath. We looked at what adopting that model as Pallet's only runtime would take:

- **Worker hosts with KVM.** Bare metal, or cloud VMs with nested virtualization enabled
  (`*.metal` on AWS, Intel N-series on GCP). These replace the EKS and GKE node pools.
- **A host agent on every worker.** It turns the OCI image from `build-service` into an ext4 root
  filesystem, launches VMs under Firecracker's `jailer`, wires up tap devices and IPs on a
  WireGuard mesh, passes env vars and secrets in through MMDS or vsock, forwards stdout and stderr
  to Kafka, restarts crashed VMs from its own local reconcile loop, and reports capacity as events.
  Most existing agents of this kind are written in Go.
- **Real placement in `scheduler-service`.** It would bin-pack against reported capacity, spread
  replicas across hosts, and reserve capacity atomically so two concurrent deploys can't overbook
  a host.
- **A routing control plane.** Edge proxies (Envoy or similar) need a table mapping each hostname
  to VM addresses. `ingress-config-service` would build it from machine and health events and push
  it over xDS.
- **TLS, rollouts and autoscaling written by us.** `tls-service` runs ACME itself.
  `traffic-routing-service` shifts proxy weights for blue/green and canary.
  `autoscaler-service` runs its own metrics-to-replicas loop.

The design fits the rest of Pallet well. Each host agent can run a Temporal worker on its own task
queue, so the deploy saga schedules `startMachine` on the target host's queue and keeps the rule
that no Pallet service blocks on another. Firecracker snapshots also make scale to zero practical:
an idle VM is written to disk and restored when the proxy gets a request for it.

The cost is the reason this record exists. ADR-0002 deliberately let cert-manager, Argo Rollouts,
the HorizontalPodAutoscaler and an ingress controller do work that this model hands back to us.
About six services in `PROJECT.md` are designed as thin wrappers over Kubernetes objects, and each
would become a system of its own. Most of the deploy path (`build-service`,
`deploy-orchestrator-service`, routing, TLS, DNS) isn't in code yet, and the first push that ends
in a live URL is still several phases away. Pallet exists to practice distributed systems
patterns: event-driven services, sagas with compensation, outbox and inbox, idempotent consumers,
circuit breakers and multi-tenancy. Those all live in the control plane and are the same under
either runtime. What a self-built VM runtime would add is mostly virtualization and Linux
networking, which is useful to learn but is not the stated goal.

## Decision

1. **Kubernetes stays the orchestrator.** ADR-0002 stands as written.

2. **Tenant pods run under a sandboxed `RuntimeClass`.** gVisor is the default. GKE provides it as
   GKE Sandbox, and on EKS it means a node group with gVisor's `runsc` runtime installed. gVisor
   doesn't need KVM, so it runs on ordinary nodes. Kata Containers with the Firecracker hypervisor
   is reserved for a higher-isolation plan tier, scheduled onto a KVM-capable node pool. That gives
   each pod in the tier its own microVM and kernel while keeping every Kubernetes controller.
   `scheduler-service` maps a plan tier to its `RuntimeClass` and node selector, as it already
   does for resource requests.

3. **The deploy saga calls a `WorkloadRuntime` port, not Fabric8 directly.** The port lives in
   `deploy-orchestrator-service`. It covers the workload lifecycle only: `deploy`, `scale`, `stop`
   and `status`. The first and only implementation is a Fabric8 adapter that creates and patches
   the `Deployment`, `Service` and `Ingress`. Saga activities and their compensations call the
   port. The port stays narrow and doesn't try to describe routing, certificates or rollout
   strategy, because on Kubernetes those belong to controllers that work off the API objects the
   adapter writes.

4. **A Firecracker runtime is deferred, not rejected.** If we build it, it will be a second
   `WorkloadRuntime` adapter for one plan tier (most likely a scale-to-zero hobby tier) running
   next to Kubernetes, not in place of it. It follows the design in the Context section: a host
   agent with a per-host Temporal task queue, placement with atomic reservations in
   `scheduler-service`, an event-fed xDS routing table, and ACME in `tls-service`. Any of these
   would reopen the question:
   - the Kubernetes deploy path works end to end, from push to live URL with TLS, and the saga's
     compensations have been exercised against real failures;
   - scale to zero, or the number of idle apps per host, becomes a product requirement that
     Kubernetes can't meet at an acceptable cost;
   - learning how an orchestrator works becomes an explicit goal that is worth pausing the service
     roadmap for.

   Building it would need its own ADR.

## Consequences

**Easier**:

- The isolation question gets an answer without touching the orchestrator choice, and we keep
  cert-manager, Argo Rollouts, the HPA and the ingress controller.
- The deploy path can be built now against `kind` locally and EKS/GKE later, as ADR-0002 planned.
- The saga depends on an interface rather than on Fabric8, which also makes its activities easier
  to test.
- A Firecracker tier remains a possible addition later and doesn't require a rewrite.

**Harder**:

- gVisor intercepts system calls in user space. That adds overhead to syscall-heavy and I/O-heavy
  apps, and a small number of apps that rely on unusual syscalls won't run under it. Users should
  learn this from a clear deploy failure, not from vague slowness.
- The Kata tier needs a separate KVM-capable node pool in each cloud, which is more expensive per
  node and per pod startup.
- Our only adapter is the Kubernetes one, so the port will tend to take on Kubernetes concepts. If
  a second adapter ever arrives, it will probably need some changes to the interface. Keeping it to
  the four lifecycle operations limits that.
- A Firecracker adapter wouldn't get rollouts, routing, autoscaling or TLS from the port. Those
  services would need a second code path for VM-backed apps, which is most of the cost described
  in the Context section.
- Network isolation (`NetworkPolicy` alone, or a service mesh with mTLS) is still open. A
  sandboxed runtime protects the kernel, not the network.

**Follow-up**:

- Define `WorkloadRuntime` and the Fabric8 adapter when `deploy-orchestrator-service`'s build
  phase starts.
- Add the `RuntimeClass` objects and node pools to the `kind` setup and to the EKS and GKE
  Terraform modules.
- When this record is accepted, take "Runtime isolation for tenant workloads" off the still-open
  lists in `docs/adr/README.md` and `PROJECT.md`, and point `PROJECT.md`'s tech stack section here.

## Alternatives considered

- **Replace Kubernetes with Firecracker microVMs now.** This is the model described in the Context
  section. It gives the strongest isolation and the most orchestration work to learn from, but it
  turns six thin services into large ones and delays the first working deploy by a long way. It is
  deferred under decision 4, not dropped.
- **Kata with Firecracker for every tenant.** Every pod would get a VM boundary. Every node pool
  would also need KVM, and every pod would pay the extra startup and memory cost, including on
  tiers whose risk doesn't justify it.
- **No sandbox, only namespaces, cgroups and a restrictive `securityContext`.** This is cheaper and
  simpler, but it leaves one shared kernel between tenants who don't trust each other.
- **HashiCorp Nomad as the orchestrator.** It's simpler to operate than Kubernetes and can schedule
  Firecracker through a driver. But it has no equivalent of cert-manager, Argo Rollouts or the HPA,
  so it has most of the build cost of the Firecracker option without the scale-to-zero benefit.
- **A managed runtime (Cloud Run, Fargate, Azure Container Apps).** The provider handles isolation
  and scaling. That removes most of the orchestration Pallet is meant to teach, and it ties tenant
  workloads to one vendor's API instead of a Kubernetes API we can run anywhere.
