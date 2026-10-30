# Map hosting: Cloudflare doorman + R2, Google Cloud Run Jobs builder

Status: **owner decision.** Test environment deployed and used by the app (`maps-test…`, auto
deploy on push to `main`); **production not deployed yet** (owner: later).
Hosts the doorman and builder of `docs/design/map-cells.md` §5.
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
(Node ≥ 24 as in CI – 22.14 fails on node:sqlite parameters; no npm install); `tools/cloud/worker/smoke.sh` (local `wrangler dev`, needs npm access).

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

## 7c. Content schema – rebuilding cells for a newer style

Why: cells built before the overlays layer `streets` existed showed no small streets (the style draws them at z13+
only from it), and a version named by the OSM date alone would give a rebuild on the same OSM date the same
(immutable, cached) name. So:
- **Builder:** version folder = `<osm_date>-s<schema>` (`tools/cells/cells.py CELL_SCHEMA`, currently 7 – see below); the manifest has
  `version` and `schema`.
- **Doorman:** `CELL_SCHEMA` (wrangler.toml, = builder's). A request for a published cell of an older schema queues a
  rebuild with the asked priority; while it builds, such a cell is answered as `queued` / `building` (not `ready`).
  A cell that is only OSM-old is still answered `ready` at once, with `"updating": true` while its refresh builds.
- **App:** `CellApi.CELL_SCHEMA`; the manifest's `version` is the installed version (older manifests: `osm_date`).
  Installed cells of an older schema are renewed by the after-routing check (`CellTriggers.freshenAlong`; no idle
  refresh, owner).
- **Bump all three** whenever the style needs data older cells lack. An app that cannot ask for a rebuild still works: delete the
  area in Settings → Offline maps and it is loaded again (the doorman rebuilds it first). The app shows the
  rebuild as `CellWhy.UPDATE` ("Updating the map for the new map style").
- **Schema 4 (POI priority `pr` in `pois.geojson`) is a look-only bump:** builder and doorman go to 4, the
  app stays at 3 (s3 cells work, same-kind POIs just tie). Doorman `CELL_SCHEMA_SERVE` = 3: a published cell of schema
  ≥ 3 stays `ready` (+ `updating`) while its s4 rebuild runs, queued at refresh priority; below 3 not
  served (asked priority). Phones get s4 through the normal refresh / after-routing check.
- **Schema 5 (router classes from the tuned bike profiles: `cf` from touring.brf, `ct` from the tuned
  trekking.brf) is look-only too:** same pattern – builder and doorman 5, `CELL_SCHEMA_SERVE` and the app stay at 3.
- **Schema 6 ("quiet towns": town flag `u` on overlay paths / streets, link pieces `k` = "l" for path ends
  at hidden sidewalks / crossings, `ar` on pedestrian squares – docs/map-pipeline.md 5b) is look-only too:** builder
  (`cells.py`) and doorman (`wrangler.toml`, `config.js` default) 6, `CELL_SCHEMA_SERVE` and the app stay at 3. A cell
  without `u` keeps the plain town look. The rebuild also refreshes the router classes for the bike footway-cost
  fix (trekking / touring.brf: compacted counts as firm, cheap footway only without a bicycle tag).
- **Schema 7 (bike map from the Hilden field review: cycle routes only of the networks lcn / rcn / ncn /
  icn, label lines `lb` for named local cycle routes, `bs` on bicycle streets, `br` on bridges and `fw` on hidden
  footways in overlay `paths`, street areas (layer `areas`), sidewalk fragments hidden –
  docs/map-pipeline.md 5b) is look-only too:** builder and doorman 7, `CELL_SCHEMA_SERVE` and the app stay at 3. An s6
  cell just keeps the unsigned route lines and has no local route names, bicycle streets or footbridge flag; the
  `lb` pieces also carry `majoronly`, so a style from before s7 leaves them out of the band and the major layers.

## 7d. Released APKs, loading modal, fresh-install reset

- **Release APK → test server with key:** `release.yml` builds the debug variant (fixed test signing key) in GitHub
  environment `test`; the app key is the newest entry of secret `ONPATH_APP_KEYS` (`${KEYS##*,}`, masked) passed as
  `ONPATH_MAP_KEY_TEST`. Rotation: put "old,new" in the secret → deploy doorman (accepts both) → release → later drop old.
  Production (`assembleRelease` + production key) comes with Play.
- **Loading modal (owner):** while the user waits for the map of their area – `ui/MapLoad.kt`, rules in
  `docs/design/map-cells.md` §9 and `docs/design/map-updates-ux.md`. Screenshots: `MapLoadOverlayTest`.
- **Fresh install:** Settings → Offline maps → Clear all (core feature C14); the next start / point loads as on a new
  phone. JVM test: `CellDownloadTest.freshInstallAndResetLoadTheMapAgain`.

## 7e. Source data for any area

Every cell is built, wherever it is:
- **Choice** (`tools/cloud/osm_sources.py`, standard library only): bbox B = cell + 5 km. (1) A *pinned* extract
  (`sources.json` "sources", cover rectangles) wins when it contains the cell – **none today**: the Trentino-Alto
  Adige pin was removed (owner), South Tyrol uses the automatic choice like everywhere else (Meran →
  nord-est + austria, the buffer crosses the main ridge; Bozen → nord-est). Cells already built from the Trentino
  extract stay as they are until their normal refresh (osm_date older than `REFRESH_DAYS` 35, asked for again) – no
  schema bump, the content is the same kind. (2) Geofabrik *country leaves* – regions with a .pbf, no sub-regions, ISO code on the
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
  from downloading the same file; the doorman's execution limits stay. There is no daily download limit (owner, for
  now).
