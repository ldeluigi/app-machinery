# 6. How it works

For changing the machinery, or understanding why it is built this way.

## Layout

```
.github/workflows/        reusable workflows the apps call (ci, ios, release, store-listing, pull-request-title,
                          automerge, retry, ci-fix-agent, issue-agent, upgrade-agent, refresh, health), and the
                          machinery's own (self-*.yml)
.github/actions/          setup-build, claude, copilot, agent (Claude, then Copilot), publish-commits
.github/scripts/          the scripts the workflows and you run: forbidden names, issue references, health report,
                          settings, secrets, take-agents-off, out-of-credit
plugins/                  the Gradle convention plugins (machinery.*), published on the machinery's GitHub Pages
tools/release             the stores' "What's new" (translated by Claude) and the listing's graphics
tools/bump                the versions Dependabot can't see (JDK, Android SDK, Copilot CLI, Claude Code)
gradle/libs.versions.toml every version the plugins give the apps
settings/                 repository settings, labels and rulesets as code
release/releaserc.yml     semantic-release, as the apps' releases run it
```

## How an app calls a workflow

An app's `.github/workflows/` holds only the triggers (GitHub requires them in the app's own files) and one line per
job, pinned to a machinery commit:

```yaml
jobs:
  ci:
    uses: <owner>/app-machinery/.github/workflows/ci.yml@<commit> # v1.4.0
    secrets: inherit
```

Each reusable workflow then checks out the machinery at its own commit (`job.workflow_sha`) into `.machinery/`, so its
actions, scripts and tools are always the same version as the workflow. Dependabot moves the pinned commit when the
machinery releases.

## How an app gets the plugins

The app pins one machinery version in its `gradle/libs.versions.toml` (`machinery = "1.4.0"`) and applies the plugins it
needs (`machinery.compose-library`, `machinery.android-application`, ...), from the Maven repository on
`https://<owner>.github.io/app-machinery/maven`. The plugins carry the Kotlin, Android Gradle plugin and Compose
Multiplatform versions, the SDK levels, the test libraries and the static analysis settings: one version bump in the app
brings them all, tested together. The app's own catalog lists only the libraries its code uses.

## How the machinery maintains itself

- **Dependabot** bumps the actions and the Gradle plugins and libraries of its catalog, weekly, as `fix` commits.
- **Self refresh** bumps what Dependabot can't see, monthly.
- **Self CI** checks every change: actionlint (with shellcheck), pinned actions, Conventional Commits, the plugins' and
  tools' builds and tests.
- **Self automation** merges Dependabot's and the bot's pull requests once green, re-runs failures, and gives the rest
  to the CI-fix agent, as for an app.
- **Self release** tags each `feat` or `fix` and publishes the plugins of that version. Dependabot then offers it to
  every app, whose own CI checks it before it merges.

## Choices

- **Reusable workflows rather than a copied template**: a fix lands in every app through one Dependabot pull request.
- **Claude first, Copilot as the fallback, both as CLIs in Actions**: GitHub's Copilot cloud agent opens draft pull
  requests that wait for approvals, and GitHub Agentic Workflows can't run Claude on a subscription.
- **Plugins on GitHub Pages rather than Maven Central**: no Sonatype account, no signing keys; Gradle and Dependabot
  read any Maven repository.
- **The bot rather than the workflow token**: GitHub starts no workflow from the workflow token's pushes and merges.
- **Agents never change workflows**: the bot's token for their commits has no Workflows permission, so an agent can't
  widen its own powers.
