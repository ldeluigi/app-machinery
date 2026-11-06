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
| After a release | CI → Upload to Google Play, Google Play listing graphics, Upload to TestFlight | The bundle goes to Play with "What's new" and the in-app update priority (major 5, feature 3, fix 2, maintenance only, like dependency bumps, 1); the listing's icon, feature graphic and screenshots that changed are replaced; the iOS app goes to TestFlight, for its testers |
| Every pull request, and its renames | Automation → Pull request title | The title is a Conventional Commit of 100 characters at most, counting the " (#123)" that squash-merging adds (it becomes the commit on `main`) |
| Dependabot's and the bot's pull requests | Automation → automerge | GitHub merges them once CI is green; build-only updates merge as `build(deps)`, which releases nothing |
| The `ios` label on a pull request | Automation → Build for iOS too | Starts CI on the pull request's branch, with its iOS build |
| A failed CI run | Automation → Re-run failed jobs once | Network hiccups and lost runners fix themselves |
| A run that failed twice | Automation → CI-fix agent | An agent fixes it, on the pull request's branch or in a new pull request; after three attempts, `needs-human` and an issue |
| A new issue, the `claude` label, or `@claude` in a comment, from a maintainer | Automation → Claude | Claude works on it; the bot opens the pull request, which closes the issue and merges by itself once CI is green |
| The `copilot` label, `@copilot`, or when Claude can't | Automation → Copilot | The Copilot CLI does the same |
| A major update merged | Automation → upgrade | An agent adopts what the new version recommends, in a pull request that merges by itself |
| Monday 08:30 UTC | Automation → retry-issues | Issues the agents ran out of credit for (`retry next week`) go back to the issue agent |
| Monthly (1st) | Automation → refresh, review | JDK bump, the machinery's shared agent skills, and a health report (deprecations, credentials about to expire, stuck pull requests) in a pull request; an agent fixes what code can fix, checks Google Play's and Apple's latest policies against the app, and opens one issue for what only you can do |
| Any of the above failing | Automation → Automation health | Recorded in the pinned "Automation health" issue, with its cause |
| Weekly (Monday) | Dependabot | Updates of the app's libraries, and of the machinery (workflows and plugins) when it released |
| Monthly (2nd), and when settings change | app-machinery's Self automation → settings | Every app's repository settings, labels, `release` environment and rules, as `settings/` says |

"Run workflow" on CI builds a branch with iOS, or with "store-listing" only replaces the listing's graphics; on
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
| `retry next week` | The agents ran out of credit for this issue; they try again on Monday. |
| `machinery-review` | The monthly refresh, which the agent reviews. |

## What the agents can't do

They never change workflow files (the bot's token for their commits has no Workflows permission), never touch what an
app's AGENTS.md reserves for you, and never merge anything that isn't green.

They only take orders from maintainers (people with write access) and read only what can't trick them. An agent reads
what maintainers and the bot wrote on an issue or pull request. What anyone else wrote, it reads only as it stood when
a maintainer last called an agent there (`@claude`, `@copilot`, or adding their label): read the thread before you call
one. Even then, it never sees the text the page doesn't show you, like HTML comments, invisible characters or image
descriptions. Each left-out post keeps a line saying who wrote it and why it was left out.
