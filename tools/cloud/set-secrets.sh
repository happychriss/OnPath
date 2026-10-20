#!/usr/bin/env bash
# Store the map-hosting keys as GitHub environment secrets (docs/design/map-hosting.md §6 step 4).
# Run in your own terminal on the laptop:   bash tools/cloud/set-secrets.sh
# Each value is read from a hidden prompt and piped straight into `gh secret set` – nothing is printed, nothing is
# written to disk except the two app keys in ~/.gradle/gradle.properties (needed to build the app on this laptop).
# Re-running is safe: it overwrites the secrets with what you enter. Press Enter on an empty prompt to skip one.
set -euo pipefail
R=${REPO:-happychriss/OnPath}
GH=$(command -v gh || echo "$HOME/.local/bin/gh")
"$GH" auth status >/dev/null 2>&1 || { echo "gh is not logged in – run: gh auth login"; exit 1; }

put() {  # put NAME ENV PROMPT
  local v
  read -rs -p "$3: " v; echo
  if [ -z "$v" ]; then echo "   skipped $1 ($2)"; return; fi
  printf '%s' "$v" | "$GH" secret set "$1" --repo "$R" --env "$2" >/dev/null
  echo "   ✓ $1 ($2)"
  unset v
}

echo "== Cloudflare deploy token (onpath-maps-deploy) – used for test and production"
read -rs -p "Deploy token: " CF; echo
if [ -n "$CF" ]; then
  for E in test production; do printf '%s' "$CF" | "$GH" secret set CLOUDFLARE_API_TOKEN --repo "$R" --env "$E" >/dev/null; echo "   ✓ CLOUDFLARE_API_TOKEN ($E)"; done
fi
unset CF

echo "== R2 token onpath-builder-test"
put R2_ACCESS_KEY_ID test "Access Key ID (test)"
put R2_SECRET_ACCESS_KEY test "Secret Access Key (test)"

echo "== R2 token onpath-builder-production"
put R2_ACCESS_KEY_ID production "Access Key ID (production)"
put R2_SECRET_ACCESS_KEY production "Secret Access Key (production)"

echo "== App keys (random, generated here, never shown)"
mkdir -p "$HOME/.gradle"; touch "$HOME/.gradle/gradle.properties"
for E in test production; do
  if grep -q "^onpath.mapKey.$E=" "$HOME/.gradle/gradle.properties"; then
    echo "   app key for $E exists already – kept (delete the line in ~/.gradle/gradle.properties to make a new one)"
    KEY=$(grep "^onpath.mapKey.$E=" "$HOME/.gradle/gradle.properties" | cut -d= -f2-)
  else
    KEY=$(openssl rand -hex 24)
    printf '\nonpath.mapKey.%s=%s\n' "$E" "$KEY" >> "$HOME/.gradle/gradle.properties"
  fi
  printf '%s' "$KEY" | "$GH" secret set ONPATH_APP_KEYS --repo "$R" --env "$E" >/dev/null
  echo "   ✓ ONPATH_APP_KEYS ($E)"
  unset KEY
done
chmod 600 "$HOME/.gradle/gradle.properties"

echo "== Secrets now stored (names only):"
for E in test production; do echo "-- $E"; "$GH" secret list --repo "$R" --env "$E"; done
