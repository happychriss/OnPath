# Map hosting: Cloudflare doorman + R2, Google Cloud Run Jobs builder

Status: **owner decision 2026-10-20.** Test environment deployed and used by the app since 0.8.x (`maps-test…`, auto
deploy on push to `main`); **production not deployed yet** (owner: later).
Replaces the Hetzner plan of `docs/design/map-cells.md` §5 / §11 / §12 phase 3 (`tools/hetzner/` is superseded).
The cell format, the build steps, the `/v1` API and the app side stay exactly as in map-cells.md.
zo stays a test machine: `tools/cells/run.sh` (BUILDER_MODE=local) is unchanged.

## 1. Picture

```
 phone (OnPath)                Cloudflare (always on, free plan)          Google Cloud (only while building)
 ──────────────                ─────────────────────────────────          ──────────────────────────────────
 POST /v1/cells/request ─────► Worker "onpath-doorman-<env>"               Cloud Run Job "onpath-cell-<env>"
 GET  /v1/cells/status  ◄───── │  app key, rate limit, dedupe, queue (D1)    (image from Artifact Registry)
                               │  cost guards                                │
                               │  cron every minute ── jobs.run(CELLS) ────► │ copy OSM extract + Planetiler
                               │                  ◄─ executions.get ──────── │ sources from the bucket (GCS)
                               │                                             │ build_cell.sh (unchanged)
                               │  reads status/f1/<cell>.json  ◄──────────── │ upload version folder, then
 GET manifest + files  ◄────── │  R2 bucket "onpath-maps-<env>" ◄─── S3 PUT ─ │ manifest, then current.json
   (Range, immutable)          ▼  (egress free)                              ▼ exits; nothing keeps running
                          maps[-test].free-your-pictures.org          gs://<project>-onpath-sources
```

- **Doorman** = Cloudflare Worker (`tools/cloud/worker/`): the `/v1` API of map-cells.md §8, state in **D1**
  (SQLite: the queue table of map-cells.md §5), files straight from **R2** through the Worker (one hostname, app key
  checked, HTTP Range, `immutable` caching for version folders).
- **Builder** = Cloud Run **Job** (`tools/cloud/Dockerfile.job`, `job.yaml`, `job_build.py`): the zo image
  (`tools/cells/Dockerfile`) plus the pipeline code baked in. One execution builds up to 4 cells one after the other.
- **Status without callbacks:** the builder writes `status/f<format>/<cell>.json` into the same R2 bucket
  (step, progress, ready/failed + plain reason); the Worker's cron (every minute) asks the Cloud Run API whether the
  execution ended and reads these files. No inbound URL into the job, no shared callback secret. A cell that is ready
  is published at once, even while the execution still builds the others.
- **Up to `PARALLEL_BUILDS` (4) builders at a time** per environment (§7f): a D1 lease makes sure only one pump
  runs (a cell is never put into two executions); cells someone waits for go first, one slot stays free for them
  (plus the hourly/daily caps below).

### Choices and why

