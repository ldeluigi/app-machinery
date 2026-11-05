---
name: commit-messages
description: How to write commit messages and pull request titles that pass this repository's checks and make good release notes and store "What's new". Use before every git commit, gh pr create, pull request title change or squash merge, in an app or in app-machinery.
---

# Commit messages and pull request titles

CI checks the message of every commit pushed and every pull request title. The version number, the GitHub Release
notes and, in an app, the stores' "What's new" are made from them. A pushed commit can't be reworded (force pushes are
refused), so get it right before pushing.

This skill is app-machinery's: its monthly refresh copies it into every app. Change it there, not in an app.

## The shape

```
<type>(<scope>): <description>

<body, optional: why, in sentences, any line length>

<trailers, optional: Co-Authored-By: ...>
```

- **type**, lower case: `feat`, `fix`, `perf`, `revert`, `refactor`, `build`, `ci`, `docs`, `test`, `chore` or `style`.
- **scope**, optional, lower case: the part the change touches, like `game`, `ios`, `printer` or `deps`.
- **description**: not empty, no full stop at the end. Lower case reads best; nothing checks the case.
- **The subject, the whole first line, has 100 characters at most.** The local commit-msg hook checks the shape but
  not the length; CI's commitlint does. Count it: `git log -1 --format=%s | awk '{print length}'`.
- A blank line between the subject and the body.
- A breaking change: `feat!: ...`, or a `BREAKING CHANGE: ...` footer.

## Pull request titles

- **The same shape, 90 characters at most.** Squash-merged, the title becomes the commit on the default branch, and
  GitHub appends ` (#<number>)` to it: commitlint counts that too. The Automation workflow's title check measures the
  subject the merge will make.
- It names the whole change, like `feat(game): replay the song from the beginning`: never `[WIP] ...` or a plain
  sentence.
- Every commit on the branch is checked as well, not only the title.

## Never in a commit message

- **An issue or pull request number, in any form, even as an example**: `Closes #12`, `Fixes #12`, `(#12)`, a bare
  `#12`. The changelog would add ", closes #12" to the release notes and the stores' "What's new". The pull request's
  description closes the issue instead (`Closes #12` there). The one exception is the ` (#<number>)` GitHub appends when
  squash-merging. To write about such numbers, use words: "the pull request's number".
- **A forbidden name**: the patterns in `config/forbidden-names.txt`, when the repository has that file (a competitor's
  trademark, say). CI checks commit messages and files against them.
- **Personal names or usernames** of the maintainers.

## Which type: releases come from it

| Type | Release | In an app |
|---|---|---|
| `feat` | minor, like 1.4.0 | a line of "What's new"; in-app update priority 3 |
| `fix`, `perf`, `revert` | patch, like 1.3.1 | a line of "What's new"; in-app update priority 2, or 1 when every change of the release has a maintenance scope |
| any type with `!` or `BREAKING CHANGE` | major, like 2.0.0 | in-app update priority 5 |
| `refactor`, `build`, `ci`, `docs`, `test`, `chore`, `style` | none | nothing |

Choose by what the people who get the release notice. In an app, a change players see is `feat` or `fix`; work they
can't see (restructuring, the build, CI, docs) takes another type, so that it doesn't ship a release with nothing new.
In app-machinery, `feat`, `fix` and `perf` release a machinery version that Dependabot then offers every app: use them
for what apps should get.

## The description is what people read

In an app, the description of each `feat`, `fix`, `perf` and `revert` becomes a line of the stores' "What's new",
translated into the app's languages, its first letter capitalized and its scope dropped. Write it for players, about
what they notice: `fix(game): the timer keeps running when the phone locks`, not `fix: null check in TimerViewModel`.

Maintenance scopes become one fixed line each, whatever their descriptions: `deps` ("Updated components for stability
and security."), `machinery` and `build` ("Kept the app up to date with the latest Android versions."), and the app's
own, listed under `scope-notes` in its `.github/workflows/ci.yml` (`catalog`, say). A patch release made of
maintenance scopes only gets in-app update priority 1, which apps keep quiet; one fix players notice makes it 2. So a
fix players notice takes a scope that isn't a maintenance one, and a dependency bump always takes `deps`.

## Before pushing

1. The subject has 100 characters at most, a pull request title 90; type and scope are lower case; no full stop at the
   end.
2. `git log -1 --format=%B | grep -nE '(^|[^[:alnum:]_])#[0-9]+'` prints nothing.
3. Nothing in the message matches `config/forbidden-names.txt`.

A wrong commit already pushed to a pull request's branch: build a new branch from the default branch with a corrected
commit, open a new pull request from it, and close the old one. A wrong commit already on the default branch stays
there: CI checks each push's own commits only, so the next push is green again.
