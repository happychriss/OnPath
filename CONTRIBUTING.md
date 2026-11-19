# Contributing to OnPath

Thank you for your interest. OnPath is a young project, and help is welcome – from a bug report after one walk to a
new feature.

## Ways to help

- **Test it.** Use OnPath on your phone and your paths and tell me what happened. Reports from different phones and
  regions are the most valuable help right now.
- **Report a problem.** Open an issue with what you did, what you expected, what happened, your phone model and the app
  version. A screenshot helps a lot.
- **Improve wording and accessibility.** German and English texts, large fonts, screen readers.
- **Fix or build something.** Small, focused pull requests are easiest to review.
- **Share what you built.** If you run your own app on top of OnPath, tell me and I will list it in the README.

## Before you write code

- For anything larger than a small fix, please open an issue first so we can agree on the scope. It saves you from
  building something that does not fit.
- OnPath has four priorities, in this order: robust first (never lose a recording, never crash on missing data, GPS or
  network), then offline, then everything on the phone, then simple. A change that trades an earlier one for a later
  one will not be merged.
- No Google Play Services, and as few dependencies as possible.

## How to send a change

1. Fork the repository and create a branch from `main`.
2. Build with JDK 17 and Android SDK 36: `./gradlew assembleDebug`.
3. Add or adjust a test for what you changed. A bug fix starts with a test that reproduces the bug.
4. Run `tools/ci/gate.sh` before you push.
5. Open a pull request against `main` and describe what changes for the user.

## Good to know

- **The code is being restructured.** Shared logic is moving into the `core` and `state` modules, so `main` changes
  quickly at the moment. Rebase often, and ask in an issue where a change should go.
- **Much of OnPath is written with AI coding assistants** under my direction and review. `CLAUDE.md`, `docs/roles/` and
  `docs/backlog.md` are their working rules and notes, not rules for you. Your pull requests are reviewed by me.
- **Where things are:** requirements in [docs/requirements.md](docs/requirements.md), the core features in
  [docs/core-features.md](docs/core-features.md), the architecture in
  [docs/architecture-contract.md](docs/architecture-contract.md), the browser planner in [web/](web/).

## Licence

OnPath is licensed under Apache-2.0. By sending a pull request you agree that your contribution is licensed under the
same terms.

## Be kind

Be respectful and assume good intent. I do this in my free time and may take a few days to answer.