| Question | Choice | Why |
| --- | --- | --- |
| Region | **europe-west1** (Belgium) | Cloud Run Jobs + Cloud Storage volumes available; Tier-1 prices (europe-west3 Frankfurt is Tier 2, ~20–40 % dearer CPU/RAM); low CO2. Latency is irrelevant for batch builds; bucket and job in the same region = free traffic |
| Job size | **2 vCPU, 8 GiB, 60 min timeout, 1 retry, 1 task per execution; up to 4 executions at once (§7f)** | A cell peaks at ~2.3 GB RAM, but Cloud Run's disk is memory: Planetiler sources (~1.4 GB) + OSM extract + work folder live in RAM too. 8 GiB needs ≥ 2 vCPU. Settable via repository variables `JOB_CPU`, `JOB_MEMORY`, `JOB_TIMEOUT_S` |
| Planetiler sources (water polygons, Natural Earth, ~1.4 GB) | **in the source bucket, copied in at start** (~15–30 s in-region) | Baking them in would make every image push ~2 GB and pin them to the image; downloading from the internet every run is slow and unfriendly to the hosts. First run: Planetiler downloads them, the builder saves them to the bucket |
| OSM extracts | **chosen per cell from the Geofabrik index, kept in the source bucket, refreshed by the builder itself** when older than 30 days (§7e) | Any area works; the first cell in a new country waits for one download (minutes). No extra job or schedule. Open sea: answered "No map data exists for this area" by the builder |
| Bucket access in the job | Cloud Storage **volume mount** at `/src` (gcsfuse) | No gcloud/gsutil in the image (keeps it small); plain file copies |
| R2 upload | **stdlib SigV4 client** (`tools/cloud/r2.py`, tested against AWS's published examples) | No aws-cli/boto3/rclone in the image |
| Job state | **D1** | A queue needs consistent reads (KV is eventually consistent) and the doc's design was already SQLite |
| Rate limit | D1 counter per IP and minute (`RATE_LIMIT_PER_MIN`, default 30) on `/v1/cells/*` | Works on the free plan; fails open if D1 hiccups. File downloads are not rate-limited (only the key is checked) |
| Downloads | through the Worker from the R2 binding | One hostname, key check, Range + ETag/304; no public bucket. (A public R2 custom domain would save Worker requests – only worth it at > 100 000 requests/day) |
| Worker → Google | service-account **key** of `doorman-invoker-<env>` as Worker secret; JWT signed with WebCrypto | Cloudflare Workers cannot use Workload Identity Federation. The key can only start/cancel that one job and read executions |
| GitHub → Google | **Workload Identity Federation**, no key | Only repository `happychriss/OnPath` (by numeric id), branch `main`, GitHub Environments `test` / `production`; each environment can act only as its own `gh-deployer-<env>` |

## 2. Environments

| | test | production |
| --- | --- | --- |
| Deployed | automatically on push to `main` when `tools/cells/**`, `tools/cloud/**`, `tools/*.py`, `tools/build_region.sh` or the workflow change | by hand: Actions → **Maps deploy** → Run workflow → `production`; waits for the owner's approval (GitHub Environment `production`, required reviewer) |
| Hostname | `maps-test.free-your-pictures.org` (variable `MAPS_HOST_TEST`) | `maps.free-your-pictures.org` (variable `MAPS_HOST_PRODUCTION`) |
| Worker / D1 / R2 bucket | `onpath-doorman-test` / `onpath-doorman-test` / `onpath-maps-test` | `onpath-doorman-production` / `onpath-doorman-production` / `onpath-maps-production` |
| Cloud Run Job / accounts | `onpath-cell-test`, `*-test` | `onpath-cell-prod`, `*-prod` |
| App | **debug** builds (`BuildConfig.MAP_SERVER_URL`) | **release** builds |
| Cost guards (wrangler.toml) | 4 executions/hour, 20/day, queue ≤ 200 | 6/hour, 40/day, queue ≤ 300 |

Both share the Artifact Registry repository and the source bucket (public OSM data only).

## 3. Costs (rough, October 2026 list prices – check the pricing pages)

Assumptions: format 1 cell ≈ 28 MB (format 2 ≈ 10 MB); one cell ≈ 3 min of builder time incl. its share of the
start-up (4 cells per execution); 2 vCPU + 8 GiB.

| Item | Free allowance | Our use | Cost |
| --- | --- | --- | --- |
| Cloud Run Jobs (europe-west1: $0.000018/vCPU-s, $0.000002/GiB-s) | 240 000 vCPU-s + 450 000 GiB-s per month | per cell 360 vCPU-s + 1 440 GiB-s → **~300 cells/month free**, then ≈ **$0.01 per cell** | 0 € at test use; 1 000 cells/month ≈ 4 € |
| Cloud Storage (source bucket, ~2–5 GB Standard) | – (free tier is US-only) | $0.020/GB-month | ~0.10 €/month |
| Artifact Registry | 0.5 GB | image ~0.8 GB × ≤ 5 kept | ~0.30 €/month |
| Secret Manager | 6 versions, 10 000 accesses | 4 secrets | ~0 € |
| Cloudflare Workers (free plan) | 100 000 requests/day, cron included | API polls + file requests | 0 € |
| D1 (free) | 5 M reads, 100 000 writes per day, 5 GB | a few thousand | 0 € |
| R2 | 10 GB-month, 1 M writes, 10 M reads per month, **egress free** | 1 000 cells ≈ 28 GB (f1) / 10 GB (f2) | 0–0.30 €/month |
| **Total** | | | **≈ 0–1 €/month at test use; ≈ 5 €/month at 1 000 builds** |

**Budget alert:** 10 € per month on the project, alerts at 50 / 90 / 100 % (setup step 2). A budget only warns. The
hard limits are the doorman's cost guards (`MAX_EXEC_PER_HOUR`, `MAX_EXEC_PER_DAY`, `MAX_QUEUE`,
`MAX_CELLS_PER_REQUEST`), the job's timeout and parallelism 1. Worst case per day with the production caps:
40 executions × 60 min × (2 vCPU + 8 GiB) ≈ 40 × $0.19 ≈ **$7.50 per day** (twice that if every task also used its
one retry) – only if something loops; the budget mail comes first. **Emergency stop:** set `BUILDS_ENABLED = "false"` for the environment in
`tools/cloud/worker/wrangler.toml` and deploy, or Cloudflare dashboard → Worker → Settings → Variables (immediate).

## 4. Security model – who holds which key

