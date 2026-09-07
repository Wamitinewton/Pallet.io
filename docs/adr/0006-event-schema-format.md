# 6. Plain JSON event payloads to start

- Status: accepted
- Date: 2026-09-07

## Context

The event backbone (Kafka) needs a payload format. The brief leaves this open:
plain JSON now, or Avro/Protobuf with a schema registry once the catalog needs
real versioning. A schema registry is operational weight and a code-generation
step; the event catalog is still small and changing.

## Decision

Plain JSON, serialized by Jackson, for now. Contracts live as flat Java records
in `platform-common-events`, each implementing `PlatformEvent` (the shared
envelope: `eventId`, `eventType`, `orgId`, `occurredAt`) with the domain fields
alongside, matching the wire sample in `PROJECT.md`. `Topics` holds the topic
name constants.

## Consequences

- No schema registry to run; fast to add and change an event.
- Compatibility is by convention, not enforced — additive changes only, and
  consumers must tolerate unknown fields.
- Revisit (new ADR) when the catalog stabilises or a breaking change is needed:
  moving to Avro/Protobuf + a registry is the expected next step, and keeping
  contracts in one module now makes that migration mechanical.
