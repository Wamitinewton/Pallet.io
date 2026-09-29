# 21. GitHub App as Pallet's source-host integration

- Status: accepted
- Date: 2026-09-28

## Context

`PROJECT.md` described `git-integration-service` as handling "the GitHub or GitLab OAuth app".
Building it (`docs/git-integration-service/ARCHITECTURE.md`) meant choosing the credential model
every later build depends on: whose authority Pallet acts with on a tenant's repositories, how long
that credential lives, and what happens when one GitHub account is used from several Pallet orgs.
GitHub offers two app types:

| Concern | OAuth App | GitHub App |
|---|---|---|
| Whose authority | Acts as the user who authorized it, with every repo that user can reach | Acts as the app, only on repositories the installer selected |
| Permissions | Coarse scopes (`repo` grants read and write to all private repos) | Fine-grained: `contents: read`, `metadata: read`, `checks: write` and nothing else |
| Credential lifetime | Long-lived user token, stored by Pallet | Installation token valid for one hour, minted on demand, never stored |
| When the user leaves the company | Their token (and Pallet's access) goes with them | The installation belongs to the GitHub organization and keeps working |
| Webhooks | Per-repository hooks Pallet has to create and clean up | One app-level webhook URL receiving every installation's events |
| Rate limit | Shared with the user | Per installation, and it grows with the installation's size |

A GitHub account can install an app only once, so one installation has to serve every Pallet org
its users deploy from: a personal org and each team org, or several team orgs inside one company.
An installation token reaches every repository the installer selected, which for a company
installation can be hundreds of repositories, while any one employee can reach only some of them.
Sharing an installation between orgs is therefore only safe if no org can get more from it than
its own members could get from GitHub directly.

## Decision

1. **Pallet integrates with GitHub as a GitHub App**, not an OAuth App. This amends `PROJECT.md`.
2. **Three permissions, nothing else.** Metadata read (mandatory; names, default branch,
   visibility), Contents read (branch heads, the compare API, fetching source for builds, and
   required to receive `push`), Checks write (build and deploy results on the commit).
3. **Webhook subscriptions**: `push`, `installation`, `installation_repositories`, `repository`,
   `github_app_authorization`. `ping` is answered; anything else is acknowledged and ignored.
4. **Installations are shared between orgs.** Any org may link an installation that its admin can
   see on GitHub, proved with that admin's own user token (`GET /user/installations`), never
   with the `installation_id` a request carries. Linking an installation grants an org nothing
   on its own.
5. **Every repository link is vouched for by a GitHub user with at least `push`** on that
   repository, checked with that user's own token at link time (`GET /repositories/{id}` answers
   only where the user and the installation overlap). `push` rather than read, because wiring a
   repository into a Pallet org is a decision for someone with write access. The floor is
   configurable (`pallet.git.link.min-repo-permission`). Nothing the installation can reach is
   shown to a user unless the user can reach it too, and error codes never distinguish the two.
6. **Access is re-verified daily.** A job confirms each link's verifier still holds the floor
   permission and disconnects the link (`VERIFIER_ACCESS_LOST`) on a definite answer from GitHub,
   never because GitHub was unreachable.
7. **User tokens are short-lived and read-only.** GitHub's user authorization produces a token
   used only for the access checks above. It is held in an AES-GCM encrypted Redis session for at
   most an hour, never written to Postgres, an event or a log, and the refresh token is discarded.
   Every call that acts on a repository uses an installation token.
8. **Installation tokens and the app JWT are minted on demand and kept in memory only.** The app
   private key exists only in this service (from Vault in deployed environments) and must be an
   RSA key of at least 2048 bits, or the service refuses to start.
9. **An installation no org uses is uninstalled after 30 days** (`pallet.git.unused-installation.grace`),
   after the org whose unlink or deletion caused it has been told how to uninstall it themselves.

The daily re-verification needs no fourth permission. GitHub's "Permissions required for GitHub
Apps" reference lists `GET /repos/{owner}/{repo}/collaborators/{username}/permission` under
repository **Metadata: read**, callable with an installation access token; `GET /user/{account_id}`,
used to follow a renamed verifier by id, needs no permission. The job calls both through the
`/repositories/{id}/...` form with one metadata-only installation token per installation. The
live call with a real App is part of the end-to-end walk (checkpoint 21); if GitHub ever answers
`403 Resource not accessible by integration` there, the smallest read permission it names is added
here and to the App, not worked around.

## Consequences

- The credential Pallet holds for a tenant's code lives an hour and reaches only what the
  installer selected; a leaked installation token is bounded in time and scope, and no long-lived
  user credential is stored anywhere.
- Each installation brings its own rate-limit budget, which is why background work is shared out
  round-robin between the orgs on an installation and manual builds are limited per app.
- A repository link depends on a named person. When that person loses access on GitHub, the link
  stops within a day and the org's admins are told; there is a verifier handover endpoint for the
  planned case. Up to a day of builds can happen after access is lost; the webhook-driven
  extension (`member`, `organization` events) would shorten that at the cost of more permissions.
- Re-verification reads the verifier's role with the installation's token, not the verifier's own
  (they aren't present), so it spends the installation's background budget, one metadata-only token
  per installation. A custom repository role counts as the base role GitHub reports under it.
- Linking and the repository picker need a GitHub user session, so they depend on Redis; webhooks,
  pushes and builds do not.
- `build-service` cannot receive a credential in `git.push.received`; how it fetches private
  source (Vault-minted, repository-scoped tokens is the recommendation) is its own ADR.
- GitLab and GitHub Enterprise Server stay behind the `ScmProvider` port and are not built now.
