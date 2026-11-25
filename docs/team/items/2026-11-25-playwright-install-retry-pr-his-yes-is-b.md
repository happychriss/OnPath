# Playwright install retry PR (his yes) is blocked by the token

- [aufräumen] 2026-11-25 morning: **Playwright install retry PR (his yes) is blocked by the token** – donald's `gh` token has no `workflow` scope (GitHub refuses any push touching `.github/workflows/`). His step: `gh auth refresh -h github.com -s workflow` on donald (browser login) – then aufräumen pushes `pr/web-playwright-install-retry` (53182aab5) and merges as usual; or he pushes that branch himself. **Owner 2026-11-25 ~12:15 (donald's chat): he runs the `gh auth refresh` himself** – then aufräumen pushes + merges.
