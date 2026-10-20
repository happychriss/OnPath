#!/usr/bin/env bash
# OnPath map hosting – one-time Google Cloud setup (docs/design/map-hosting.md §Owner setup, step 2).
#
# Paste into Google Cloud Shell (https://shell.cloud.google.com) or run there:
#     bash gcp-setup.sh
# Safe to run again: every step checks what exists and only adds what is missing (bindings are re-applied).
# It never deletes anything and never creates keys. Non-interactive: PROJECT_ID=… REGION=… GH_REPO=… YES=1 bash gcp-setup.sh
#
# What it creates (all labelled app=onpath-maps where Google allows labels):
#   APIs            run, artifactregistry, storage, iam, iamcredentials, sts, secretmanager, cloudresourcemanager
#   Docker repo     Artifact Registry "onpath" (cleanup: keep the 5 newest images, delete older than 30 days)
#   Bucket          gs://<project>-onpath-sources  (OSM extracts + Planetiler sources; private; objects deleted after
#                   120 days – the builder downloads them again when missing)
#   Accounts        per environment (test / prod):
#                     gh-deployer-<env>      GitHub Actions (via Workload Identity, no key): push image, update ITS job,
#                                            add R2 secret versions, create/rotate the invoker key
#                     cell-builder-<env>     runs the job: read/write the source bucket, read ITS two R2 secrets
#                     doorman-invoker-<env>  the Cloudflare Worker: start/cancel ITS job, read executions
#   Secrets         r2-access-key-id-<env>, r2-secret-access-key-<env>   (empty – the deploy workflow fills them)
#   Jobs            onpath-cell-test, onpath-cell-prod (placeholder image until the first deploy)
#   Roles           onpathJobRunner (run/cancel one job), onpathExecutionReader (read executions + operations)
#   Workload Identity pool "github", provider "onpath-repo": only this repository, only branch main, only the
#                   GitHub Environments "test" and "production"; each environment can act only as its own deployer.
set -euo pipefail

bold() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '    \033[33mNOTE:\033[0m %s\n' "$*"; }
ask() { # ask PROMPT DEFAULT → answer
  local a
  if [ "${YES:-0}" = 1 ]; then echo "$2"; return; fi
  read -r -p "$1 [$2]: " a </dev/tty
  echo "${a:-$2}"
}
quiet() { "$@" >/dev/null 2>&1; }
retry() { # retry CMD… – reruns CMD (up to 5 attempts, 65 s apart) when Google answers with a rate limit / quota
  # error (HTTP 429, RESOURCE_EXHAUSTED, e.g. "Service accounts created per minute"); any other error fails at once.
  local n=1 max=5 err rc
  err=$(mktemp)
  while :; do
    if "$@" 2>"$err"; then cat "$err" >&2; rm -f "$err"; return 0; else rc=$?; fi
    cat "$err" >&2
    if [ "$n" -lt "$max" ] && grep -qiE '429|RESOURCE_EXHAUSTED|quota|rate limit' "$err"; then
      warn "Google rate limit – waiting 65 s, then retrying… (attempt $((n + 1)) of $max)"
      sleep 65; n=$((n + 1))
    else
      rm -f "$err"; return "$rc"   # other errors (or still limited after $max attempts) fail as before
    fi
  done
}

command -v gcloud >/dev/null || { echo "gcloud not found – run this in Google Cloud Shell." >&2; exit 1; }

bold "Settings"
info "Region: europe-west1 (Belgium) is recommended – Cloud Run Jobs + Cloud Storage volumes are available there,"
info "it is a 'Tier 1' price region (europe-west3 Frankfurt is Tier 2, roughly 20–40 % more for CPU/RAM) and low-CO2."
info "Build latency does not matter (batch jobs), and the source bucket must be in the same region (free traffic)."
PROJECT_ID=${PROJECT_ID:-$(ask "Google Cloud project id" "$(gcloud config get-value project 2>/dev/null || true)")}
REGION=${REGION:-$(ask "Region" "europe-west1")}
GH_REPO=${GH_REPO:-$(ask "GitHub repository" "happychriss/OnPath")}
[ -n "$PROJECT_ID" ] || { echo "No project id." >&2; exit 1; }
AR_REPO=onpath
SRC_BUCKET="${PROJECT_ID}-onpath-sources"
POOL=github
PROVIDER=onpath-repo
PLACEHOLDER_IMAGE=us-docker.pkg.dev/cloudrun/container/job:latest
info "project=$PROJECT_ID  region=$REGION  repo=$GH_REPO  bucket=gs://$SRC_BUCKET"
if [ "${YES:-0}" != 1 ]; then
  read -r -p "Continue? (y/N) " ok </dev/tty
  [ "$ok" = y ] || [ "$ok" = Y ] || exit 1
