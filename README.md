# app-machinery

Everything that builds, checks, releases and looks after Kotlin Multiplatform apps (Android and iOS), shared by every
app so that each one keeps almost nothing of its own:

- **Workflows** that the apps call: CI, the iOS build, releases to Google Play with translated "What's new", the store
  listing's graphics, auto-merge, and AI agents (Claude Code, with the Copilot CLI as a fallback) that take issues, fix
  failing builds, adopt major updates and review a monthly refresh.
- **Gradle plugins** that the apps build with: Kotlin Multiplatform, Compose Multiplatform, the Android app, static
  analysis (detekt, ktlint, copy-paste check), licence checks, translation checks, and the iOS project's settings.
- **Repository settings as code**: merge options, labels, the `release` environment and the default branch's rules,
  applied to every app.
- **Docs that say every click** of what can't be automated.

The machinery maintains itself: Dependabot and a monthly refresh keep its versions current, its own CI checks every
change, and each release reaches every app as one pull request from Dependabot.

## Where to start

| You want to | Read |
|---|---|
| Set up your accounts, once (GitHub, Google, Apple, Claude, Copilot) | [docs/01-once-per-account.md](docs/01-once-per-account.md) |
| Start a new app | [docs/02-new-app.md](docs/02-new-app.md) |
| Know what happens by itself | [docs/03-what-runs-by-itself.md](docs/03-what-runs-by-itself.md) |
| Know what to renew, and when | [docs/04-renewals.md](docs/04-renewals.md) |
| Fix something that broke | [docs/05-troubleshooting.md](docs/05-troubleshooting.md) |
| Understand or change the machinery | [docs/06-how-it-works.md](docs/06-how-it-works.md) |

New apps start from [app-template](../../../app-template), which already calls everything here.

## Licence

MIT: see [LICENSE](LICENSE).
