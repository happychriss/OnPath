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

# R2 S3 credentials are derived from the R2 API token (Cloudflare docs, r2/api/s3/tokens):
#   Access Key ID = the token's id (from the verify API), Secret Access Key = SHA-256 hex of the token value.
ACCOUNT_ID=${CLOUDFLARE_ACCOUNT_ID:-30454d39e2d3e562cf5ef7f0730c5bae}
r2() {  # r2 ENV : read the R2 token value, store R2_ACCESS_KEY_ID + R2_SECRET_ACCESS_KEY for ENV
  local tok id sec
  read -rs -p "R2 token value ($1, onpath-builder-$1): " tok; echo
  if [ -z "$tok" ]; then echo "   skipped R2 keys ($1)"; return; fi
  id=$(curl -fsS -H "Authorization: Bearer $tok" "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT_ID/tokens/verify" 2>/dev/null \
       | python3 -c 'import sys,json; print(json.load(sys.stdin)["result"]["id"])' 2>/dev/null) \
    || id=$(curl -fsS -H "Authorization: Bearer $tok" "https://api.cloudflare.com/client/v4/user/tokens/verify" 2>/dev/null \
       | python3 -c 'import sys,json; print(json.load(sys.stdin)["result"]["id"])' 2>/dev/null) || true
  if [ -z "$id" ]; then echo "   ✗ Cloudflare did not accept this R2 token ($1) – nothing stored"; unset tok; return; fi
  sec=$(printf '%s' "$tok" | sha256sum | cut -d' ' -f1)
  printf '%s' "$id"  | "$GH" secret set R2_ACCESS_KEY_ID     --repo "$R" --env "$1" >/dev/null
  printf '%s' "$sec" | "$GH" secret set R2_SECRET_ACCESS_KEY --repo "$R" --env "$1" >/dev/null
  echo "   ✓ R2_ACCESS_KEY_ID + R2_SECRET_ACCESS_KEY ($1)"
  unset tok id sec
}
echo "== R2 tokens (paste the token VALUE; Access Key ID and secret are derived from it)"
r2 test
r2 production

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
