# 6. How it works

For changing the machinery, or understanding why it is built this way.

## Layout

```
.github/workflows/        the two reusable workflows the apps call: ci.yml (checks, Android and iOS builds, release,
                          Google Play) and automation.yml (auto-merge, retries, the AI agents, the monthly refresh,
                          the health report); and the machinery's own, self-ci.yml and self-automation.yml
.github/actions/          setup-build, claude, copilot, agent (Claude, then Copilot), thread (what an agent may read
                          of an issue or pull request), bundle-commits and publish-commits (an agent's commits, from
                          its job to the one that pushes them)
.github/scripts/          the scripts the workflows and you run: forbidden names, issue references, health report,
                          settings, secrets, take-agents-off, out-of-credit, check-agent-commits, wait-for-publish,
                          and their tests (test-scripts)
plugins/                  the Gradle convention plugins (machinery.*), published on the machinery's GitHub Pages
tools/release             the stores' "What's new" (translated by Claude), Google Play's listing graphics and the
                          App Store listing (bin/app-store, through App Store Connect's API)
tools/bump                the versions Dependabot can't see (JDK, Android SDK, Copilot CLI, Claude Code, actionlint)
tools/thread              the only GitHub text the agents read: an issue's or pull request's conversation, as far
                          as maintainers wrote or saw it, without what the page doesn't show
gradle/libs.versions.toml every version the plugins give the apps
settings/                 repository settings, labels and rulesets as code
release/releaserc.yml     semantic-release, as the apps' releases run it
.claude/skills/           the agents' skills; commit-messages (how to write commits and pull request titles that
                          pass CI) is shared: the monthly refresh copies it into every app
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
- **Self automation** bumps what Dependabot can't see, monthly, and applies `settings/` to every app: the repositories
  the bot is installed on whose workflows call the machinery's. The others, on an account that holds more than apps,
  keep their settings.
- **Self CI** checks every change: actionlint (with shellcheck), pinned actions, Conventional Commits, the plugins' and
  tools' builds, tests and gradle-kotlin-qa's checks, and the template's build with the change's plugins. Then it
  releases (below).
- Its own automation merges Dependabot's and the bot's pull requests once green, re-runs failures, and gives the rest
  to the CI-fix agent, as for an app.
- **Self CI**, on `main`, tags each `feat` or `fix` and publishes the plugins of that version. Dependabot then offers it to
  every app, whose own CI checks it before it merges.

## Choices

- **Reusable workflows rather than a copied template**: a fix lands in every app through one Dependabot pull request.
- **Claude first, Copilot as the fallback, both as CLIs in Actions**: GitHub's Copilot cloud agent opens draft pull
  requests that wait for approvals, and GitHub Agentic Workflows can't run Claude on a subscription.
- **Plugins on GitHub Pages rather than Maven Central**: no Sonatype account, no signing keys; Gradle and Dependabot
  read any Maven repository.
- **The bot rather than the workflow token**: GitHub starts no workflow from the workflow token's pushes and merges.
- **Agents never change workflows**: the bot's token for their commits has no Workflows permission, so an agent can't
  widen its own powers. For the same reason `publish-commits` refuses commits that change the agents' own settings
  (`.claude/settings.json`, `.mcp.json`, Copilot's `.github/copilot/` and `.github/hooks/`), which can allow any
  command.
- **The bot's key never shares a runner with an agent**: an agent's commands run the code of the app and of
  everything it depends on, as the user the runner's own steps run as, so what a job holds, they can reach. Each agent
  therefore works in a job that holds its model's credential and the workflow token, which there only reads the
  repository (and its runs): it can't push, post or read an issue. A start job reads the conversation for it. A
  publish job, on a clean runner with its own checkout of the machinery, takes the agent's commits as a git bundle,
  fetches them without checking them out, works out by itself what they add to the branch, refuses those that change
  the agents' settings, and pushes as the bot. What the agent's job says of itself (its answer, whether it finished)
  is posted, never obeyed. This rests on GitHub giving a job's runner only the secrets that job uses; and whatever
  the runner gets, the bot's token is no longer used in a workspace an agent touched. The model's credential stays
  with the agent: it can spend the plan's quota, not touch the repository.
- **The agents only read the Gradle cache**: an issue or a comment starts their jobs on the default branch's ref,
  where a job may write the cache that the releases' builds read.
- **A second request waits for the first one's commits**: jobs queue, not runs, so a request on the same branch could
  start while the first one's publish job is still on its way. `wait-for-publish` holds the agent until then, and the
  branch is read again.
- **The build gets no credentials**: an agent runs `./gradlew`, which runs the code of the app and of everything it
  depends on. Claude Code's subprocess scrub (`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`) keeps Claude's and the other
  credentials out of the environment of the commands Claude runs, but for the read-only GitHub token that `gh` needs.
  It also runs them among processes of their own, where they can't read the runner's: Claude Code needs `bubblewrap`
  and `socat` for that, which the Claude action installs, and refuses every command without them. There the
  checkout's git hooks are read-only, so the Claude action lets the build write its hooks before Claude starts.
  The Copilot CLI has no such switch: its commands see its token, which can only ask Copilot.
- **Only maintainers steer the agents**: anyone can write on a public repository's issues, and text an agent reads can
  carry instructions (prompt injection). So an agent's prompt holds all it reads of GitHub, from `tools/thread`, and
  no agent has a command that reads issues, pull requests or comments, nor a token that writes (the workflow pushes
  and posts for it):
  - only maintainers (write, maintain or admin access, asked of GitHub: an issue's "collaborator" or "member"
    association counts read-only people too) and the bot App can start an agent;
  - the agent reads all that maintainers and the bot wrote; what others wrote, only as it stood when a maintainer last
    called an agent there (`@claude`, `@copilot`, or adding their label by hand), since the maintainer could read it
    then: a post or an edit from even a second later is left out, and so is a title changed since. The bot's own
    labels (Copilot taking over, the Monday retry) aren't a maintainer's call;
  - the text the page doesn't show is left out of all of it, so that nothing can hide from the maintainer who calls the
    agent: HTML and Markdown comments (`[//]: # (...)`), tags and their attributes (images' descriptions, tooltips),
    `hidden` elements, invisible characters (zero-width, direction marks, Unicode tags, variation selectors), images,
    math that hides or recolours text, Mermaid diagrams, code blocks' info strings, others' link targets, posts the page
    hides. The Markdown is parsed as CommonMark, as GitHub parses it, so that a trick can't look like code to the tool
    and like a comment to the page;
  - each post is quoted under a heading saying who wrote it, which a post can't fake.

  - the `gh` commands that read issues, pull requests and comments are denied to the agents (`--disallowedTools`,
    `--deny-tool`), and a denial wins over what an app's own `.claude/settings.json` allows its owner's sessions.

  Web pages, release notes and logs, which the CI-fix, upgrade and review agents need, stay untrusted data that their
  prompts say never to obey.
- **Each agent has its own time limit**, shorter than its job's: a job that reaches its limit is cancelled and says
  nothing, while an agent stopped at its own still gets its error posted and its issue handed on. Copilot's is
  enforced by its action (`timeout`), Claude's by the step of its job, since a composite action's steps take none.
  A job that ends without a word anyway still gets its answer from its publish job.
- **Copilot answers in a file**: its output holds every message of its run and its sub-agents' replies, so its action
  asks it to write its answer to `.copilot-answer.md`, kept out of its commits, and posts that; without the file, its
  output without the sub-agents' replies.