- **Doorman:** no coverage check (no `sources.json` in the Worker). ETA: `+NEW_AREA_S` (90) for a cell
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
a renumbered cut. No OOM at 8 GiB with the 3.9 GB cut + 1.4 GB Planetiler sources in memory. Step progress
(`job_build.py STEP_PROGRESS`) and `AVG_CELL_S` (330) follow these timings.

## 7f. Priority, parallel builds, live ETA

Why: with one builder, a waiting user's cell could sit behind background builds ("about 30 min"). Owner decision: up to 4 builds at once, waiting users first, an honest ETA.

- **Priority** (`queue.js PRIORITY`, lower = sooner, FIFO within one): someone **waits** = `point` 0 (point,
  search result, app start T3, confirmed tap, schema update) and `near` 1 (their close neighbours); **background** =
  `corridor` 2, `ahead` 2 (T5, the next cell on tour – the app still sends `near` for it until it is changed after
  the production deploy), `refresh` 3, `test` 4 (smoke tests). A cell asked for again keeps the better priority.
- **Parallel** (`PARALLEL_BUILDS`, default 4; "1" = one builder): the cron pump (and every request) starts
  executions until all slots are busy. Waiting cells are spread over the free slots (`ceil(waiting / free)` cells
  per execution, at most `CELLS_PER_EXECUTION`) – 3 points → 3 executions of 1 cell, built at the same time.
  Background cells are spread over the free slots but one (§7h; at most 4 per execution) and **never take the last free slot** (they start only while ≥ 2
  slots are free) and only within `MAX_EXEC_PER_HOUR/DAY` minus a reserve of `min(slots, hour/2)` starts – a waiting
  request never waits behind a refresh. Separate Cloud Run executions of the one job: no IAM change needed (the
  doorman's role already has `run.jobs.runWithOverrides`). If Google refuses a start while others run (quota),
  the pump drops to **one builder for 30 min** (`meta` row `parallel-blocked`, `/v1/health` → `slots`).
- **Cost guards** with the parallelism: test 16 executions/hour, 60/day; production 16/80.
  Worst case 4 × 2 vCPU × 8 GiB for an hour ≈ 0.8 USD.
- **Extract lock across executions:** parallel executions run on different machines, each with its own gcsfuse
  mount – O_EXCL there is not atomic across machines (a new file stays local until closed) and the stat cache can
  hide another execution's fresh download for up to a minute. On Cloud Run (`SRC_BUCKET` set) the builder therefore
  locks and checks freshness through the **GCS JSON API** (`tools/cloud/src_objects.py`): the lock object is
  created with `ifGenerationMatch=0` (exactly one wins), existence/size/meta come from the API. The downloader writes
  its ETA into the lock; waiting executions report it (`source_eta_s`). Same service account permission as before
  (`roles/storage.objectUser` on the bucket). Locally / zo: O_EXCL files.
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

## 7g. Storage cleanup and manifest sources

- **Daily cleanup** (Worker cron, 03:17 UTC, `tools/cloud/worker/src/cleanup.js`): format-1 cells are deleted for good
  (`cells/f1/…`, `status/f1/…`, D1 rows `format = 1` – the app reads format 2 only); a format-2 cell keeps its current
  version (`current.json`) and the previous one, older versions go once their newest object is older than 7 days
  (an app that read the old manifest just before can finish its download). At most 1000 objects per run, the rest the
  next day. Logged as `{"ev":"cleanup", f1Objects, f1Rows, oldVersions, oldObjects}`.
- **Manifest `sources`**: `[{id, date, url}]` of the OSM extracts the cell was cut from (job → `SRC_META` →
  build_cell.sh → `cells.py manifest --sources`); debugging aid, the app ignores it.

## 7h. Faster map refreshes: where the time goes, dev loop

**Field case:** the look-only bump s5 → s6 (overlays only) for 6 cells around Düsseldorf / Hilden took ~48 min from
the after-routing refresh (~09:45 UTC) to the map on the phone (10:34). From the R2 status files and `build.json`:
- the cells were queued at **refresh priority** (s5 ≥ `CELL_SCHEMA_SERVE`) = background → batched **4 per execution**
  and built one after the other: execution n4s2c built 4 cells 09:47:42 → 10:24:12, vrrlx 2 cells → 10:07:10;
- **8–10.5 min per cell** in the cloud (dense area; Meran 160–175 s): overlays 129–197 s, base 87–126 s, terrain
  63–125 s (AWS tiles, no cache on Cloud Run), pois / addresses 47–70 s each, cut + merge + renumber 25–35 s, upload ~4 s;
- the app saw "ready + updating" and waited its **15-min idle time** before asking again: ~10 min after the last build;
- download (126 MB) + join < 1 min.

The same cell (10-531-342) on donald with the job image (4 CPUs, low priority): 247 s cold (+ 89 s for the two
Geofabrik extracts), **179 s warm** (overlays 61 s, base 30 s, pois 22 s, addresses 23 s, terrain 20 s) – 2.5–3× faster
than Cloud Run's 2 vCPU. An overlays-only rebuild would take ~80 s here, ~4.2 min in the cloud (see backlog: partial
rebuild).