fi

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
info "project number: $PROJECT_NUMBER"

bold "1/10 Billing"
BILLING=$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingAccountName)' 2>/dev/null || true)
if [ -z "$BILLING" ]; then
  warn "Could not see a billing account for this project. Link one: https://console.cloud.google.com/billing/linkedaccount?project=$PROJECT_ID"
  warn "(Cloud Run, Artifact Registry and Secret Manager need billing even inside the free tier.)"
else
  info "billing account: ${BILLING#billingAccounts/}"
fi

bold "2/10 Enable APIs (takes a minute the first time)"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com storage.googleapis.com iam.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com secretmanager.googleapis.com cloudresourcemanager.googleapis.com
info "ok"

bold "3/10 Artifact Registry Docker repository '$AR_REPO' (builder images)"
if quiet gcloud artifacts repositories describe "$AR_REPO" --location "$REGION"; then info "exists"
else
  retry gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location "$REGION" \
    --description="OnPath map cell builder images" --labels=app=onpath-maps
fi
POLICY=$(mktemp)
cat > "$POLICY" <<'JSON'
[
  {"name": "keep-newest-5", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 5}},
  {"name": "delete-older-30d", "action": {"type": "Delete"}, "condition": {"olderThan": "30d"}}
]
JSON
gcloud artifacts repositories set-cleanup-policies "$AR_REPO" --location "$REGION" --policy="$POLICY" --no-dry-run >/dev/null
rm -f "$POLICY"
info "cleanup policy: keep the 5 newest images, delete others after 30 days (storage over 0.5 GB costs)"

bold "4/10 Source bucket gs://$SRC_BUCKET (OSM extracts, Planetiler sources – public data, private bucket)"
if quiet gcloud storage buckets describe "gs://$SRC_BUCKET"; then info "exists"
else
  retry gcloud storage buckets create "gs://$SRC_BUCKET" --location "$REGION" --default-storage-class=STANDARD \
    --uniform-bucket-level-access --public-access-prevention
fi
LC=$(mktemp)
echo '{"rule": [{"action": {"type": "Delete"}, "condition": {"age": 120}}]}' > "$LC"
gcloud storage buckets update "gs://$SRC_BUCKET" --lifecycle-file="$LC" --clear-soft-delete --update-labels=app=onpath-maps >/dev/null
rm -f "$LC"
info "lifecycle: objects older than 120 days are deleted (re-downloaded when needed); no soft-delete copies"