| Who | Holds | Can do | Cannot |
| --- | --- | --- | --- |
| Owner | Google account (project owner), Cloudflare account, GitHub | everything | – |
| GitHub Actions, environment `test` | short-lived Google token as `gh-deployer-test` (WIF); `CLOUDFLARE_API_TOKEN`; R2 test keys; test app key | push images; update **only** `onpath-cell-test`; run it as `cell-builder-test`; add versions to the two test R2 secrets (not read them); create/delete keys of `doorman-invoker-test`; deploy the Worker | touch anything of production on Google; read any secret value in Google; log in from another repo/branch |
| GitHub Actions, environment `production` | same for `*-prod` – only after the owner approved the run | same for production | – |
| `cell-builder-<env>` (the job) | runtime identity on Google; R2 key of **its** bucket (Object Read & Write) | read/write the source bucket; read its two R2 secrets; write objects to its R2 bucket | start jobs, see other secrets, touch the other environment's bucket |
| Worker `onpath-doorman-<env>` | `APP_KEYS`; `GCP_INVOKER_KEY` (key of `doorman-invoker-<env>`); bindings to its D1 + R2 | start/cancel **its** job (with CELLS/FORMAT overrides); read executions; read/write its bucket and database | deploy anything, read Google secrets, touch the source bucket |
| App | one app key per environment, compiled in (map-cells.md Q8) | call `/v1` | anything else; real protection comes with the pass token (map-cells.md §13) |

Notes:
- The Cloudflare API token is account-wide for Workers (Cloudflare has no per-Worker tokens), so a test run *could*
  deploy the production Worker. The expensive side (Google) is separated per environment; the Cloudflare side is
  bounded by the cost guards and the free plan. Use two tokens if you want them separable later.
- The invoker key never appears in logs: gcloud writes it to a 0600 temp file in the runner, `wrangler secret put`
  reads it from stdin, the file is shredded. It is created only when the Worker has none, or when the workflow is
  run with `rotate_invoker_key`; old keys of that account are then deleted.
- Personal Google accounts have no organisation policies. If the project belongs to an organisation that enforces
  `iam.disableServiceAccountKeyCreation`, `gcp-setup.sh` says so – allow keys for this project only.

## 5. Deploy, rollback, bug fix

| Situation | What to do |
| --- | --- |
| Normal change (pipeline, Worker, job settings) | push to `main` → **test** deploys by itself (Actions → Maps deploy). Check `https://maps-test…/v1/health`, build a cell with the debug app or `smoke.sh` |
| Promote to production | Actions → Maps deploy → Run workflow → `production` → approve. Deploys exactly the commit at the head of `main` |
| Worker broken | Cloudflare dashboard → Workers → `onpath-doorman-<env>` → Deployments → **Rollback** (instant), or `npx wrangler rollback --env <env>`; then fix on `main` |
| Builder broken | `gcloud run jobs update onpath-cell-<env> --region europe-west1 --image <older tag>` in Cloud Shell (tags = commit ids, 5 kept), or `git revert` + deploy. Meanwhile requests just queue/fail with a reason; nothing published breaks (versions are immutable, `current.json` moves only after a complete upload) |
| Bad cells published | Fix the pipeline, deploy, then queue a rebuild: `npx wrangler d1 execute onpath-doorman-<env> --remote --env <env> --command "UPDATE cells SET job='queued', priority=3, requested_at=unixepoch()*1000, attempts=0, permanent=0 WHERE id IN ('10-543-361')"` (all cells: drop the `WHERE`; the hourly cap paces it) |
| Cell failed 3 times | Failed cells are retried automatically after the next deploy (the Worker compares `BUILD_REV`) when someone asks again |
| Stop all builds now | `BUILDS_ENABLED = "false"` (see §3) |
| Logs | Cloudflare: Worker → Logs (JSON lines: `request`, `execution-started`, `cell-ready`, `cell-failed`, `cost-guard`). Google: Cloud Run → Jobs → `onpath-cell-<env>` → Executions → Logs (the full `build_cell.sh` output) |

## 6. Owner setup – step by step (≈ 45 minutes, once)

Values (keys, tokens) are always pasted into hidden prompts or piped; they never appear on screen or in a file in
the repo. Commands with `gh` run on the laptop (`gh auth login` once).

### Step 1 – Google account, project, billing
1. https://console.cloud.google.com → project picker → **New project**, name `onpath-maps` (note the **project id**).
2. Billing → link a billing account to that project (a card is needed even for free use).

### Step 2 – Cloud Shell script + budget
1. Open **Cloud Shell** (terminal icon top right) with that project selected.
2. Get the script and run it (it asks for project id, region `europe-west1`, repo `happychriss/OnPath`):
   ```bash
   curl -fsSLO https://raw.githubusercontent.com/happychriss/OnPath/main/tools/cloud/gcp-setup.sh
   less gcp-setup.sh        # read it first – it only creates, never deletes
   bash gcp-setup.sh
   ```
   Re-running it is safe. At the end it prints the `gh variable set …` lines – keep them for step 4.
3. **Budget:** the link printed at step 10 → Create budget: scope = this project, 10 EUR / month, alerts 50 / 90 / 100 %.

