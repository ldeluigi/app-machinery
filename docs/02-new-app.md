# 2. A new app

Do these for each app, in order: later steps need earlier ones. Your accounts must be set up first
([01-once-per-account.md](01-once-per-account.md)).

Words used below: **`<owner>`** is the GitHub account of the apps (yours, or an organization), **`<repo>`** the app's repository name, **`<app.id>`**
the app's id (step 1).

## 1. Choose the name and the id

- [ ] **The name** players see. It can change later, but check first that no app or trademark uses it: search Google
      Play, the App Store and the trademark registers (<https://www.tmdn.org/tmview/>, <https://tmsearch.uspto.gov/>,
      classes 9, 28 and 41 for games). Register its `.app` and `.com` domains the same day if you want them.
- [ ] **The id** (Android application id, iOS bundle id): reverse-domain, lower case, like `com.example.myapp`. It
      **can never change** once the app is on a store, so name what the app does rather than its brand, and don't use
      your own name. Google Play and Apple both refuse an id another developer already has.

## 2. Create the repository

```sh
gh repo create <owner>/<repo> --template <owner>/app-template --private --clone
cd <repo>
```

Then follow the template's README, "Make it yours": it sets the name and id in `gradle.properties`, renames the code's
package and draws the icon. Commit and push to `main`.

## 3. Settings and secrets

- [ ] **Settings**: the bot is installed on all of `<owner>`'s repositories, so its monthly run covers the new one,
      whose workflows call the machinery's as the template's do. To apply them now: in app-machinery, **Actions → Self
      automation → Run workflow → settings**. Check: the repository's **Settings → Rules → Rulesets** lists "Default".
- [ ] **Secrets**: in a copy of app-machinery, `.github/scripts/set-secrets.sh <owner>/<repo>`, as in
      docs/01-once-per-account.md step 6. Paste the account's values (the bot, the agents, Google Play's two), and
      press Enter to skip the rest: the next steps ask for them. The app's name and id need no variable: the releases
      read them from `app.name` and `app.id` in its `gradle.properties`, as the build does.
      Check: the repository's **Settings → Secrets and variables → Actions** lists them under Secrets and Variables,
      and the Dependabot tab lists the agents' and the bot's.

## 4. The upload key (Android signing)

Google signs the app for its users (Play App Signing). The upload key only proves to Google that an upload comes from
you; if it is ever lost, Google can reset it.

- [ ] Make it **once**, outside the repository (`keytool` comes with the JDK):

  ```sh
  keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 36500
  ```

  It asks for a password, used for the key too. Keep `upload.jks` and the password in your password manager.
- [ ] Store it: `.github/scripts/set-secrets.sh <owner>/<repo> UPLOAD_KEYSTORE_BASE64 UPLOAD_KEYSTORE_PASSWORD
      UPLOAD_KEY_ALIAS UPLOAD_KEY_PASSWORD`. Drag `upload.jks` onto the window for the first, then paste the password,
      `upload`, and the password again.

## 5. The first release (GitHub)

- [ ] Merge a `feat:` or `fix:` pull request (or push one to `main`). CI runs, then the release: it builds the signed
      bundle and makes the GitHub Release `v1.0.0` with its changelog, the `.aab`, the `.apk` and `mapping.txt`.
- [ ] "Upload to Google Play" is skipped until step 8: expected.
- If the release ran before step 4, it failed while signing and made nothing: **Actions → CI →** that run **→ Re-run
  failed jobs**.

### 5a. The support address (first app only)

Google Play and the App Store show a developer email publicly, and the privacy policy needs a contact. A dedicated Gmail
address keeps yours off the stores and forwards everything to you. One address can serve every app.

- [ ] <https://accounts.google.com/signup>: name e.g. "`<App>` Support", **create your own Gmail address**,
      brand-neutral (e.g. `myapp.support@gmail.com`), a strong password in your password manager, your own email
      and phone for recovery. Personalisation: **Manual**, everything off.
