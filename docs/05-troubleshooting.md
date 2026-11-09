# 5. Troubleshooting

Most failures fix themselves: a failed run is re-run once, then an agent tries to fix it, then you get an issue. These
are the ones that land on you, with what to do.

| You see | Why | Do |
|---|---|---|
| Every agent run fails within seconds, "refused" or "model not available" | The Claude plan changed and `--model best` picks a model the plan doesn't have | In `.github/actions/claude/action.yml`, set `--model opus`, release the machinery. |
| Agents fail with "usage limit", issues get `retry next week` | The Claude (or Copilot) quota is used up | Nothing: they retry on Monday. To retry now, take the label off. |
| A used-up quota is listed in Automation health as a failure, and the issue gets no `retry next week` | Claude or Copilot words its limit in a new way | Add the wording to `.github/scripts/out-of-credit.sh` and to its test, `test-scripts.sh`, and release the machinery. Meanwhile, add the label by hand. |
| An agent's comment says it ran out of time | It worked for an hour without finishing, or hung | Ask for less at a time, or call it again (`@claude ...`, or its label). If Copilot keeps hanging, bump its CLI: `cli-version` in `.github/actions/copilot/action.yml`. |
| Claude's answer says it couldn't run a command or write a file, and its run warns "Claude was refused tools" | Claude Code kept Claude from them: its commands run isolated, which needs `bubblewrap` and `socat` on the runner ("Sandbox dependencies not available"), or a new Claude Code asks for a permission the machinery doesn't give | The warning names the tools, the answer says why. In `.github/actions/claude/action.yml`: the step that installs the two, and `--allowedTools`. Release the machinery, then call Claude again. |
| "Not the agents' to change" in an agent's run, whose commits weren't pushed | Its commits changed the agents' own settings (`.claude/settings.json`, `.mcp.json`, `.github/copilot/`, `.github/hooks/`) | Make that change yourself, if you want it. |
| "The branch moved" in an agent's publish job, whose commits weren't pushed | The branch got commits, yours or another request's, while the agent worked on it | Call the agent again (`@claude ...`, or its label): it starts from the branch as it is now. |
| "Bad credentials" or 401 in an agent or the bot's steps | A token expired or was revoked | docs/04-renewals.md. |
| "Not a maintainer: @someone can't call the agents" in Automation health | Someone without write access (a read-only collaborator or organization member) called an agent; it didn't run | Nothing. If they should, give them write access; or call the agent yourself. |
| The agent missed what someone wrote; its thread says "Left out: not a maintainer's" | Others' posts reach the agents only as they stood when a maintainer last called one, never posts or edits from after | Read the posts, then call the agent again (`@claude ...`, or remove and add its label). |
| "Resource not accessible by integration" in the bot's steps | The bot lacks a permission (after a machinery change that needs a new one) | The App's settings → Permissions → add it; then accept it on the installation (**`<owner>` Settings → GitHub Apps → Configure → Review request**). |
| A release went to neither Google Play nor TestFlight | `PLAY_TRACK` (or `APPLE_TEAM_ID`) isn't set on the app | Set it (docs/02-new-app.md, steps 8 and 10a): the next release uploads, and its notes include this one's. |
| Google Play upload fails with 403 | The service account lacks a permission on the app | Play Console → Users and permissions → `publisher@…` → App permissions (docs/02-new-app.md step 8). |
| Google Play upload fails with "Package not found" | The app isn't on Play yet, or `APP_ID` is wrong | Do the first upload by hand (docs/02-new-app.md step 7); check the `APP_ID` variable. |
| Store listing fails with 403 | No **Manage store presence** for the service account | As above. |
| "The job was not acquired by Runner" on the iOS job | GitHub's macOS runners were busy | Re-run the job; it isn't a code problem. |
| The iOS UI test can't find the home screen | The app crashed or was too slow to draw | The run's `ios-screenshots` artifact holds a screen recording (`.mp4`) and the crash report (`.ips`). |
| "Upload to TestFlight" fails with "No profiles for ..." or a bundle identifier error | The app's id isn't registered with Apple, or the API key hasn't Admin access | docs/02-new-app.md step 10a; the key needs **Admin**. Then **Re-run failed jobs**. |
| The upload fails with "No suitable application records were found" | The app has no App Store Connect record yet | Make it (docs/02-new-app.md step 10a), then **Re-run failed jobs**. |
| A commit is refused by CI: "header must not be longer than 100 characters" | A commit subject over 100 characters (the local hook doesn't count them), or a pull request title that the " (#123)" of squash-merging took past 100 | Before merging: shorten it (a branch can't be force-pushed by the agents: open a new pull request from a rebuilt branch). After: nothing to do, since the next push checks only its own commits. |
| A release made nothing | Only `build`, `ci`, `docs`, `refactor`, `test` or `chore` commits since the last one | Expected: `feat`, `fix`, `perf` and `revert` release. |
| "What's new" stayed in English | No Claude token for the release, or the translation failed | The run's warnings say why. The next release translates again. |
| Settings drifted (a rule changed by hand) | — | **app-machinery → Actions → Self automation → Run workflow → settings** puts `settings/` back. |

When nothing here matches, open an issue in the app: the issue agent reads the logs and either fixes it or says what
you must do.