### Step 3 – Cloudflare (dashboard, account that has the zone `free-your-pictures.org`)
1. Note the **Account ID** (Workers & Pages → right column, or the dashboard URL).
2. **R2 → enable** (accept the plan; it stays free within the allowance). Create two buckets: `onpath-maps-test` and
   `onpath-maps-production` with **jurisdiction: European Union** (done – both exist with EU jurisdiction). (The
   workflow would create them too, but the bucket tokens below need them to exist.)
   **Why EU jurisdiction:** the data (map cells, status files) is guaranteed to stay in the EU – the owner and the
   users are in Europe, and it is the simplest GDPR answer. Consequences, all handled in the code:
   - S3 endpoint has `.eu.`: `https://<account>.eu.r2.cloudflarestorage.com/<bucket>` – the builder builds it from
     `R2_ACCOUNT_ID` + `R2_JURISDICTION` (default `eu`, `tools/cloud/r2.py`; `R2_ENDPOINT` overrides). The S3
     signing region stays `auto`.
   - Worker bindings in `wrangler.toml` carry `jurisdiction = "eu"` (without it the binding finds no bucket).
   - Every `wrangler r2 bucket …` call passes `--jurisdiction eu` – without it wrangler would look at (or create!) a
     second, non-EU bucket of the same name. The workflow takes this from the GitHub variable `R2_JURISDICTION`
     (default `eu`; `default` = buckets without jurisdiction). The D1 database is created with location hint
     `weur` (variable `D1_LOCATION`).
3. **R2 → Manage R2 API Tokens → Create API token** – once per environment:
   - name `onpath-builder-test`, permission **Object Read & Write**, **apply to specific bucket: `onpath-maps-test`
     only**, TTL: forever → copy *Access Key ID* and *Secret Access Key* (shown once) into step 4.
   - the same for `onpath-builder-production` → bucket `onpath-maps-production`.
   (Why by hand: an API token that could create R2 tokens could also create tokens with any other right – that is
   not least privilege.)
4. **My Profile → API Tokens → Create Token → Custom token** `onpath-maps-deploy`:
   - Account → **Workers Scripts: Edit**
   - Account → **Workers R2 Storage: Edit** (create the buckets if missing)
   - Account → **D1: Edit**
   - Account → **Account Settings: Read**
   - Zone → **Workers Routes: Edit**, Zone → **DNS: Edit**, Zone → **Zone: Read** – Zone resources: *Specific zone →
     free-your-pictures.org*
   - Account resources: *only your account*. Client IP filtering: none (GitHub runners change). TTL: optional.
   If the first deploy fails at the custom domain with an authentication error, add Zone → *SSL and Certificates:
   Edit* to the token.

### Step 4 – GitHub: environments, variables, secrets (laptop)
```bash
R=happychriss/OnPath
gh api -X PUT repos/$R/environments/test >/dev/null
gh api -X PUT repos/$R/environments/production >/dev/null
# then the gh variable set … lines printed by gcp-setup.sh, plus:
gh variable set CLOUDFLARE_ACCOUNT_ID --repo $R --body "<account id>"        # not secret
# optional: gh variable set MAPS_HOST_TEST --repo $R --body maps-test.example.org   (same for MAPS_HOST_PRODUCTION)

for E in test production; do
  gh secret set CLOUDFLARE_API_TOKEN --repo $R --env $E          # paste the deploy token (hidden prompt)
  gh secret set R2_ACCESS_KEY_ID     --repo $R --env $E          # paste that environment's R2 Access Key ID
  gh secret set R2_SECRET_ACCESS_KEY --repo $R --env $E          # paste that environment's R2 Secret Access Key
  KEY=$(openssl rand -hex 24)                                    # app key: new, random, never shown
  printf '%s' "$KEY" | gh secret set ONPATH_APP_KEYS --repo $R --env $E
  printf '\nonpath.mapKey.%s=%s\n' "$E" "$KEY" >> ~/.gradle/gradle.properties   # app builds on the laptop
  unset KEY
done
```
- GitHub → Settings → Environments → **production** → *Required reviewers*: `happychriss`; *Deployment branches*:
  `main` only. Same branch rule for **test**.
- App key rotation later: set `ONPATH_APP_KEYS` to `old,new`, deploy, ship the app with `new`, later set `new` only.
- APKs built by `release.yml` get no key yet (open decision, §8); laptop builds read it from `~/.gradle/gradle.properties`.

### Step 5 – First deploy and smoke test
1. Actions → **Maps deploy** → Run workflow → `test` (or push any change under `tools/cloud/`). The first run
   builds the image (~5 min), creates D1, deploys the Worker with the custom domain, creates the invoker key.
2. `curl https://maps-test.free-your-pictures.org/v1/health` → `{"ok":true,"env":"test",…}`
3. Full smoke test, **starts a real build of the Meran cell** (~5 min, first run longer: Planetiler sources and the
   Geofabrik extracts nord-est + austria are downloaded once into the bucket):
   ```bash
   ONPATH_APP_KEY=$(grep '^onpath.mapKey.test=' ~/.gradle/gradle.properties | cut -d= -f2) \
     tools/cloud/worker/smoke.sh https://maps-test.free-your-pictures.org
   ```
   Then poll `…/v1/cells/status?ids=10-543-361` (with the key header) until `ready`; Cloud Run → Jobs shows the
   execution and its log.