- [ ] **2-Step Verification** at <https://myaccount.google.com/security>, backup codes in your password manager.
- [ ] Forward everything: web Gmail → ⚙ → **See all settings → Forwarding and POP/IMAP → Add a forwarding address** →
      yours → confirm with the link Google sends you → **Forward a copy … and mark Gmail's copy as read** → Save.
- [ ] Keep Google's mail out of spam (spam isn't forwarded): search bar filter icon → From `google.com` → **Create
      filter → Never send it to Spam**.
- [ ] Sign in to it once a year: Google deletes accounts unused for two years.

### 5b. A public page for the privacy policy

The repository is private, so its `docs/privacy-policy.md` isn't public.

- [ ] Signed in as the support account: <https://sites.google.com> → **Blank site**, title "`<App>` privacy policy",
      paste `docs/privacy-policy.md` (contact filled in) → **Publish** → a neutral address → check it in a private
      window. Keep it in step with the file.

## 6. Google Play Console: create the app

In <https://play.google.com/console>:

- [ ] **Create app**: the name, default language English (United States), App or Game, Free. Accept the declarations.
- [ ] **Policy → App content**, every section: privacy policy (the page of 5b); ads (none, unless the app has them);
      app access (explain anything a reviewer needs, like another app that must be installed); content rating
      questionnaire; target audience (13+ keeps an app out of the Families programme); data safety (what the privacy
      policy says); news, government, financial and health features (no).
- [ ] **Grow → Store presence → Main store listing**: short and full description, app icon (512×512), feature graphic
      (1024×500), at least two phone screenshots. The app's `StoreArtTest` draws them into `composeApp/build/store-art`:
      `./gradlew :composeApp:testAndroidHostTest --tests "*StoreArtTest*"`. Upload them by hand this once; every release
      then replaces the ones that changed. Category.
- [ ] **Store settings → Store listing contact details**: the support address, the privacy page as website.
- [ ] **Testing → Internal testing → Testers**: an email list (you and your friends), save the opt-in link.
- [ ] Optional: **Store listings → the listing → Manage translations**: add the app's languages. "What's new" reaches
      the listing's languages only (Claude translates it); the others read it in English.

## 7. The first upload, by hand

Google's API can't create an app, so the first bundle goes up by hand.

- [ ] Download the `.aab` from the latest GitHub Release.
- [ ] **Testing → Internal testing → Create new release** → upload it → accept **Play App Signing** (a key Google makes)
      → notes → **Save → Review release → Start rollout**.
- [ ] Open the opt-in link on your phone and install the app from Play.

## 8. Uploads from GitHub

- [ ] Let this repository's workflows act as the publisher service account (Cloud Shell; the ids are in your secrets
      file and `docs/01-once-per-account.md` step 7):

  ```sh
  gcloud iam service-accounts add-iam-policy-binding publisher@<project-id>.iam.gserviceaccount.com \
    --project <project-id> --role=roles/iam.workloadIdentityUser \
    --member="principalSet://iam.googleapis.com/projects/<project-number>/locations/global/workloadIdentityPools/github/attribute.repository/<owner>/<repo>"
  ```

- [ ] **Play Console → Users and permissions →** `publisher@…` **→ App permissions → Add app** → this app, with
      **Release to production, exclude devices, and use Play App Signing**, **Release apps to testing tracks** and
      **Manage store presence**. Apply.
- [ ] Turn the uploads on: `.github/scripts/set-secrets.sh <owner>/<repo> PLAY_TRACK`, and answer `internal`. Every
      release after this goes to Play by itself, with "What's new" and the update priority.
- [ ] Replace the listing's graphics with the app's own once: **Actions → CI → Run workflow**, on `main`, with
      **store-listing** ticked.

## 9. Launch to production

- [ ] Check the **pre-launch report** (Play runs it on the internal track) and the store listing.
- [ ] A personal developer account made after November 2023 must first run a **closed test** with 12 testers for
      14 days: **Testing → Closed testing**. Older accounts and organizations skip this.
