# 4. Renewals

What expires, when, and what to do. Put each date in your calendar when you create the thing.

| What | Lasts | Who reminds you | What to do |
|---|---|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | 1 year | your calendar; the agents start failing ("Automation health" issue) | On your computer: `claude setup-token`, then store the new token in app-machinery and every app at once, pasting it once: `.github/scripts/set-secrets.sh <owner>/app-machinery,<owner>/<app>,... CLAUDE_CODE_OAUTH_TOKEN`. |
| `COPILOT_GITHUB_TOKEN` | its expiry date (up to a year) | an issue from the monthly refresh, 30 days ahead | Your Settings → Developer settings → Fine-grained tokens → the token → **Regenerate**, then store it as above, naming `COPILOT_GITHUB_TOKEN`. |
| The bot's private key | doesn't expire | — | If lost or leaked: the App's page → **Generate a private key** (delete the old one), then store it as above, naming `GH_APP_PRIVATE_KEY` (drag the `.pem` file). |
| Apple Developer Program | 1 year | Apple's emails | Renew at <https://developer.apple.com/account>, or the apps leave the App Store. |
| App Store Connect API key | doesn't expire | — | If leaked: revoke it, make a new one (docs/01-once-per-account.md step 8), then store it as above, naming `ASC_KEY_ID ASC_PRIVATE_KEY`. |
| The support Gmail account | deleted after 2 years unused | your calendar, yearly | Sign in to it once. |
| The upload key (Android) | 100 years | — | Keep `upload.jks` and its password backed up. If lost: Play Console → the app → **Test and release → App integrity → Request upload key reset**. |
| Google Play policies | change often | the monthly refresh's issues | Do what the issue titled "Owner: the monthly refresh's to-dos" lists, before its deadlines. |

After `set-secrets.sh`, the next run of each workflow uses the new values: nothing else to restart.