4. Same for production when wanted (approve the run).

## 7. Files

| File | What |
| --- | --- |
| `tools/cloud/gcp-setup.sh` | One-time Google setup (Cloud Shell), idempotent |
| `tools/cloud/Dockerfile.job` (+ `.dockerignore`), `git-shim.sh` | Job image = zo image + `tools/` + entry point |
| `tools/cloud/job.yaml` | Cloud Run Job definition (filled by the workflow, `gcloud run jobs replace`) |
| `tools/cloud/job_build.py`, `r2.py`, `src_objects.py` | Entry point: sources, `build_cell.sh`, upload, status files; stdlib S3 client; extract locks via the GCS API (§7f). Also usable on zo with `R2_LOCAL_DIR` |
| `tools/cloud/osm_sources.py`, `sources.json` | Extract choice from the Geofabrik index (§7e); pinned extracts (none today) + source limits |
| `tools/cloud/test_cloud.py` (+ `testdata/`) | Builder tests (SigV4 vectors, extract choice on a Geofabrik index subset, downloads/locks/limits, build/upload/status flow with a fake build) |
| `tools/cloud/worker/` | Worker (`src/`), D1 migrations, `wrangler.toml` (test/production), tests (`node --test`), `smoke.sh` |
| `.github/workflows/maps-deploy.yml` | Tests, then deploy test / production |
| `app/…/data/MapServer.kt` | Server URL + key per build type, shown in Settings → Diagnostics |

Local checks: `cd tools/cloud && python3 -m unittest test_cloud`; `cd tools/cloud/worker && node --test`
(Node ≥ 22.13, no npm install); `tools/cloud/worker/smoke.sh` (local `wrangler dev`, needs npm access).

## 7b. First cloud builds (2026-10-21, test)

Cells ship **only in format 2** (map-cells.md Q11): `FORMATS = "2"` in `wrangler.toml` (test and production, also
the code default), `FORMAT=2` in `job.yaml` / `job_build.py`; `/v1/health` → `"formats":[2]`, `?format=1` → 404.
The format-1 code paths stay (cost nothing); no app uses f1 cells.

- 2026-10-20 (overnight, format 1): 10/543/361, 28 MB – the first ever cloud build, sources copied into the bucket.
- 2026-10-21, format 2, one request (reason `point`) for 10/543/361 + 10/544/361 → one execution (2 vCPU, 8 GiB,
  europe-west1), sources already in the bucket: requested 07:02:34 UTC, first cell `ready` 07:07 (~4¾ min incl.
  start + source copy), second 07:10 (**~8 min for both**). Build times (`build.json`): 174 s (incl. renumber
  11 s) and 158 s; slowest steps base 54–56 s (peak 0.82 GB), terrain 32–34 s (AWS downloads), overlays 18–19 s
  + tippecanoe 9 s, contours 14–16 s. ~25 % slower than the laptop (129 / 137 s, map-cells.md §18).
- Manifests `format: 2`, `pipeline: git:2a1a1c6`, sha256 of every downloaded file OK. Sizes **9.31 MB** and
  **7.08 MB** – file by file the same as the local format-2 build (base 3.56 / 2.63, overlays 2.77 / 2.25, terrain
  1.02 / 1.01, contours 0.69 / 0.63, pois 0.63 / 0.26, addresses 0.63 / 0.30 MB).
- `seam_check.py` on the downloaded cells (border 11.25): 0 shared tiles in all four pmtiles, 0 POIs / house
  numbers in both; overlays paths 79/79, trails 28/28, ways 49/49, routes 64/64, base transportation 24/24,
  waterway 15/15 joined with equal attributes; contours 323 of 412 within 3 units (the same per-tile tracing offset
  as locally) – identical to the local check.

## 7c. Content schema – rebuilding cells for a newer style (2026-10-21)

Field report: the 09:11 format-2 cells had no overlays layer `streets` (added 10:15), the style draws small streets
at z13+ only from it → no small streets on the phone. Versions were named by the OSM date alone, so a rebuild on the
same OSM date would have had the same (immutable, cached) name. Now:
- **Builder:** version folder = `<osm_date>-s<schema>` (`tools/cells/cells.py CELL_SCHEMA`, now 2); the manifest has
  `version` and `schema`.
