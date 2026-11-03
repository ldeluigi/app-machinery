# 5. Troubleshooting

Most failures fix themselves: a failed run is re-run once, then an agent tries to fix it, then you get an issue. These
are the ones that land on you, with what to do.

| You see | Why | Do |
|---|---|---|
| Every agent run fails within seconds, "refused" or "model not available" | The Claude plan changed and `--model best` picks a model the plan doesn't have | In `.github/actions/claude/action.yml`, set `--model opus`, release the machinery. |
| Agents fail with "usage limit", issues get `retry next week` | The Claude (or Copilot) quota is used up | Nothing: they retry on Monday. To retry now, take the label off. |
| "Bad credentials" or 401 in an agent or the bot's steps | A token expired or was revoked | docs/04-renewals.md. |
| "Resource not accessible by integration" in the bot's steps | The bot lacks a permission (after a machinery change that needs a new one) | The App's settings → Permissions → add it; then accept it on the installation (**`<owner>` Settings → GitHub Apps → Configure → Review request**). |
| Google Play upload fails with 403 | The service account lacks a permission on the app | Play Console → Users and permissions → `publisher@…` → App permissions (docs/02-new-app.md step 8). |
| Google Play upload fails with "Package not found" | The app isn't on Play yet, or `APP_ID` is wrong | Do the first upload by hand (docs/02-new-app.md step 7); check the `APP_ID` variable. |
| Store listing fails with 403 | No **Manage store presence** for the service account | As above. |
| "The job was not acquired by Runner" on the iOS job | GitHub's macOS runners were busy | Re-run the job; it isn't a code problem. |
| The iOS UI test can't find the home screen | The app crashed or was too slow to draw | The run's `ios-screenshots` artifact holds a screen recording (`.mp4`) and the crash report (`.ips`). |
| A commit is refused by CI: "header must not be longer than 100 characters" | A commit subject over 100 characters (the local hook doesn't count them) | Shorten it. A branch can't be force-pushed by the agents: open a new pull request from a rebuilt branch. |
| A release made nothing | Only `build`, `ci`, `docs`, `refactor`, `test` or `chore` commits since the last one | Expected: `feat`, `fix`, `perf` and `revert` release. |
| "What's new" stayed in English | No Claude token for the release, or the translation failed | The run's warnings say why. The next release translates again. |
| Settings drifted (a rule changed by hand) | — | **app-machinery → Actions → Self settings → Run workflow** puts `settings/` back. |

When nothing here matches, open an issue in the app: the issue agent reads the logs and either fixes it or says what
you must do.
