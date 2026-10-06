# GitHub App setup

`git-integration-service` talks to GitHub as a GitHub App. This guide registers that App and wires its
credentials into the service and the web dashboard. It starts with the GitHub account that owns the
App and ends with a push on GitHub turning into a `git.push.received` event on your machine.

The design decisions behind the App are recorded elsewhere: why a GitHub App and not an OAuth App, why
these permissions, and how one installation is shared between orgs. See
[ADR-0021](../adr/0021-github-app-source-host-integration.md) and [ARCHITECTURE.md](ARCHITECTURE.md).
This guide only covers setup.

## Contents

1. [What you need at the end](#1-what-you-need-at-the-end)
2. [The account that owns the App](#2-the-account-that-owns-the-app)
3. [A GitHub organization for the App](#3-a-github-organization-for-the-app)
4. [A tunnel for webhooks](#4-a-tunnel-for-webhooks)
5. [Secrets you generate](#5-secrets-you-generate)
6. [Register the development App](#6-register-the-development-app)
7. [Credentials GitHub issues](#7-credentials-github-issues)
8. [Configure git-integration-service](#8-configure-git-integration-service)
9. [Configure the web dashboard](#9-configure-the-web-dashboard)
10. [Run and verify](#10-run-and-verify)
11. [Test repositories and a second account](#11-test-repositories-and-a-second-account)
12. [The production App](#12-the-production-app)
13. [Rotation and leaks](#13-rotation-and-leaks)
14. [Troubleshooting](#14-troubleshooting)
15. [Checklist](#15-checklist)

---

## 1. What you need at the end

| Variable | What it is | Comes from | Lives in | Secret |
|---|---|---|---|---|
| `PALLET_GIT_GITHUB_APP_ID` | Numeric App ID | GitHub | `services/git-integration-service/keys.properties` | No |
| `PALLET_GIT_GITHUB_CLIENT_ID` | The App's client ID (`Iv23li…`) | GitHub | `keys.properties` | No |
| `PALLET_GIT_GITHUB_APP_SLUG` | The App's URL name | GitHub, derived from the App name | `keys.properties` | No |
| `PALLET_GIT_GITHUB_CLIENT_SECRET` | Exchanges a user's `code` for a user token | GitHub | `keys.properties` | Yes |
| `PALLET_GIT_GITHUB_PRIVATE_KEY` | Signs the App JWT that mints installation tokens | GitHub, downloaded once | `keys.properties` | Yes, the most sensitive one |
| `PALLET_GIT_WEBHOOK_SECRET` | HMAC key GitHub signs each webhook with | You | `keys.properties` and the App's settings | Yes |
| `PALLET_GIT_WEBHOOK_SECRET_PREVIOUS` | The old webhook secret, set only during a rotation | You | `keys.properties` | Yes |
| `PALLET_GIT_STATE_SIGNING_KEY` | Signs the single-use `state` on install and authorize redirects | You | `keys.properties` | Yes |
| `PALLET_GIT_USER_SESSION_KEY` | AES-GCM key for GitHub user sessions in Redis | You | `keys.properties` | Yes |
| `NEXT_PUBLIC_GITHUB_APP_SLUG` | The same slug, for the dashboard | GitHub | `web/.env.local` | No, it is compiled into the browser bundle |

`keys.properties` and `web/.env.local` are git-ignored. Keep their values out of commits, issues,
chats, screenshots and log searches.

Pallet holds no long-lived credential for anyone's code. The private key signs JWTs that live ten
minutes, those JWTs mint installation tokens that live an hour in memory, and user tokens sit in an
encrypted Redis session for at most an hour. Whoever has the private key can forge all of that, so it
gets the most care below.

### Why two Apps

A GitHub App has one webhook URL and one callback target. In development both point at your machine;
in production they point at the deployed gateway and dashboard, so one App can't serve both. Keeping
them apart also means a development key can't reach a production installation, and test installs
never appear in production. Register the development App now (sections 3 to 11) and the production
App once something is deployed (section 12).

---

## 2. The account that owns the App

Use a dedicated GitHub account for the App rather than your personal one. GitHub's terms allow one
machine account per person alongside a personal account, provided a person is responsible for it.

1. Pick an email address you will keep: a mailbox on a domain you own (`github@<your-domain>`) or a
   dedicated mailbox. GitHub won't let one address be primary on two accounts, and a shared address
   lets the account move to a team later.
2. Sign out of GitHub, or open a separate browser profile for this account. A separate profile is
   easier to live with.
3. Sign up at <https://github.com/signup>. The username is public and shows on the App's page, so
   choose something users can recognize, such as `<product>-platform` or `<product>-bot`.
4. Verify the email address.
5. Turn on two-factor authentication: Settings → Password and authentication → Enable two-factor
   authentication. Prefer a passkey or hardware key, with a TOTP app as the backup. Save the recovery
   codes in your password manager. If you lose both the second factor and the codes, you lose the
   account and the App with it.
6. Keep the password, the 2FA backup and the recovery codes in one password manager entry.
7. Optional: Settings → Emails → Keep my email addresses private.
8. Settings → Public profile: set a display name and an avatar. People see them when they install the
   App.

Don't create a personal access token on this account. Nothing uses one.

---

## 3. A GitHub organization for the App

You can register the App under the account directly, but a free GitHub organization makes ownership
easier to manage:

- You can add a second owner, or transfer the organization later, without sharing a password.
- You can make someone an App manager without making them an organization owner.
- The App is listed under the organization's name, which reads as a product rather than a person.

To create one, signed in as the owning account:

1. Avatar menu → Your organizations → New organization → Free.
2. Pick a name. Organization names and usernames share one namespace, so it has to differ from the
   account's username.
3. Use the email address from section 2 as the contact email.
4. Under "This organization belongs to", choose My personal account.
5. Organization → Settings → Authentication security → Require two-factor authentication for everyone
   in the organization.
6. Invite your personal GitHub account as a second owner, so a problem with the owning account doesn't
   lock you out. That account needs 2FA too.

Without an organization, every step below is the same; you open the App's settings from the account's
Settings → Developer settings → GitHub Apps instead.

---

## 4. A tunnel for webhooks

GitHub delivers webhooks over the internet and can't reach `localhost`. Every request goes through
`api-gateway` on port `8083`, which routes `/api/v1/git-integration/webhooks/github` to the service as
a public path with rate limiting off (`config-repo/api-gateway.yml`). The tunnel therefore points at
the gateway, not at the service's own port `8085`.

The tunnel has to forward the request body unchanged. The service checks GitHub's
`X-Hub-Signature-256` HMAC over the raw bytes, and a relay that parses and re-serializes the JSON (some
`smee.io` clients do this) makes every delivery fail with `401`.

ngrok with a free static domain is the simplest choice for development, because the URL survives
restarts and you set it in the App once:

```bash
# install from https://ngrok.com/download, sign up, and claim your free static domain in the ngrok dashboard
ngrok config add-authtoken <ngrok-authtoken>      # stored in ngrok's own config, outside the repo
ngrok http --url=<your-static-domain> 8083
```

A cloudflared quick tunnel needs no account, but its URL changes on every start, so you would update
the App's webhook URL each session:

```bash
cloudflared tunnel --url http://localhost:8083
# prints https://<random-words>.trycloudflare.com
```

If you have a domain on Cloudflare, a named cloudflared tunnel gives a stable hostname such as
`hooks-dev.<your-domain>`.

Your webhook URL is:

```
https://<tunnel-host>/api/v1/git-integration/webhooks/github
```

Only webhooks need the tunnel. The callback is a browser redirect, and your browser reaches
`localhost:5173` directly.

---

## 5. Secrets you generate

Three of the secrets come from you, not from GitHub. Generate them before registering the App, since
the webhook secret goes into GitHub's form.

If `keys.properties` doesn't exist yet, create it first:

```bash
cd services/git-integration-service
make dev-secrets          # writes keys.properties, mode 600, git-ignored; --force overwrites it
```

`make dev-secrets` writes the five secret entries with random values so the service can start. The
private key and client secret it writes are placeholders that GitHub has never seen; section 8
replaces them. It doesn't write the App ID, client ID or slug at all, because
`config-repo/git-integration-service.yml` gives those local defaults (`local-app-id`,
`local-client-id`, `pallet-local`) that let the service start but can't talk to GitHub.

The three generated values it writes (webhook secret, state signing key, user session key) are
already random and usable. To generate fresh ones instead:

```bash
openssl rand -hex 32       # webhook secret; also goes into the App's form in section 6
openssl rand -base64 32    # state signing key
openssl rand -base64 32    # user session encryption key (AES-256-GCM)
```

| Variable | Format | Requirement |
|---|---|---|
| `PALLET_GIT_WEBHOOK_SECRET` | Any string; hex is easiest | At least 32 random bytes |
| `PALLET_GIT_STATE_SIGNING_KEY` | Base64 | 32 bytes once decoded |
| `PALLET_GIT_USER_SESSION_KEY` | Base64 | 32 bytes once decoded |

---

## 6. Register the development App

Signed in as the owning account, open the organization → Settings → Developer settings → GitHub Apps
→ New GitHub App. Fill in the form as follows.

### Basic information

| Field | Value |
|---|---|
| GitHub App name | Something that is obviously not production, such as `<product>-dev`. Names are unique across GitHub. |
| Description | "Development instance. Not for real deployments." |
| Homepage URL | The repository URL, or `http://localhost:5173` |

### Identifying and authorizing users

| Field | Value |
|---|---|
| Callback URL | `http://localhost:5173/github/callback` |
| Expire user authorization tokens | Checked. The service discards the refresh token anyway, and expiry caps how long a user token works. |
| Request user authorization (OAuth) during installation | Checked. A fresh install then returns with `code` and `state`, and the dashboard links it in one step. |
| Enable Device Flow | Unchecked |

The service never sends a `redirect_uri`, so GitHub always returns to the Callback URL set here and a
caller can't change it.

### Post installation

| Field | Value |
|---|---|
| Setup URL | Disabled while "Request user authorization during installation" is checked; GitHub uses the Callback URL instead. |
| Redirect on update | Checked, so changing the repository selection also returns to the dashboard. |

`/github/callback` (`web/src/app/github/callback/page.tsx`) handles each kind of return: a plain
authorization (`code`, `state`), a fresh install (`installation_id`, `code`, `state`), an update, and an
install request from a GitHub organization member who isn't an owner (`setup_action=request`).

### Webhook

| Field | Value |
|---|---|
| Active | Checked |
| Webhook URL | `https://<tunnel-host>/api/v1/git-integration/webhooks/github` |
| Webhook secret | Exactly the `PALLET_GIT_WEBHOOK_SECRET` value in `keys.properties` |
| SSL verification | Enabled |

### Permissions

Repository permissions are exactly these three; leave the rest at "No access".

| Permission | Access | Used for |
|---|---|---|
| Metadata | Read-only | Required for every App. Names, default branch, visibility, and the daily check of each verifier's role. |
| Contents | Read-only | Branch heads, the compare API, fetching source for builds. GitHub also requires it for `push` events. |
| Checks | Read and write | Build and deploy results on commits. |

No organization or account permissions. The user token only lists the user's installations and
repositories, which needs none.

If GitHub answers `403 Resource not accessible by integration` during testing, add the smallest read
permission it names to the App and record it in ADR-0021.

### Events

Subscribe to Push and Repository. GitHub sends `installation`, `installation_repositories` and
`github_app_authorization` to every App without a checkbox. The service acknowledges and ignores any
other event, so leave the rest unchecked.

### Where can this GitHub App be installed?

"Only on this account" restricts installs to the owning organization. Testing installs on your
personal account (section 11) needs "Any account". Start with the first and switch when you reach
those tests. After creation the setting lives on the App's Advanced tab, under Make public.

A private App also hides its authorize page from everyone except the owning account. Starting the
GitHub connection while signed in to GitHub as anyone else shows GitHub's 404 page.

Click Create GitHub App.

---

## 7. Credentials GitHub issues

You land on the App's General page.

1. App ID, near the top: `PALLET_GIT_GITHUB_APP_ID`.
2. Client ID, just below it (`Iv23li…`): `PALLET_GIT_GITHUB_CLIENT_ID`.
3. The slug is the last segment of the App's public link, `https://github.com/apps/<slug>`. It goes
   into `PALLET_GIT_GITHUB_APP_SLUG` and `NEXT_PUBLIC_GITHUB_APP_SLUG`. Install URLs are built from it
   (`https://github.com/apps/<slug>/installations/new?state=…`), so it must match exactly.
4. Client secrets → Generate a new client secret. GitHub shows it once; put it straight into your
   password manager. This is `PALLET_GIT_GITHUB_CLIENT_SECRET`.
5. Private keys → Generate a private key. The browser downloads `<app-name>.<date>.private-key.pem`.
   GitHub keeps only the public half, so this file is the only copy.

Move the key out of your downloads folder, and out of the repository if the browser saved it there.
The repository's `.gitignore` doesn't match `.pem` files, so a key left in the working tree is one
`git add .` away from a commit.

```bash
mkdir -p ~/.pallet-secrets && chmod 700 ~/.pallet-secrets
mv ~/Downloads/<app-name>.*.private-key.pem ~/.pallet-secrets/<app-name>.pem
chmod 600 ~/.pallet-secrets/<app-name>.pem

# should print "Private-Key: (2048 bit, 2 primes)" or larger; the service refuses anything smaller
openssl rsa -in ~/.pallet-secrets/<app-name>.pem -noout -text | head -1
```

GitHub issues keys in PKCS#1 format (`-----BEGIN RSA PRIVATE KEY-----`). The service reads PKCS#1 and
PKCS#8, so you don't need to convert it. Attach the `.pem` to the password manager entry too, so a lost
laptop doesn't force a new key.

---

## 8. Configure git-integration-service

`application.yaml` imports `keys.properties` from the service directory, and
`config-repo/git-integration-service.yml` reads each value through a `${PALLET_GIT_…}` placeholder.
Locally the file fills those placeholders. In a deployed environment the same names arrive as
environment variables from Vault or Kubernetes Secrets.

When you're done, `services/git-integration-service/keys.properties` has these eight entries (the
values below are placeholders):

```properties
PALLET_GIT_GITHUB_APP_ID=1234567
PALLET_GIT_GITHUB_CLIENT_ID=Iv23liXXXXXXXXXXXXXX
PALLET_GIT_GITHUB_APP_SLUG=<slug>
PALLET_GIT_GITHUB_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----\n
PALLET_GIT_GITHUB_CLIENT_SECRET=<client secret from section 7>
PALLET_GIT_WEBHOOK_SECRET=<the same value as the App's webhook secret>
PALLET_GIT_STATE_SIGNING_KEY=<base64>
PALLET_GIT_USER_SESSION_KEY=<base64>
```

Add the App ID, client ID and slug lines yourself, and replace the client secret. The service rejects
a blank value for any of them, so don't leave a line empty.

A `.properties` value fits on one line, so the PEM goes in with literal `\n` escapes. Let the shell
build that line rather than editing it by hand:

```bash
cd services/git-integration-service
key_line="$(awk 'BEGIN{ORS="\\n"} 1' ~/.pallet-secrets/<app-name>.pem)"
sed -i '/^PALLET_GIT_GITHUB_PRIVATE_KEY=/d' keys.properties     # GNU sed; on macOS use sed -i ''
printf 'PALLET_GIT_GITHUB_PRIVATE_KEY=%s\n' "$key_line" >> keys.properties
unset key_line
```

Pass the key through `printf` as above. Handing it to `awk -v` or a `sed` replacement turns the `\n`
escapes back into real newlines, and the base64 contains `/` and `+`, which break a `sed` pattern.

Check the file without printing a secret:

```bash
ls -l keys.properties                      # -rw------- (owner only)
git check-ignore -v keys.properties        # prints the .gitignore rule that matches it
cut -d= -f1 keys.properties                # the eight names above
git status --short                         # no keys.properties, no .pem
```

To confirm the stored key matches the file, compare the public keys:

```bash
diff <(grep '^PALLET_GIT_GITHUB_PRIVATE_KEY=' keys.properties | cut -d= -f2- | sed 's/\\n/\n/g' \
        | openssl rsa -pubout 2>/dev/null | sha256sum) \
     <(openssl rsa -in ~/.pallet-secrets/<app-name>.pem -pubout 2>/dev/null | sha256sum) && echo match
```

At startup the service checks that the private key is RSA of at least 2048 bits
(`SecretMaterialValidator`) and that each required value is present. It refuses to start otherwise,
and names what's wrong.

Leave `PALLET_GIT_GITHUB_API_BASE_URL` and `PALLET_GIT_GITHUB_WEB_BASE_URL` unset. Their defaults
are `https://api.github.com` and `https://github.com`; the variables exist for tests and a possible
GitHub Enterprise Server.

---

## 9. Configure the web dashboard

```bash
cd web
cp .env.example .env.local        # only if .env.local doesn't exist; it's git-ignored
```

In `web/.env.local`:

```dotenv
PALLET_GATEWAY_URL=http://localhost:8083
PALLET_PUBLIC_BASE_URL=http://localhost:5173
PALLET_SESSION_ENCRYPTION_KEY=<openssl rand -base64 32, separate from the service's keys>
NEXT_PUBLIC_GITHUB_APP_SLUG=<slug>
```

The dashboard has no GitHub secret. It asks the service, through the gateway, for an install or
authorize URL, sends the browser to GitHub, and on return posts `code`, `state` and `installation_id`
back to the service from `/github/callback`. Values prefixed `NEXT_PUBLIC_` are compiled into the
browser bundle, which is why only the slug is here.

`PALLET_PUBLIC_BASE_URL` and the App's Callback URL must share an origin (`http://localhost:5173`).
If they differ, GitHub returns the browser to a host without the dashboard's session cookie, and the
callback page sends you to sign in.

---

## 10. Run and verify

```bash
make up                                   # repo root: postgres, redis, kafka, keycloak, mailpit
make obs                                  # optional: prometheus, grafana, jaeger

# one terminal each
(cd services/config-server && ./mvnw spring-boot:run)
(cd services/identity-service && ./mvnw spring-boot:run)
(cd services/org-team-service && ./mvnw spring-boot:run)
(cd services/notification-service && ./mvnw spring-boot:run)
(cd services/api-gateway && ./mvnw spring-boot:run)
(cd services/git-integration-service && make run)
(cd web && pnpm dev)

ngrok http --url=<your-static-domain> 8083
```

Then check each step in order:

1. On the App's Advanced tab, under Recent deliveries, redeliver the `ping`. It should answer `200`.
   A `401` means the webhook secret on GitHub and `PALLET_GIT_WEBHOOK_SECRET` differ, or the tunnel
   changed the body. A `502` or `504` from the tunnel means the gateway or the service isn't running.
2. Without GitHub involved, `scripts/send-webhook.sh <fixture.json>` (run from the service directory)
   signs a recorded fixture with your local secret and posts it.
3. Sign in at <http://localhost:5173>, open an org you administer, go to GitHub and connect. GitHub
   asks which account and repositories to install on, then which permissions to authorize, and returns
   to `/github/callback`, which links the installation and takes you back to the org's GitHub page.
4. Recent deliveries shows `installation.created` answered `202`.
5. The repository picker lists only repositories that both the installation and your GitHub user can
   reach.
6. Link a repository to an app and push a commit. Recent deliveries shows `push` answered `202`, and a
   record appears on `git.push.received` (Kafka UI on `:8090` with `make up-all`).

[Checkpoint 21](../workflows/git-integration-service/21-end-to-end-with-github.md) has the full list of
scenarios, including the failure cases.

The unit and integration tests (`./mvnw test`, `./mvnw clean verify`) don't use any of this. They run
against WireMock and recorded fixtures and don't read `keys.properties`.

---

## 11. Test repositories and a second account

Sharing an installation, the push-permission floor and losing access each need some setup on GitHub:

1. Two scratch repositories on the account you install on, one public and one private, each with a
   commit on `main`. For the monorepo scenario, give one a `web/` folder.
2. A second GitHub user with read-only access to the private repository, such as a collaborator or a
   test account.
3. "Any account" in the App's install setting, so you can install it on your personal account.

Install the App on your personal account, not on the owning account or organization. The owning
account holds the App; the repositories it builds belong to users.

---

## 12. The production App

Register it once a gateway and a dashboard are deployed on public hostnames. Repeat sections 5 to 7
with these differences:

| Setting | Development | Production |
|---|---|---|
| Name | `<product>-dev` | The product name users will see on the install screen |
| Callback URL | `http://localhost:5173/github/callback` | `https://<dashboard-host>/github/callback` |
| Webhook URL | `https://<tunnel-host>/api/v1/git-integration/webhooks/github` | `https://<api-host>/api/v1/git-integration/webhooks/github` |
| Install on | This account, or any account while testing | Any account |
| Secrets | Generated locally, in `keys.properties` | Generated separately on a trusted machine and stored only in Vault |
| Private key | `~/.pallet-secrets/` | Straight into Vault; delete the download afterwards |
| Webhook IP allowlist | Off | Consider `pallet.git.webhook.github-ip-allowlist.enabled: true` |
| Homepage, description, logo | Anything | A real landing page, description and logo |

Don't reuse a development secret in production, and don't copy the production private key to a
laptop.

In production the same variable names come from Vault or Kubernetes Secrets (see `SECURITY.md`).
Vault can hold the PEM on multiple lines; the service also accepts the `\n`-escaped form.

The dashboard reads `NEXT_PUBLIC_GITHUB_APP_SLUG` when the bundle is built, not at runtime, so the
production build needs the production slug.

GitHub Marketplace is a separate review and isn't required: with "Any account", anyone can install the
App from `https://github.com/apps/<slug>`.

None of the App's credentials belong in GitHub Actions secrets. CI (`.github/workflows/build.yml`)
tests against WireMock and fixtures, and the Actions `GITHUB_TOKEN` already covers reading
`platform-common-*` from GitHub Packages (see [PACKAGES.md](../../PACKAGES.md)).

---

## 13. Rotation and leaks

[RUNBOOK.md](RUNBOOK.md) has the full procedures.

| Secret | How to rotate | Effect on users |
|---|---|---|
| Webhook secret | Runbook §13: new value in `PALLET_GIT_WEBHOOK_SECRET`, old value in `_PREVIOUS`, deploy, change it on GitHub, then clear `_PREVIOUS` | None; both values verify during the swap |
| Private key | Runbook §14: generate a second key on GitHub (both stay valid), deploy it, confirm tokens mint, delete the old key | None |
| Client secret | Generate a new one on GitHub (both stay valid), deploy it, delete the old one | None |
| State signing key | Replace it and deploy | Install or authorize flows started in the last ten minutes fail and have to be restarted |
| User session key | Runbook §15 | Users re-authorize the next time they need GitHub, without a consent prompt |

If the private key leaks (committed, pasted somewhere, on a lost laptop), delete it on the App's
General page first, then generate and deploy a new one. Token minting fails for a few minutes in
between, which is better than leaving someone able to mint installation tokens for every installation.
Then rotate the client secret and the webhook secret, and look through the App's recent deliveries and
the installations' audit logs.

If the owning account is compromised, recover it with the recovery codes, revoke its sessions
(Settings → Sessions), read Settings → Security log, and rotate every App secret. The second
organization owner from section 3 is what keeps you in control while that happens.

Rotate any secret that reaches a commit. Rewriting history doesn't help once the value has been
pushed or shared.

---

## 14. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| The service won't start and names the private key | The key is under 2048 bits, or the line lost its `\n` escapes | Rebuild the line with the commands in section 8 |
| Every delivery gets `401` | The webhook secret differs between GitHub and `keys.properties`, or the tunnel rewrites the body | Paste the value from `keys.properties` into the App and restart the service; use ngrok or cloudflared |
| Deliveries time out or get `502` | The tunnel is down, the gateway or service isn't running, or the tunnel points at `8085` | Point the tunnel at `8083` and check `/actuator/health` |
| The install returns to the dashboard's sign-in page | The Callback URL's origin differs from `PALLET_PUBLIC_BASE_URL`, or the dashboard session expired | Use `http://localhost:5173` for both |
| `400 INVALID_AUTHORIZATION_STATE` | The state is over ten minutes old, was already used (refresh, back button), or the signing key changed mid-flow | Start the flow again |
| The callback has `installation_id` but no `code` | "Request user authorization during installation" is off | Turn it on (section 6) |
| The callback has `setup_action=request` | You installed on a GitHub organization you don't own, so GitHub asked its owners | An owner approves it on GitHub, and the `installation` webhook follows |
| `403 Resource not accessible by integration` | A call needs a permission the App doesn't have | See Permissions in section 6 |
| `GITHUB_CREDENTIAL_REJECTED` or JWT errors | The App ID and the private key belong to different Apps, or the clock is off | Use a matching pair and sync the clock; GitHub rejects JWTs with a skewed `iat` |
| GitHub's 404 page ("This is not the page you were looking for") on `github.com/login/oauth/authorize` | The App is private and you're signed in to GitHub as an account that doesn't own it | Make the App public (Advanced tab), or sign in as the owning account |
| GitHub says the App can't be installed on this account | The App is set to install on its own account only | Switch it to "Any account" |

---

## 15. Checklist

Account and ownership

- [ ] Dedicated email address and GitHub account, 2FA with a passkey or hardware key, recovery codes saved
- [ ] Organization created, 2FA required, a second owner added
- [ ] No personal access token on the owning account

Development App

- [ ] Tunnel to port `8083` on a stable URL
- [ ] Webhook secret, state signing key and user session key generated
- [ ] App registered with Callback `http://localhost:5173/github/callback`, expiring user tokens, user authorization during install, and redirect on update
- [ ] Webhook URL ending in `/api/v1/git-integration/webhooks/github`, secret set, SSL verification on
- [ ] Permissions: Metadata read, Contents read, Checks read and write; events: Push and Repository
- [ ] Client secret and private key saved in the password manager; no `.pem` in the repository
- [ ] `keys.properties` has all eight entries, mode 600, ignored by git
- [ ] `web/.env.local` has the slug and its own session key
- [ ] `ping` redelivers with `200`, and an install returns through `/github/callback` and links
- [ ] A push reaches `git.push.received`

Production App

- [ ] A separate App with its own secrets, generated for production and stored only in Vault
- [ ] Production Callback and Webhook URLs, installable on any account
- [ ] The downloaded private key deleted once it's in Vault
- [ ] The dashboard built with the production slug
