# 1. Record architecture decisions

- Status: accepted
- Date: 2026-09-07

## Context

`PROJECT.md` is the design brief and will keep changing. Decisions that shaped
the brief — and the ones its "open questions" section still has to settle — need
a durable record that says *why*, not just *what*, so a choice is not silently
reversed later or re-argued from scratch.

## Decision

Use lightweight ADRs, one Markdown file per decision under `docs/adr/`, numbered
sequentially, following `0000-adr-template.md`. `PROJECT.md` stays the living
description of the system; ADRs capture point-in-time decisions and their
rationale. When an ADR settles an open question from `PROJECT.md`, that section
of the brief is updated to point here.

## Consequences

- Every non-obvious choice gets a short, reviewable home.
- ADRs are append-only: a reversal is a new ADR that supersedes the old one, and
  the old one stays with its status changed.
- Slight overhead per decision; acceptable for a project whose whole point is
  learning from the decisions.