bold "5/10 Service accounts"
sa() { echo "$1@$PROJECT_ID.iam.gserviceaccount.com"; }
for e in test prod; do
  for pair in "gh-deployer-$e|GitHub Actions deploy ($e)" "cell-builder-$e|Map cell builder job ($e)" \
              "doorman-invoker-$e|Cloudflare doorman: starts builder job ($e)"; do
    name=${pair%%|*}; title=${pair#*|}
    if quiet gcloud iam service-accounts describe "$(sa "$name")"; then info "$name exists"
    else retry gcloud iam service-accounts create "$name" --display-name="$title" --description="OnPath maps, see docs/design/map-hosting.md"
    fi
  done
done

bold "6/10 Secret Manager: R2 upload keys for the builder (empty now, filled by the deploy workflow)"
for e in test prod; do
  for s in "r2-access-key-id-$e" "r2-secret-access-key-$e"; do
    if quiet gcloud secrets describe "$s"; then info "$s exists"
    else retry gcloud secrets create "$s" --replication-policy=user-managed --locations="$REGION" --labels=app=onpath-maps,env=$e
    fi
    retry gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$(sa "cell-builder-$e")" \
      --role=roles/secretmanager.secretAccessor >/dev/null
    retry gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$(sa "gh-deployer-$e")" \
      --role=roles/secretmanager.secretVersionAdder >/dev/null
    retry gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$(sa "gh-deployer-$e")" \
      --role=roles/secretmanager.viewer >/dev/null   # metadata only (does a version exist?), no values
  done
done
info "cell-builder-<env> may read, gh-deployer-<env> may add versions (not read) – each only its own environment"

bold "7/10 Custom roles (smallest permission sets for the doorman)"
role() { # role ID TITLE PERMISSIONS
  if quiet gcloud iam roles describe "$1" --project "$PROJECT_ID"; then
    retry gcloud iam roles update "$1" --project "$PROJECT_ID" --permissions="$3" --quiet >/dev/null; info "$1 updated"
  else
    retry gcloud iam roles create "$1" --project "$PROJECT_ID" --title="$2" --permissions="$3" --stage=GA >/dev/null; info "$1 created"
  fi
}
role onpathJobRunner "OnPath: run one Cloud Run job" \
  "run.jobs.get,run.jobs.run,run.jobs.runWithOverrides,run.executions.cancel"
role onpathExecutionReader "OnPath: read Cloud Run executions" \
  "run.executions.get,run.executions.list,run.operations.get"

bold "8/10 Builder jobs (placeholder image; the deploy workflow replaces it) and who may touch them"
for e in test prod; do
  job="onpath-cell-$e"
  if quiet gcloud run jobs describe "$job" --region "$REGION"; then info "$job exists"
  else
    retry gcloud run jobs create "$job" --region "$REGION" --image "$PLACEHOLDER_IMAGE" --service-account "$(sa "cell-builder-$e")" \
      --tasks 1 --max-retries 0 --labels=app=onpath-maps,env=$e >/dev/null
    info "$job created (placeholder)"
  fi
  retry gcloud run jobs add-iam-policy-binding "$job" --region "$REGION" --member="serviceAccount:$(sa "doorman-invoker-$e")" \
    --role="projects/$PROJECT_ID/roles/onpathJobRunner" >/dev/null
  retry gcloud run jobs add-iam-policy-binding "$job" --region "$REGION" --member="serviceAccount:$(sa "gh-deployer-$e")" \
    --role=roles/run.developer >/dev/null
  retry gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$(sa "doorman-invoker-$e")" \
    --role="projects/$PROJECT_ID/roles/onpathExecutionReader" --condition=None >/dev/null
  retry gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$(sa "gh-deployer-$e")" \
    --role=roles/run.viewer --condition=None >/dev/null
  # deployer: run the job as cell-builder-<env>, push images, create/rotate the invoker's key (nothing else)
  retry gcloud iam service-accounts add-iam-policy-binding "$(sa "cell-builder-$e")" \
    --member="serviceAccount:$(sa "gh-deployer-$e")" --role=roles/iam.serviceAccountUser >/dev/null
  retry gcloud iam service-accounts add-iam-policy-binding "$(sa "doorman-invoker-$e")" \
    --member="serviceAccount:$(sa "gh-deployer-$e")" --role=roles/iam.serviceAccountKeyAdmin >/dev/null
  retry gcloud artifacts repositories add-iam-policy-binding "$AR_REPO" --location "$REGION" \
    --member="serviceAccount:$(sa "gh-deployer-$e")" --role=roles/artifactregistry.writer >/dev/null
  retry gcloud storage buckets add-iam-policy-binding "gs://$SRC_BUCKET" \
    --member="serviceAccount:$(sa "cell-builder-$e")" --role=roles/storage.objectUser >/dev/null
  info "$e: bindings applied"
done
ORGPOL=$(gcloud resource-manager org-policies describe iam.disableServiceAccountKeyCreation --project "$PROJECT_ID" \
  --effective --format='value(booleanPolicy.enforced)' 2>/dev/null || true)
if [ "$ORGPOL" = True ]; then
  warn "The organisation policy iam.disableServiceAccountKeyCreation is ENFORCED for this project. The doorman needs one"
  warn "key for doorman-invoker-<env> (Cloudflare cannot use Workload Identity). Allow keys for this project only:"
  warn "  https://console.cloud.google.com/iam-admin/orgpolicies/iam-disableServiceAccountKeyCreation?project=$PROJECT_ID"
fi

bold "9/10 Workload Identity Federation for GitHub Actions (no keys in GitHub)"
REPO_ID=$(curl -fsS "https://api.github.com/repos/$GH_REPO" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null || true)
[ -n "$REPO_ID" ] || REPO_ID=$(ask "Numeric id of $GH_REPO (gh api repos/$GH_REPO --jq .id)" "")
[ -n "$REPO_ID" ] || { echo "Need the repository id." >&2; exit 1; }
info "repository id: $REPO_ID (survives renames; a deleted+recreated repo with the same name gets a new id)"
if quiet gcloud iam workload-identity-pools describe "$POOL" --location global; then info "pool $POOL exists"
else retry gcloud iam workload-identity-pools create "$POOL" --location global --display-name="GitHub Actions"
fi
MAPPING="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment,attribute.ref=assertion.ref"
COND="assertion.repository_id == '$REPO_ID' && assertion.ref == 'refs/heads/main' && assertion.environment in ['test', 'production']"
if quiet gcloud iam workload-identity-pools providers describe "$PROVIDER" --workload-identity-pool "$POOL" --location global; then
  retry gcloud iam workload-identity-pools providers update-oidc "$PROVIDER" --workload-identity-pool "$POOL" --location global \
    --attribute-mapping="$MAPPING" --attribute-condition="$COND" >/dev/null
  info "provider $PROVIDER updated"
else
  retry gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" --workload-identity-pool "$POOL" --location global \
    --issuer-uri="https://token.actions.githubusercontent.com" --attribute-mapping="$MAPPING" --attribute-condition="$COND" \
    --display-name="OnPath repository"
fi
PRINCIPALS="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$POOL"
retry gcloud iam service-accounts add-iam-policy-binding "$(sa gh-deployer-test)" --role=roles/iam.workloadIdentityUser \
  --member="$PRINCIPALS/attribute.environment/test" >/dev/null
retry gcloud iam service-accounts add-iam-policy-binding "$(sa gh-deployer-prod)" --role=roles/iam.workloadIdentityUser \
  --member="$PRINCIPALS/attribute.environment/production" >/dev/null
info "GitHub Environment 'test' → gh-deployer-test, 'production' → gh-deployer-prod; nothing else can log in"

bold "10/10 Budget alert (console step – Google offers no budget command without extra rights)"
info "Open https://console.cloud.google.com/billing/${BILLING#billingAccounts/}/budgets → Create budget:"
info "  name 'OnPath maps', scope: project $PROJECT_ID, amount 10 EUR per month, alerts at 50 %, 90 %, 100 % (email)."
info "A budget only WARNS – it never stops spending. The hard limits are the doorman's cost guards"
info "(MAX_EXEC_PER_HOUR / MAX_EXEC_PER_DAY in tools/cloud/worker/wrangler.toml) and the job's timeout."

WIF_PROVIDER="projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"
bold "Done. Now set these GitHub variables (not secrets – nothing here is confidential), e.g. in this shell:"
cat <<EOF

  gh api -X PUT repos/$GH_REPO/environments/test >/dev/null
  gh api -X PUT repos/$GH_REPO/environments/production >/dev/null
  gh variable set GCP_PROJECT_ID   --repo $GH_REPO --body "$PROJECT_ID"
  gh variable set GCP_REGION       --repo $GH_REPO --body "$REGION"
  gh variable set GCP_WIF_PROVIDER --repo $GH_REPO --body "$WIF_PROVIDER"
  gh variable set GCP_SRC_BUCKET   --repo $GH_REPO --body "$SRC_BUCKET"
  gh variable set GCP_DEPLOYER_SA  --repo $GH_REPO --env test       --body "$(sa gh-deployer-test)"
  gh variable set GCP_DEPLOYER_SA  --repo $GH_REPO --env production --body "$(sa gh-deployer-prod)"

(Cloud Shell has no 'gh' logged in – run them on the laptop, or set them in GitHub → Settings → Secrets and
variables → Actions → Variables.) Next: docs/design/map-hosting.md §Owner setup, step 3 (Cloudflare).
EOF
