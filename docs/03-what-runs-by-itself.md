# 3. What runs by itself

Once an app is set up, this is what happens without you. You only merge your own pull requests (or let an agent do the
work), answer the issues labelled `needs-human`, and do Google's and Apple's reviews' homework.

| When | Workflow (in app-machinery) | What happens |
|---|---|---|
| Every pull request and push | `ci.yml` | Commit messages are Conventional Commits; no forbidden name; no issue number in a commit; actions pinned to commits; `./gradlew check` (tests, detekt, ktlint, copy-paste check, Android Lint, licences, translations) and a debug build; on `main`, Dependabot's Gradle updates and refreshes, the instrumented tests on an Android emulator |
| Pull requests labelled `ios`, pushes to `main` touching code | `ios.yml` | The Kotlin tests on an iOS simulator, the Xcode build, the UI tests, screenshots |
| Every pull request, and its renames | `pull-request-title.yml` | The title is a Conventional Commit of 100 characters at most (it becomes the commit on `main`) |
| Dependabot's and the bot's pull requests | `automerge.yml` | GitHub merges them once CI is green; build-only updates merge as `build(deps)`, which releases nothing |
| After CI on `main` | `release.yml` | semantic-release decides the version from the commits → signed bundle → GitHub Release with the changelog → "What's new" from the last releases, translated by Claude into the app's languages → Google Play, with the in-app update priority (major 5, feature 3, fix 1) |
| After a release | `store-listing.yml` | The store listing's icon, feature graphic and screenshots that changed are replaced |
| Weekly (Monday) | Dependabot | Updates of the app's libraries, and of the machinery (workflows and plugins) when it released |
| Monthly (1st) | `refresh.yml` | JDK bump, health report (deprecations, credentials about to expire, stuck pull requests) in a pull request; an agent fixes what code can fix, checks Google Play's and Apple's latest policies against the app, and opens one issue for what only you can do |
| A failed run | `retry.yml` | Re-run once (network hiccups, lost runners) |
| A run that failed twice | `ci-fix-agent.yml` | An agent fixes it, on the pull request's branch or in a new pull request; after three attempts, `needs-human` and an issue |
| A major update merged | `upgrade-agent.yml` | An agent adopts what the new version recommends, in a pull request that merges by itself |
| A new issue, the `claude` label, or `@claude` in a comment | `issue-agent.yml` | Claude works on it; the bot opens the pull request, which closes the issue and merges by itself once CI is green |
| The `copilot` label, `@copilot`, or when Claude can't | `issue-agent.yml` | The Copilot CLI does the same |
| Monday 08:30 UTC | `retry.yml` | Issues the agents ran out of credit for (`retry next week`) go back to the issue agent |
| An agent's own failure | `health.yml` | Recorded in the pinned "Automation health" issue, with its cause |
| Monthly (2nd), and when settings change | `self-settings.yml` | Every app's repository settings, labels, `release` environment and rules, as `settings/` says |

## The labels

| Label | Means |
|---|---|
| `needs-human` | The automation stopped: you take it from here. Add it to an issue to keep the agents off it. |
| `automerge` | Merges by itself once CI is green. |
| `agent` | Opened by an AI agent. |
| `claude` / `copilot` | Hands an issue (or a failing pull request) to that agent. |
| `ios` | Builds and tests the pull request for iOS too (macOS minutes cost ten times Linux ones on a private repository). |
| `major-update` | A major dependency update: the upgrade agent follows up after it merges. |
| `autofix-1` … `autofix-3` | The CI-fix agent's attempts on a pull request. |
| `retry next week` | The agents ran out of credit for this issue; they try again on Monday. |
| `machinery-review` | The monthly refresh, which the agent reviews. |

## What the agents can't do

They never change workflow files (the bot's token for their commits has no Workflows permission), never touch what an
app's AGENTS.md reserves for you, and never merge anything that isn't green.
