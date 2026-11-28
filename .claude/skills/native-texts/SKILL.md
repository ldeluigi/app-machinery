---
name: native-texts
description: How to write and translate every text people read - the app's strings in every language, achievements, store listings, and the commit messages that become the stores' "What's new" - so each language sounds like a native speaker talking, and every store accepts it. Use before adding or changing any text, translation, achievement, store listing or release note, and to review one.
---

# Texts that sound native, in every language

The owner judges an app's texts as a native speaker would: a text that is correct but reads like a translation is
a bug. These rules come from what reviews of the apps found. Read them before you add or change a text in any
language, and check your change against the list at the end.

This skill is app-machinery's: its monthly refresh copies it into every app. Change it there, not in an app. An app's
`AGENTS.md` says its voice, who its texts speak to and its key terms; where it is more precise than this, it wins.

## What a good text is

- **What a native speaker would say out loud in that spot**, not what the English words map to. Restructure, drop
  words, use the idioms people use. Grammatical and faithful but never said that way is wrong.
- **Verbs, not nouns**: a status says what is happening as people say it (Italian "Carico le foto…", never
  "Caricamento foto…"); a button says what it does.
- **The register the app's users expect**: for a casual app, the informal "you" where the language has one, warm and
  short, never bureaucratic.
- **Short where the room is short**: buttons, chips, tabs, titles. Look up where a text is shown
  (`Res.string.<key>` in the code) to see its role and its room before you write it.
- **Who it speaks to**: a manual action and a button speak to the one person doing it. Where something may belong to a
  group (a team's answer, a shared result), word it so it fits one person or many: an impersonal phrase or a sentence
  about the thing, and no adjective or participle that agrees with one person's gender or number.
- **One idea, one word, in the whole app**: before writing a new text, read the language's file and reuse its words
  for the app's things. A new text that calls them something else is wrong even if its word is fine.
- **What users earn is *unlocked*** (Italian "sbloccato", the word games use), never *opened* (the word for a door),
  in every language and in commit messages too.
- **Names of achievements, levels and badges**: plain names people get at once beat clever ones; use the language's
  own wordplay only when it is as obvious as the English pun.
- **Screen-reader texts are spoken sentences** too, in the same voice.
- **Exact meaning**: nothing added that isn't true of the app, nothing that matters left out; numbers, units and
  symbols as the English has them. Keep a text that is already natural: the goal is the best text, not a different one.

## The files' rules

`./gradlew check` fails on most of these (`checkTranslations`, `checkStoreTexts`); write them right the first time.

- **Every text in every language, in the same change**: the build fails on a text missing from a language. Never copy
  the English into another language's file as a stand-in.
- **Placeholders** (`%1$s`, `%2$d`): each one exactly once, as in the English; they may move.
- **The app's name is a placeholder, never in a file**: it is a brand, so no language puts an ending on it or declines
  it. Write around it, inflecting the word for "app" or "game" next to it when the grammar needs one.
- **Plurals**: every form the language has (one, few, many, other...), by the Unicode rules, even where English has two.
- **As inside the XML**: `&amp;` for &, `\n` for a line break, the quotes and apostrophes the file already uses.
  Symbols (`…`, `·`, `★`) as the English has them.
- **Store limits**, in characters, as the stores count them: an achievement's name at most 100 and its description
  at most 120 (Game Center refuses longer, and a refused one stops the release's achievement step); App Store name and
  subtitle 30, keywords 100, promotional text 170, description 4000; Google Play title 30, short description 80, full
  description 4000. Count in every language: German and Finnish run longest, Chinese and Japanese shortest.
- **A store's own lines**: in the listing, a line meant for one store only says so as the app's `AGENTS.md` describes;
  never name another platform in a store's text, nor a feature that store's app lacks.

## Commit messages are release notes

`feat` and `fix` commits become the stores' "What's new" in every language. Write their description for the people
who use the app: what they now get or what works again, in their words, never the code's ("feat(home): count
backwards too", not "feat: add decrement to counter view model"). The `commit-messages` skill has the format.

## Reworking a language, or many

When the owner asks to make a language (or all of them) sound native:

1. Give each language its own native-speaker pass: the English with its comments, the current text, where each text
   is used, the app's voice, and a language the owner already approved as the model of the voice (not as a source).
   It keeps what is natural, rewrites the rest, and keeps a glossary of the key terms so they stay consistent.
2. Have a second native speaker judge each change **blind**: the current and the proposed text in random order, with
   the English, the note and where it is used; it picks one, or writes a third when both are wrong. Closeness to the
   English is no merit. Apply the picks.
3. Check the placeholders, plurals and escaping of every changed text, then run `./gradlew check`.
4. Show the owner a sample of the changes in a language they read, with the English, before opening the pull request.

## Before you open the pull request

- [ ] Every new or changed text is in every language, each as a native speaker would say it, with the language's
      existing words for the app's things.
- [ ] Manual actions and buttons speak to one person; shared results fit one person or many.
- [ ] What users earn is unlocked, never opened.
- [ ] Placeholders, plurals and the app name's placeholder are right in every language; `./gradlew check` passes.
- [ ] Store texts and achievements are within the stores' limits in every language.
- [ ] Commit messages say what users get, in their words.