**App poll while updating:** a cell answered "ready + updating" counts as being built (`MapCells.updatingOnServer`):
the app asks again at the doorman's `poll_s`, and the doorman answers `poll_s` 15 while any asked cell is rebuilt
(with 60 the app waited its 15-min idle time). The new version reaches the phone within ~15 s of the build
(`CellDownloadTest.updatingCellIsPolledUntilTheRebuildIsDone`). Not on screen: unchanged (no polling).

**Background lane** (`queue.js startNext`): background cells (refresh, schema rebuild, corridor) are spread over the
free slots minus the one kept for someone waiting – 6 cells on 4 idle slots → 3 executions × 2 cells, built at the same
time (4 + 2 serially left the last Hilden cell 36 min after the start, this way ≈ 2 × 10 min); a long queue still goes 4 per
execution, so the hourly cap is not used up by small batches. `PARALLEL_BUILDS` / `MAX_EXEC_PER_HOUR` unchanged (the
Cloud Run CPU quota for more slots is not checked yet). Each extra execution costs ~1.5 min of start-up (≈ $0.003).

**Pre-warm** (`queue.js prewarm`, `PREWARM_DAYS` default 30, 0 = off): the first pump after a `CELL_SCHEMA` bump queues
every cell asked for in the last 30 days whose published version is of an older schema, once, at refresh priority
(meta row `prewarm-schema`; a full queue continues at the next pump). Phones then find the new map built instead of
waiting for it; cost ≈ $0.01 per cell. The first deploy of this code also pre-warms today's s < 6 cells.