- [ ] **Production → Countries/regions**.
- [ ] `.github/scripts/set-secrets.sh <owner>/<repo> PLAY_TRACK`, and answer `production`. The next `feat` or `fix` merge goes to everyone, after Google's review.

## 10. iOS

Each pull request labelled `ios`, and every push to `main` that touches code, builds the iOS app on GitHub's Macs and
runs its UI tests on a simulator. Its screenshots, screen recording and any crash report are the run's
`ios-screenshots` artifact.

### 10a. TestFlight

Once these are done, every release also goes to TestFlight, Apple's beta testing app, by itself. You need Apple's steps
of `docs/01-once-per-account.md` (step 8) first: the Developer Program, the Team ID and the API key.

- [ ] **Register the app's id**: <https://developer.apple.com/account/resources/identifiers/list> → **+** → **App IDs**
      → Continue → **App** → Continue. Description: the app's name; **Bundle ID: Explicit**, `<app.id>`. Register.
- [ ] **Make its App Store Connect record**: <https://appstoreconnect.apple.com/apps> → **+** → **New App**. Platform
      **iOS**, the name players see (it must be free on the App Store), its primary language, the bundle id you just
      registered, and any SKU (the app id will do). User access: **Full Access**. Create.
- [ ] **Store the keys**, the Team ID last, since it turns the uploads on:
      `.github/scripts/set-secrets.sh <owner>/<repo> ASC_KEY_ID ASC_ISSUER_ID ASC_PRIVATE_KEY APPLE_TEAM_ID`
      (drag the `.p8` file onto the window for `ASC_PRIVATE_KEY`).
- [ ] Merge a `feat:` or `fix:` pull request. After the release, **Upload to TestFlight** archives the app, signs it
      through Apple's cloud signing (no certificates to keep) and uploads it. App Store Connect takes 10 to 30
      minutes to process it, then emails you.
- [ ] **Testers**: in App Store Connect → the app → **TestFlight**.
  - People in your team (up to 100): the App Store listing job of 10b makes an internal group with access to every
    build and invites the team members of `testflight.internalTesterRoles`. To add others by hand: **Internal Testing
    → +**, a group, add them. Someone outside the team first needs an App Store Connect account: **Users and Access →
    +**, their Apple Account email, role **Marketing** (it sees the least), and they accept the email. Internal builds
    need no review.
  - Anyone else (up to 10,000): **External Testing → +**, a group, then **Public Link**. Each version's first build
    goes through Apple's beta review, usually within a day.
  - Testers install **TestFlight** from the App Store on their iPhone and open the invitation.

### 10b. The App Store listing

