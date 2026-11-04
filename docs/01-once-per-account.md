# 1. Once per account

Do these once. Every app after the first reuses them. Each step says where to click, what to type, and how to check
it worked. Tick the boxes as you go.

Words used below:
- **`<owner>`**: the GitHub account that owns the apps and this machinery: yours, or an organization (step 1).
- **the bot**: the GitHub App that pushes, labels and merges for the automation (step 2).

## 0. Tools on your computer

- [ ] **GitHub CLI**: on Windows `winget install GitHub.cli`, on a Mac `brew install gh`. Then `gh auth login` and
      answer: GitHub.com → HTTPS → Yes → Login with a web browser.
      Check: `gh auth status` says "Logged in".
- [ ] **Google Cloud CLI** (only for Google Play uploads): <https://cloud.google.com/sdk/docs/install>, then
      `gcloud auth login`. The Cloud Shell in the browser (<https://shell.cloud.google.com>) works too, with nothing to
      install.
- [ ] **A JDK 21 or newer** to run Gradle (Android Studio brings one), and **Claude Code** for step 4:
      <https://code.claude.com/docs>.

## 1. An organization (optional)

The apps, this machinery and the template can live in your own account: every app's workflows and the plugins' web
address then mention your username. To keep it out, put them all in an organization instead:

- [ ] github.com → your picture (top right) → **Your organizations** → **New organization** → **Free**.
- [ ] Name: neutral and short, like a studio name. It shows in URLs (`github.com/<owner>/...`) and can be renamed
      later, but every app's workflows mention it. Contact email: yours (private). "My personal account".
- [ ] Skip inviting members. Check: <https://github.com/orgs/<owner>> opens.
- [ ] Repositories you already have can move there: the repository → **Settings → General → Danger Zone → Transfer**.
      GitHub redirects the old URLs.

## 2. The bot (a GitHub App)

The automation acts as this App, not as you, and not with the workflows' own token: GitHub doesn't start workflows from
that token's pushes, so a merged update would never be released.

**Already have one** (the first app's bot)? Move it instead of making a new one:
- [ ] Your **Settings → Developer settings → GitHub Apps** → the App → **Advanced → Transfer ownership** → `<owner>`.
- [ ] In the App's settings (now under the organization: **`<owner>` Settings → Developer settings → GitHub Apps**):
      **General → GitHub App name**: a neutral name, e.g. `<owner>-bot`. Its login becomes `<name>[bot]`.
- [ ] Then do the permissions and installation below, as for a new one, and skip "Generate a private key" if you still
      have its `.pem` file.

**Making a new one:**
- [ ] **`<owner>` Settings → Developer settings → GitHub Apps → New GitHub App**.
  - Name: e.g. `<owner>-bot`. Homepage URL: `https://github.com/<owner>`.
  - **Webhook**: untick **Active**.
  - **Repository permissions**, set to **Read and write**: Administration, Contents, Environments, Issues, Pull requests,
    Workflows. Leave the others as they are (Metadata stays read-only).
  - **Where can this GitHub App be installed?** Only on this account.
  - **Create GitHub App**.
- [ ] On the App's page: note the **Client ID** (`Iv23…`). Its login is its name plus `[bot]`, e.g. `<owner>-bot[bot]`.
- [ ] **Generate a private key**: a `.pem` file downloads. Keep it in your password manager. A lost key is no disaster:
      generate a new one, and store it again (docs/04-renewals.md).
- [ ] **Install App** → `<owner>` → **All repositories** → Install. New apps then get the bot by themselves.
- [ ] After changing an App's permissions, GitHub asks the installation to accept them: **`<owner>` Settings → GitHub
      Apps → the App → Configure** shows a banner, **Review request → Accept**.

## 3. The machinery's own home

- [ ] Create the repository `<owner>/app-machinery`, **public**, from this one (or transfer it there).
- [ ] **Settings → Pages → Build and deployment → Source: Deploy from a branch → `gh-pages` / `(root)` → Save.**
      The Gradle plugins are published there (`https://<owner>.github.io/app-machinery/maven`).
- [ ] Give the machinery its secrets, as you will for each app (step 6 shows the file):
      `.github/scripts/set-secrets.sh <owner>/app-machinery machinery.env`.
- [ ] Apply its settings once by hand: **Actions → Self automation → Run workflow → settings**. From then on it runs
      monthly, and whenever `settings/` changes, for
      every repository the bot is installed on.

## 4. Claude (the main AI agent)

The agents run Claude Code on your Claude subscription (Pro or Max), within its usage limits, shared with your own use.
An Anthropic API key works too, paid per use.

- [ ] On your computer, with Claude Code installed and signed in: `claude setup-token`. Copy the token it prints.
      It lasts **one year**: put a reminder in your calendar 11 months from today.
- [ ] Keep it for step 6 as `CLAUDE_CODE_OAUTH_TOKEN` (or an API key as `ANTHROPIC_API_KEY`).

## 5. Copilot (the fallback AI agent)

When Claude can't finish (its limit is reached, its token expired, it errs), the Copilot CLI takes over, on your GitHub
Copilot plan.

- [ ] **Your Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
      Name `copilot-agents`, the longest expiry offered, **Repository access: Public repositories**, **Account
      permissions → Copilot Requests: Read-only**. Generate, copy.
- [ ] Keep it for step 6 as `COPILOT_GITHUB_TOKEN`. The monthly refresh opens an issue 30 days before it expires.
- [ ] Turn off GitHub's own Copilot cloud agent for the apps, so `@copilot` comments reach only the workflows (its pull
      requests start as drafts that wait for you): <https://github.com/settings/copilot/coding_agent> → **Repository
      access: No repositories** → Save.

## 6. The secrets file

Every app gets the same secrets, from one file on your computer that you never commit. Make it once:

```
# The bot (step 2)
GH_APP_CLIENT_ID=Iv23...
GH_APP_LOGIN=<owner>-bot[bot]
GH_APP_PRIVATE_KEY=@C:/path/to/the-bot.private-key.pem
# The agents (steps 4 and 5)
CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-...
COPILOT_GITHUB_TOKEN=github_pat_...
```

Save it as `machinery.env` in a private folder (your password manager can attach it). Each app adds its own lines to a
copy of it (docs/02-new-app.md). `.github/scripts/set-secrets.sh <owner>/<repo> <file>` stores each line where the
workflows read it, printing only the names.

## 7. Google Play (Android)

- [ ] **A Google Play developer account**: <https://play.google.com/console/signup>. 25 USD once. A personal account
      made after November 2023 must run a closed test with 12 testers for 14 days before an app's first production
      release; an organization account (with a D-U-N-S number) doesn't, and doesn't publish your legal name.
- [ ] **A support address**, so your own email stays off the stores: a new Gmail account, brand-neutral, forwarding
      everything to you. The first app's guide (docs/02-new-app.md, step 5a) has every click; one address can serve
      every app.
- [ ] **Developer account settings** (Play Console → Developer account): **Contact details → How users contact you**:
      the support address, verified. **How Google contacts you**: your own. **About you → Developer name**: neutral.
- [ ] **A Google Cloud project for uploads**, once (the Cloud Shell works): choose a project id, e.g.
      `<owner>-publishing`, then

  ```sh
  gcloud projects create <project-id>
  gcloud services enable androidpublisher.googleapis.com iamcredentials.googleapis.com sts.googleapis.com --project <project-id>
  gcloud iam service-accounts create publisher --project <project-id>
  gcloud iam workload-identity-pools create github --location=global --project <project-id>
  gcloud iam workload-identity-pools providers create-oidc github \
    --location=global --workload-identity-pool=github --project <project-id> \
    --issuer-uri=https://token.actions.githubusercontent.com \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
    --attribute-condition="assertion.repository_owner=='<owner>'"
  gcloud projects describe <project-id> --format="value(projectNumber)"   # note this: <project-number>
  ```

  This lets GitHub Actions of `<owner>`'s repositories, and nothing else, ask for the service account. Each app then
  allows its own repository (docs/02-new-app.md). Add to the secrets file:

  ```
  GCP_WIF_PROVIDER=projects/<project-number>/locations/global/workloadIdentityPools/github/providers/github
  GCP_SERVICE_ACCOUNT=publisher@<project-id>.iam.gserviceaccount.com
  ```

- [ ] **Play Console → Users and permissions → Invite new users**: `publisher@<project-id>.iam.gserviceaccount.com`,
      no account permissions yet (each app adds its own).

## 8. Apple (iOS)

Builds and tests for iOS run on GitHub's Macs: no Mac needed. Publishing on the App Store needs Apple's paid program.

- [ ] **Apple Developer Program**: <https://developer.apple.com/programs/enroll/>, with an Apple Account that has
      two-factor authentication. 99 USD a year. Approval can take 48 hours. As an individual, your name is the
      seller's name on the App Store; an organization (with a D-U-N-S number) shows its own.
- [ ] Note your **Team ID**: <https://developer.apple.com/account> → Membership details. Add `APPLE_TEAM_ID=...` to
      the secrets file.
- [ ] **An App Store Connect API key**, for signing and uploads from GitHub's Macs: <https://appstoreconnect.apple.com>
      → Users and Access → Integrations → **App Store Connect API** → Team Keys → **+** → name `machinery`, access
      **Admin** → Generate. Download the `.p8` file (only once!), note the **Key ID** and the **Issuer ID** above the
      list. Add to the secrets file:

  ```
  ASC_KEY_ID=...
  ASC_ISSUER_ID=...
  ASC_PRIVATE_KEY=@C:/path/to/AuthKey_XXXX.p8
  ```

  The iOS release workflow (TestFlight, then the App Store) uses them; until it exists, they wait in the file.