**Dev loop – `tools/dev/push_cell.sh <cell>`** (pipeline work on donald, no cloud): builds the cell from the *current
working tree* with the cloud job image (`tools/dev/cell_lib.sh`: base image with make -j4, `docker run --cpus 4
--cpu-shares 64 --blkio-weight 10`, 8 GiB; work folder `~/onpath-cells-work`, not the repo), renames the version to
`<osm_date>-dev<MMDDHHMM>-s<schema>`, force-stops OnPath (refused while a tour records), pushes it as a complete
download (`cells/download/<cell>/<version>/`, via `<version>.part` + one rename) and starts OnPath: `MapCells.recover()`
joins it at start like a download finished before a crash. ~3–4 min per cell, the push + join ~12 s.
- adb-created folders belong to `shell` with mode 2770 – the app cannot even list them (first try on the Pixel 8: the
  cell was silently not joined); the script opens them (777 / 666), then the app joins, renames the address file and
  deletes the folder as usual.
- A dev version is never a server name (`CellApi.isDevVersion`), and a **refresh does not replace it for 24 h**
  (`MapCells.DEV_KEEP_MS` – the restored plan's route asks for a refresh at every start). After that the next refresh
  loads the server's version; at once: Settings → Offline maps → delete the area. Other cells are not touched.

**Fast lane – `tools/dev/publish_cells.sh <cells…>`** (owner: "build here and push to the cloud" – TEST
only): builds the cells from **origin/main** (`git archive`, a dirty working tree is refused, manifest pipeline =
the origin/main commit) with the same job image, ≤ 2 containers at once, and `job_build.py` uploads them to R2 exactly
like a Cloud Run execution (files → manifest → `current.json`, status file with execution `donald-…`). A cell whose
status file says a cloud job is building it (< 70 min) is skipped. `--dry-run` = everything but the upload.
- **Doorman "adopt"** (`queue.js adoptable` / `adoptQueued`): D1 does not know such a version. Before the doorman
  queues a build (request) and before it starts one (every pump, first 40 queued cells, also with builds off), it
  reads the cell's `current.json` + manifest; a version that is current – schema ≥ `CELL_SCHEMA`, OSM date younger than
  `REFRESH_DAYS`, other than the D1 row's – is taken as it is: row ready (`version`, `osm_date`, `size`), no build.
  Never while a Cloud Run job builds the cell (that job finishes and publishes its own). Logged as `{"ev":"adopted"}`.
- **Credentials:** one R2 API token, **Object Read & Write on bucket `onpath-maps-test` only** (owner, Cloudflare
  dashboard → R2 → Manage API tokens), in `~/.config/onpath/r2-test.env` on donald (0600, outside the repo; keys
  `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_JURISDICTION`), passed only as
  `docker --env-file` and never printed. The script refuses any other bucket, an `R2_ENDPOINT` override, or a file that
  is not 0600. No D1 or Cloudflare API token is needed (adoption happens in the Worker). Production stays cloud-only.
  Revoke: dashboard → R2 → Manage API tokens → delete the donald token.
- Expected: 6 dense cells ≈ 10 min (2 at once, ~3 min each warm) + upload (~130 MB); the phone loads them at its
  next refresh / request for them.
- **A whole area overnight – `tools/dev/publish_area.sh <dir>`** (owner 2026-10-30: all of Germany into TEST):
  `tools/dev/area_cells.py <natural_earth_vector.sqlite> DEU [--packages]` lists the z10 cells touching the country
  (Natural Earth 10 m admin-0 + 2 km: border cells in, open sea out – Germany 708 cells, 17 region packages) into
  `<dir>/cells.txt` / `packages.txt`. The runner pins origin/main, asks the bucket which ids are already current
  (`area_state.py` in the job image – resume after a crash or stop), then builds cells (grouped by z7 block), then
  packages: up to `PARALLEL` containers of `BATCH` ids each; a container starts only while the RAM left after its cut
  peak (and the running ones' growth to theirs) stays ≥ 6 GB; a failed id is tried once more, then listed in
  `failed.txt`. One-line `<dir>/status.txt` (done / failed / left / ETA), log `<dir>/run-YYYYMMDD.log`. Run as a user
  unit, e.g. `systemd-run --user --unit onpath-germany --on-calendar='… 19:00' -p Nice=19 -p IOSchedulingClass=idle
  tools/dev/publish_area.sh ~/onpath-cells-work/germany`; `systemctl --user stop onpath-germany` kills its containers.
  Probe on donald (pipeline 32ac3de, warm): Berlin 10/550/335 159 s 23 MB, Passau 10/550/353 (niederbayern + austria)
  93 s 10 MB, Seenplatte 10/548/331 61 s 4 MB; peak 4.0 GB (the raw cut); the doorman adopted all three at the first request.

## 7i. Region packages (z7)

The region view below z10 (main roads, towns, forests, rivers, relief, long-distance routes at z7–9) comes in
**region packages**: one per web-mercator **z7 tile** (8 × 8 cells, ~200 km), holding only map tiles z7–9 under it.
The doorman treats them like cells – same D1 table, queue, priorities, ETA, status files and R2 layout:

| | Cell | Region package |
| --- | --- | --- |
| Id | `10-x-y` (`10/x/y`) | `7-x-y` (`7/x/y`), e.g. `7-66-42` Düsseldorf; any other zoom → 400 (`cells.js` `PACKAGE_Z`, `isPackage`) |
| R2 | `cells/f2/10/x/y/<version>/…`, `status/f2/10-x-y.json` | `cells/f2/7/x/y/<version>/…`, `status/f2/7-x-y.json` |
| Manifest | `GET /v1/cells/10/x/y/manifest` | `GET /v1/cells/7/x/y/manifest` |
| Version | `<osm_date>-s<CELL_SCHEMA>` | `<osm_date>-s<PACKAGE_SCHEMA>` – its own content schema (`wrangler.toml` `PACKAGE_SCHEMA`, default 1) |
| Older schema | rebuilt when asked; served meanwhile from `CELL_SCHEMA_SERVE` on | rebuilt when asked; any older version is served meanwhile |
| Pre-warm (§7h) | after a `CELL_SCHEMA` bump | not pre-warmed – rebuilt when the app asks again |

The doorman never queues a package by itself: the app asks for the packages under the cells it requests (and installed
cells' packages), in separate calls with `reason: "refresh"` (priority 3 – background, never ahead of someone
waiting). Adoption / the fast lane (§7h) works the same: a current package published into R2 outside a job is taken
as it is. Mixing cells and packages in one request is allowed.

## 8. Open points

- **Production trigger on release:** a release created by `release.yml` with `GITHUB_TOKEN` does not trigger other
  workflows, so "deploy production on release" would need a PAT or `workflow_run`; for now production is by hand.
- Cells unused for 12 months are not removed (old versions are, §7g).
- Format 1 cells built before the switch (test: 10/543/361 under `cells/f1/…`, D1 rows with format 1) are no
  longer served; they can be deleted from the bucket by hand.
- Not verified against the real services (no accounts used while writing): `gcloud run jobs replace` of `job.yaml`
  (gcsfuse volume syntax), the custom-role permission names, the Cloudflare token permission list, the first
  custom-domain deploy. The first owner run of steps 2–5 is the real test; fixes are small and local.
