# 3. What runs by itself

Once an app is set up, this is what happens without you. You only merge your own pull requests (or let an agent do the
work), answer the issues labelled `needs-human`, and do Google's and Apple's reviews' homework.

Each app has two workflows, each calling its namesake in app-machinery: **CI** (`ci.yml`) for everything that builds
and ships the app, **Automation** (`automation.yml`) for everything that looks after it.

| When | Workflow → job | What happens |
|---|---|---|
| Every pull request and push | CI → Conventional commits, Repository rules, Build and check | Commit messages are Conventional Commits; no forbidden name; no issue number in a commit; actions pinned to commits; `./gradlew check` (tests, detekt, ktlint, copy-paste check, Android Lint, licences, translations) and a debug build |
| Pushes to `main`, Dependabot's Gradle updates, refreshes | CI → Emulator smoke test | The instrumented tests on an Android emulator |
| Pushes to `main` touching code, pull requests labelled `ios`, runs by hand | CI → Build and test for iOS | The Kotlin tests on an iOS simulator, the Xcode build, the UI tests, screenshots |
| Pushes to `main`, once all of the above passed | CI → Version, build and GitHub Release | semantic-release decides the version from the commits; signed bundle; GitHub Release with the changelog; "What's new" from the last releases, translated by Claude into the app's languages |
| After a release | CI → Upload to Google Play, Google Play listing graphics, Upload to TestFlight, App Store listing | The bundle goes to Play with "What's new" and the in-app update priority (major 5, feature 3, fix 2, maintenance only, like dependency bumps, 1); the listing's icon, feature graphic and screenshots that changed are replaced; the iOS app goes to TestFlight, for its testers; the App Store record takes what changed in the app's `store/app-store` listing and screenshots, and the version being prepared gets the new build (never submitted: docs/02-new-app.md, step 10c) |
| Every pull request, and its renames | Automation → Pull request title | The title is a Conventional Commit of 100 characters at most, counting the " (#123)" that squash-merging adds (it becomes the commit on `main`) |
| Dependabot's and the bot's pull requests | Automation → automerge | GitHub merges them once CI is green; build-only updates merge as `build(deps)`, which releases nothing |
| The `ios` label on a pull request | Automation → Build for iOS too | Starts CI on the pull request's branch, with its iOS build |
| A failed CI run | Automation → Re-run failed jobs once | Network hiccups and lost runners fix themselves |
| A run that failed twice | Automation → Triage, Fix, Fix: publish | An agent fixes it, on the pull request's branch or in a new pull request; after three attempts, `needs-human` and an issue |
| A new issue, the `claude` label, or `@claude` in a comment, from a maintainer | Automation → Claude: start, Claude, Claude: publish | Claude works on it; the bot opens the pull request, which closes the issue and merges by itself once CI is green. A new issue with the `needs-human` or `retry next week` label, or with someone assigned, is left alone |
| The `copilot` label, `@copilot`, or when Claude can't | Automation → Copilot: start, Copilot, Copilot: publish | The Copilot CLI does the same |
| A major update merged | Automation → Adopt the new versions, and its publish job | An agent adopts what the new version recommends, in a pull request that merges by itself |
| Monday 08:30 UTC | Automation → retry-issues | Issues the agents ran out of credit for (`retry next week`) go back to the issue agent |
| A maintainer takes off an issue's `retry next week` label, or its last assignee | Automation → Retry | The issue goes to the agents, unless it still has the label, an assignee or `needs-human`, or a pull request that closes it is open. Removing the assignee hands over only an issue no agent was called on yet: one they already answered is called again with `@claude ...` or its label |
| Monthly (1st) | Automation → Refresh and check health, Review, Review: publish | JDK bump, the machinery's shared agent skills, and a health report (deprecations, credentials about to expire, stuck pull requests) in a pull request; an agent fixes what code can fix, checks Google Play's and Apple's latest policies against the app, and opens one issue for what only you can do |
| Any of the above failing | Automation → Automation health | Recorded in the pinned "Automation health" issue, with its cause |
| Weekly (Monday) | Dependabot | Updates of the app's libraries, and of the machinery (workflows and plugins) when it released |
| Monthly (2nd), and when settings change | app-machinery's Self automation → settings | Every app's repository settings, labels, `release` environment and rules, as `settings/` says. An app is a repository whose workflows call the machinery's: the bot's other repositories are left alone |

"Run workflow" on CI builds a branch with iOS, or with "store-listing" only brings the store listings up to date (Google Play's graphics, the App Store's listing); on
Automation it starts the refresh, or the retry of the issues, at once.

## The labels

| Label | Means |
|---|---|
| `needs-human` | The automation stopped: you take it from here. Add it to an issue to keep the agents off it. |
| `automerge` | Merges by itself once CI is green. |
| `agent` | Opened by an AI agent. |
| `claude` / `copilot` | Hands an issue (or a failing pull request) to that agent. Added by a maintainer, it lets the agent read what others wrote there so far. |
| `ios` | Builds and tests the pull request for iOS too (macOS minutes cost ten times Linux ones on a private repository). |
| `major-update` | A major dependency update: the upgrade agent follows up after it merges. |
| `autofix-1` … `autofix-3` | The CI-fix agent's attempts on a pull request. |
| `retry next week` | The agents ran out of credit for this issue; they try again on Monday, or as soon as you take the label off. Open an issue with it, or assigned to someone, to keep the agents off it until the label or the assignee goes. |
| `machinery-review` | The monthly refresh, which the agent reviews. |

## What the agents can't do

They never change workflow files (the bot's token for their commits has no Workflows permission) nor their own
settings (`.claude/settings.json`, `.mcp.json`, Copilot's `.github/copilot/` and `.github/hooks/`: such commits aren't
pushed), never touch what an app's AGENTS.md reserves for you, and never merge anything that isn't green. An agent
works on an issue for an hour at most: past that it is stopped, and says so.

They never hold the bot's key, nor a token that pushes or posts. An agent works in a job of its own, where its builds
run, with its model's credential and a token that only reads the repository. So an agent shows as up to three jobs in
a run: a start job reads the conversation and says the agent is on it; the agent's own job does the work; a publish
job, where nothing of the agent's runs, checks its commits, pushes them as the bot, opens the pull request and answers.

They only take orders from maintainers (people with write access) and read only what can't trick them. An agent reads
what maintainers and the bot wrote on an issue or pull request. What anyone else wrote, it reads only as it stood when
a maintainer last called an agent there (`@claude`, `@copilot`, or adding their label): read the thread before you call
one. Even then, it never sees the text the page doesn't show you, like HTML comments, invisible characters or image
descriptions. Each left-out post keeps a line saying who wrote it and why it was left out. The commands that read
issues and pull requests (`gh issue view`, `gh pr view`, `gh api`...) are denied to the agents, even where an app's own
`.claude/settings.json` allows them to you.