- **Doorman:** `CELL_SCHEMA` (wrangler.toml, = builder's). A request for a published cell of an older schema queues a
  rebuild with the asked priority; while it builds, such a cell is answered as `queued` / `building` (not `ready`).
  A cell that is only OSM-old is still answered `ready` at once, with `"updating": true` while its refresh builds.
- **App:** `CellApi.CELL_SCHEMA`; installed cells of an older schema are requested again at every start
  (`CellTriggers.updateOutdated`, reason `UPDATE`, sent as `point`, allowed on mobile data); the manifest's `version`
  is the installed version (older manifests: `osm_date`).
- **Bump all three** whenever the style needs data older cells lack. Old apps (before this) still work: delete the
  area in Settings → Offline maps and it is loaded again (the doorman rebuilds it first). The app shows the
  rebuild as `CellWhy.UPDATE` ("Updating the map for the new map style").
- **Schema 4 (2026-10-23, POI priority `pr` in `pois.geojson`) is a look-only bump:** builder and doorman go to 4, the
  app stays at 3 (s3 cells work, same-kind POIs just tie). Doorman `CELL_SCHEMA_SERVE` = 3: a published cell of schema
  ≥ 3 stays `ready` (+ `updating`) while its s4 rebuild runs, queued at refresh priority; below 3 as before (not
  served, asked priority). Phones get s4 through the normal refresh / after-routing check.
- **Schema 5 (2026-10-26, router classes from the tuned bike profiles: `cf` from touring.brf, `ct` from the tuned
  trekking.brf) is look-only too:** same pattern – builder and doorman 5, `CELL_SCHEMA_SERVE` and the app stay at 3.

## 7d. Released APKs, loading overlay, fresh-install reset (2026-10-21, cloud)

- **Release APK → test server with key:** `release.yml` builds the debug variant (fixed test signing key) in GitHub
  environment `test`; the app key is the newest entry of secret `ONPATH_APP_KEYS` (`${KEYS##*,}`, masked) passed as
  `ONPATH_MAP_KEY_TEST`. Rotation: put "old,new" in the secret → deploy doorman (accepts both) → release → later drop old.
  Production (`assembleRelease` + production key) comes with Play.
- **Overlay (owner):** while areas are built, downloaded, wait for the mobile-data answer, are offline or failed, a
  modal window (`ui/CellCard.kt MapLoadOverlay`, not dismissable by back/outside) shows title, why, progress, rows,
  *Wait for Wi-Fi / Use mobile data* and only **Cancel** (+ Retry on a retryable failure). Cancel stops the job's
  cells; installed ones stay. Once the question is answered "wait for Wi-Fi", the overlay closes and the plan-sheet
  card shows the Wi-Fi note. Screenshots: `MapLoadOverlayTest`.
- **Fresh install simulation:** Settings → Diagnostics → "Reset maps (like a fresh install)" (`AppViewModel.resetMapsLikeFreshInstall`):
  cancels all, deletes installed cells, resets the T3/T5 memory; the next start / point loads as on a new phone.
  JVM test: `CellDownloadTest.freshInstallAndResetLoadTheMapAgain`.

## 7e. Source data for any area (2026-10-21)

Field test: a point outside South Tyrol gave "source data not available". Now every cell is built:
- **Choice** (`tools/cloud/osm_sources.py`, standard library only): bbox B = cell + 5 km. (1) A *pinned* extract
  (`sources.json` "sources", cover rectangles) wins when it contains the cell – **none today**: the Trentino-Alto
  Adige pin was removed (owner 2026-10-21), South Tyrol uses the automatic choice like everywhere else (Meran →
  nord-est + austria, the buffer crosses the main ridge; Bozen → nord-est). Cells already built from the Trentino
  extract stay as they are until their normal refresh (osm_date older than `REFRESH_DAYS` 35, asked for again) – no
  schema bump, the content is the same kind. `osm/trentino_alto_adige.osm.pbf` in the source bucket is unused and
  can be deleted by hand. (2) Geofabrik *country leaves* – regions with a .pbf, no sub-regions, ISO code on the
  region or a parent (italy/nord-est, austria, switzerland, germany/bayern/oberbayern, us/new-york …; never alps,
  dach, britain-and-ireland). A leaf that holds most of another candidate is a container and is dropped ("us": the
  states' parent is north-america). One leaf contains B → that one (the smallest). Else the 1–4 leaves touching B,
  smallest first (border cell; Innsbruck reaches the Brenner → nord-est + austria, Scuol → 4). (3) More than 4, or
  none of them: the smallest leaf of any kind containing B (alps, great-britain …). (4) Nothing → open sea, a
  permanent failure. Tests on a 24-region subset of the real index (`testdata/`).
- **Cut** (`build_cell.sh CUT=raw`): each extract is cut raw (`osmium extract -s smart`, ~4.0 GB peak for any
  extract – the bit sets span the OSM id range; trentino 8 s, alps 2.3 GB 62 s locally), several cuts are merged
  and `osmium time-filter` keeps one version per object (extracts of different days), then only the small cut is
  renumbered (~2 s, ~150 MB). Whole-extract renumbering would need ~1 GB RAM per 1 GB extract (alps: 2.5 GB, 221 s,
  4.3 GB of files) and cannot merge two extracts. zo keeps `CUT=renumbered` (`run.sh`, 3 GB limit).
- **Cache** in the source bucket: `osm/geofabrik/<path>.osm.pbf` + `.meta.json` (download time, size, seconds),
  `osm/geofabrik/index-v1.json` (weekly). Downloads stream straight into the bucket mount
  (no RAM copy) and the cut reads the extract from there. `<extract>.lock` (O_EXCL) – a second job waits for the
  first one's download (stale after 45 min). Older than `max_age_days` (30) → downloaded again.
- **Cost guards:** `max_extract_mb` (2500) refuses huge extracts for good; the per-extract lock keeps two jobs
  from downloading the same file; the doorman's execution limits stay. The daily download limit (10 new extracts
  per day, with a "deferred" failure the doorman counted no attempt for) was **removed** (owner 2026-10-21) – for
  now; `osm/downloads.json` is no longer written.
- **Doorman:** no coverage check any more (no `sources.json` in the Worker). ETA: `+NEW_AREA_S` (90) for a cell
  whose z7 block (8 × 8 cells) has no built/building cell yet; while the builder downloads, its own estimate
  (`source_eta_s` in the status file, step `download`) replaces the guess.
- Planetiler's sources (water polygons, Natural Earth, lake centerlines) are global – nothing per area.

First builds on test (2026-10-21, pipeline 473898d, all format 2, manifests + sizes checked via the API):

| Cell | Extracts | Request → ready | Build (`build.json`) | Cut (peak) | Size |
| --- | --- | --- | --- | --- | --- |
| 10/544/359 Innsbruck | nord-est + austria, **both new** (download ~80 s for both) | 19:29:16 → ~19:40 (~11 min incl. start) | 366 s | 36 s + 48 s (3.1 / 3.9 GB), merge 4 s, renumber 2 s | 11.9 MB |
| 10/547/358 Kitzbühel (same execution) | austria (cached) | → 19:45 | 301 s | 47 s (3.9 GB) | 10.4 MB |
| 10/534/361 Grindelwald (next execution) | switzerland, new | 19:30:23 queued → 19:49 | 242 s | 30 s (3.9 GB) | 10.1 MB |
| 10/553/364 Ljubljana | slovenia, new (download 19:53:45–19:54:05, **~20 s**) | 19:53:00 → 19:59:03 (**6 min**) | 291 s | 20 s (3.9 GB) | 13.6 MB |

Downloads from Geofabrik into the bucket are fast (tens of seconds); the raw cut adds 20–85 s per cell compared to
the old renumbered cut. No OOM at 8 GiB with the 3.9 GB cut + 1.4 GB Planetiler sources in memory. Step progress
(`job_build.py STEP_PROGRESS`) and `AVG_CELL_S` (330) follow these timings.

## 7f. Priority, parallel builds, live ETA (2026-10-21)

Field test: Hilden (first German cell) showed "about 30 min" – one builder, busy with a background build, two more
queued. Owner decision: up to 4 builds at once, waiting users first, an honest ETA.

- **Priority** (`queue.js PRIORITY`, lower = sooner, FIFO within one): someone **waits** = `point` 0 (point,
  search result, app start T3, confirmed tap, schema update) and `near` 1 (their close neighbours); **background** =
  `corridor` 2, `ahead` 2 (T5, the next cell on tour – the app still sends `near` for it until it is changed after
  the production deploy), `refresh` 3, `test` 4 (smoke tests). A cell asked for again keeps the better priority.
- **Parallel** (`PARALLEL_BUILDS`, default 4; "1" = old behaviour): the cron pump (and every request) starts
  executions until all slots are busy. Waiting cells are spread over the free slots (`ceil(waiting / free)` cells
  per execution, at most `CELLS_PER_EXECUTION`) – 3 points → 3 executions of 1 cell, built at the same time.
  Background cells are batched (4 per execution) and **never take the last free slot** (they start only while ≥ 2
  slots are free) and only within `MAX_EXEC_PER_HOUR/DAY` minus a reserve of `min(slots, hour/2)` starts – a waiting
  request never waits behind a refresh. Separate Cloud Run executions of the one job: no IAM change needed (the
  doorman's role already has `run.jobs.runWithOverrides`). If Google refuses a start while others run (quota),
  the pump drops to **one builder for 30 min** (`meta` row `parallel-blocked`, `/v1/health` → `slots`).
- **Cost guards raised** with the parallelism: test 16 executions/hour, 60/day; production 16/80 (was 4/20, 6/40).
  Worst case 4 × 2 vCPU × 8 GiB for an hour ≈ 0.8 USD.
- **Extract lock across executions:** parallel executions run on different machines, each with its own gcsfuse
  mount – O_EXCL there is not atomic across machines (a new file stays local until closed) and the stat cache can
  hide another execution's fresh download for up to a minute. On Cloud Run (`SRC_BUCKET` set) the builder therefore
  locks and checks freshness through the **GCS JSON API** (`tools/cloud/src_objects.py`): the lock object is
  created with `ifGenerationMatch=0` (exactly one wins), existence/size/meta come from the API. The downloader writes
  its ETA into the lock; waiting executions report it (`source_eta_s`). Same service account permission as before
  (`roles/storage.objectUser` on the bucket). Locally / zo: O_EXCL files as before.
- **Live ETA** (`worker/src/eta.js`), recomputed on every status call: a simulation of the pump –
  (1) every running execution: its cells one after the other, remaining = the builder's step progress × build
  estimate (+ its own download ETA while it downloads; startup left before its first status);
  (2) the queue in pump order on the slots as they free (+ `PUMP_LAG_S` 30 for the minute cron, hourly cost guard
  respected): start + `STARTUP_S` + per cell the extract wait + build estimate. **Extract wait**: none when a cell
  in the 3 × 3 cells around it is built or building (cached); else the measured download estimate (= typical
  extract MB ÷ measured MB/s + 15 s; Geofabrik's index has no sizes, so the size is the mean of real downloads) –
  only once per area, later cells there wait for the same download. **Build estimate**: rolling mean of the last
  `ETA_SAMPLES` (10) real builds per cache state (new extract / cached), from table `builds` – the builder reports
  `build_s` (build + upload, without download), `new_source`, `download_s`, `download_mb` in the ready status;
  fallbacks `AVG_CELL_S` / `NEW_AREA_S` until there are measurements.
- **Smooth:** the reported finish time per cell (`cells.eta_at`) moves towards a new estimate by at least a quarter
  per answer and fully after `ETA_SMOOTH_S` (60 s) – no jumps, but no stale numbers either; a finish time already
  passed is replaced at once; never below 20 s.

Proof on test (2026-10-21, pipeline 79b1500, no measured builds yet → fallback times):
- Round 1, all slots idle: refresh Bonn 10/532/344 asked 20:42:05, then 3 points around Münster (10/533/338,
  10/532/338, 10/533/339) 20:42:06 → **4 executions running at once** (the refresh's own pump started it while
  every slot was idle). Reported ETA 510 s (→ 20:50:36); ready 20:47:19, 20:48:21, 20:48:21 (5.2–6.3 min – the
  fallback guesses are pessimistic until real builds are measured); Bonn: ETA 539 s → 20:51:04, ready 20:50:25.
  The three Münster cells share an extract: one downloaded, the others reported `download` (waiting on the lock)
  and went on afterwards.
- Round 2, all 4 slots busy: refresh Aachen 10/529/343 asked 20:44:07, point Dortmund 10/533/340 20:44:28 → the
  point was position 1 at once, **started first** when the first slot freed (20:47:07); the refresh started only
  when two slots were free (20:48:09). Point: ETA at request 699 s → 20:56:07, **ready 20:56:26** (last poll;
  between 20:55:55 and 20:56:26). Bonn at 20:44:31: ETA 343 s → 20:50:14, ready 20:50:15. The Ruhr cell built
  ~3 min slower than average (dense area) – the step progress showed it, the rolling mean learns.
- Fixed after the proof: a cell the builder had published but the pump not yet recorded showed "starting" again
  for up to a minute; now `upload`, progress 1, ~30 s.

## 7g. Storage cleanup and manifest sources (2026-10-22, cloud)

- **Daily cleanup** (Worker cron, 03:17 UTC, `tools/cloud/worker/src/cleanup.js`): format-1 cells are deleted for good
  (`cells/f1/…`, `status/f1/…`, D1 rows `format = 1` – the app reads format 2 only); a format-2 cell keeps its current
  version (`current.json`) and the previous one, older versions go once their newest object is older than 7 days
  (an app that read the old manifest just before can finish its download). At most 1000 objects per run, the rest the
  next day. Logged as `{"ev":"cleanup", f1Objects, f1Rows, oldVersions, oldObjects}`.
- **Unused Trentino extract** in the source bucket (since the Geofabrik choice, 2026-10-21) – the Worker cannot reach
  GCS; delete once by hand (Cloud Shell, owner):
  `gsutil rm gs://onpath-maps-onpath-sources/osm/trentino_alto_adige.osm.pbf` (bucket = `<project>-onpath-sources`, see
  gcp-setup.sh; `gsutil ls gs://onpath-maps-onpath-sources/osm/` first).
- **Manifest `sources`**: `[{id, date, url}]` of the OSM extracts the cell was cut from (job → `SRC_META` →
  build_cell.sh → `cells.py manifest --sources`); debugging aid, the app ignores it.

## 8. Open points

- **Production trigger on release:** a release created by `release.yml` with `GITHUB_TOKEN` does not trigger other
  workflows, so "deploy production on release" would need a PAT or `workflow_run`; for now production is by hand.
- Cells unused for 12 months are not removed (old versions are, §7g).
- Format 1 cells built before the switch (test: 10/543/361 under `cells/f1/…`, D1 rows with format 1) are no
  longer served; they can be deleted from the bucket by hand.
- Not verified against the real services (no accounts used while writing): `gcloud run jobs replace` of `job.yaml`
  (gcsfuse volume syntax), the custom-role permission names, the Cloudflare token permission list, the first
  custom-domain deploy. The first owner run of steps 2–5 is the real test; fixes are small and local.