The app keeps its App Store listing in its repository, as it keeps its code: `store/app-store/` (the
`app-store-listing` input of the app's `ci.yml` caller names another folder). After every TestFlight upload, and in a
run started by hand with **store-listing** ticked (**Actions → CI → Run workflow**, on `main`), the **App Store
listing** job brings the app's App Store Connect record in line with it, with the same API key as the upload. It
changes only what differs, and never submits anything for review.

- `app-store.properties`: what isn't written in a language.
  - `primaryCategory`, `primarySubcategoryOne`, `primarySubcategoryTwo`, `secondaryCategory`: Apple's category ids
    (`GAMES`, `GAMES_TRIVIA`, `ENTERTAINMENT`...).
  - `contentRightsDeclaration`: `USES_THIRD_PARTY_CONTENT` (it shows, plays or links to others' content, and you have
    the rights) or `DOES_NOT_USE_THIRD_PARTY_CONTENT`.
  - `supportUrl`, `privacyPolicyUrl`, `marketingUrl` (optional): the same in every language.
  - `price` in `price.territory` (`USA` by default; `0` is free): every other country gets the same tier.
  - `availability.newTerritories` (`true` or `false`) and `availability.exclude`: the countries the app is sold in,
    all but the excluded ones (Apple's three-letter codes, e.g. `CHN`, where games need a licence).
  - `ageRating.<question>`: the age rating's answers, by the API's names (`profanityOrCrudeHumor=INFREQUENT`,
    `gambling=false`...). Apple adds questions from time to time: the job's report shows the rating Apple calculated.
  - `releaseType`: `MANUAL` (you release an approved version yourself) or `AFTER_APPROVAL`.
  - `review.<field>`: App Review's details other than your own name and phone, e.g. `review.contactEmail` and
    `review.demoAccountRequired=false`.
  - `testflight.feedbackEmail`, `testflight.internalGroup` (its name) and `testflight.internalTesterRoles` (e.g.
    `ACCOUNT_HOLDER,ADMIN`).
- `review-notes.txt`: the notes for App Review, in English: how to try the app, what it needs.
- One folder per App Store locale (`en-US`, `de-DE`, `ja`, `zh-Hans`...: Apple's "locale shortcodes"), with
  `name.txt` (30 characters), `subtitle.txt` (30), `description.txt` (4,000), `keywords.txt` (100, comma-separated),
  `promotional_text.txt` (170) and, for TestFlight, `beta_description.txt`. `{app.name}` stands for `app.name` of
  `gradle.properties`. A text over Apple's limit stops the job before it changes anything.
- The screenshots: StoreArtTest draws them into `composeApp/build/store-art/app-store/<display type>/`, e.g.
  `APP_IPHONE_67` (6.9-inch iPhone, 1320 × 2868) and `APP_IPAD_PRO_3GEN_129` (13-inch iPad, 2064 × 2752, needed when
  the app runs on iPads). They go to the primary language, which the other languages show too; a changed picture
  replaces its whole set.

The version being prepared gets the release's build once App Store Connect has processed it (the job waits up to
`app-store-build-wait` minutes, 30 by default, then takes the newest processed build), with the build's version
number. A version in review or on sale can't change: the job then only reports how it stands, and the texts wait for
the next version.

The job's summary (the run's page) lists what it filled in, how the record stands (the age rating, the version, the
build, the countries where the app can't be sold yet and why) and what's left for you.

### 10c. The first submission

What the API can't do, once, in App Store Connect → the app:

- [ ] **App Privacy** (sidebar): **Get Started** → "Do you or your third-party partners collect data from this app?"
      as the privacy policy says (an app that collects nothing: **No**) → Save → **Publish**. The API can neither
      answer nor read this, so the job's report always reminds you of it.
- [ ] The version page (sidebar, **iOS App** → the version) → **App Review Information**: your first name, last name
      and phone number (the job fills in the email and the notes). They stay out of the repository.
- [ ] Same page → **Copyright**: the year and the rights holder, e.g. `2026 Jane Doe`, unless `copyright` is set.
- [ ] To sell in the European Union, declare whether you're a **trader** under the Digital Services Act: App Store
      Connect → **Business** → **Agreements** → Compliance → Digital Services Act → **Complete Compliance
      Requirements**. A trader's address, phone and
      email show on the EU storefronts; an individual whose app earns nothing usually isn't one. Until then, the
      job's report lists the EU countries as "TRADER_STATUS_NOT_PROVIDED".
- [ ] Check the job's report: nothing failed, the build is there, nothing else is left for you.
- [ ] **Add for Review** (top right of the version page) → **Submit to App Review**. Submitting states, on your
      behalf: the content rights answer, the age rating's answers, the App Privacy answers and the build's export
      compliance (`ITSAppUsesNonExemptEncryption` in the app's Info.plist) are true. Review takes a day or two; with
      `releaseType=MANUAL`, **Release This Version** once it is approved.

Later versions: each release's build goes to TestFlight; to ship one, **+** next to **iOS App** in the sidebar makes
a new version (App Store Connect copies the texts), the next App Store listing job fills it in, then 10c's last step.

## 11. The app's own extras

An app's own scheduled jobs (a data refresh, say) stay in its repository; store their secrets by name, like
`.github/scripts/set-secrets.sh <owner>/<repo> YOUTUBE_API_KEY`. Keep the app's maintenance scope in mind for the release notes: the app's `ci.yml` caller passes
`scope-notes`, e.g. `catalog: Refreshed the song catalogue.`, so that its commits read as one friendly line.
