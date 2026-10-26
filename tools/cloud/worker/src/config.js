// Settings from wrangler.toml [vars] (per environment) with safe defaults. All limits are cost guards
// (docs/design/map-hosting.md §Costs): the Worker never starts more builds than these allow, whatever the app asks.

const num = (v, d) => {
  const n = Number(v);
  return v === undefined || v === null || v === "" || !Number.isFinite(n) ? d : n;
};

export function config(env) {
  return {
    environment: env.ENVIRONMENT || "dev",
    formats: String(env.FORMATS || "2").split(",").map((s) => Number(s.trim())).filter((n) => n > 0),
    buildsEnabled: String(env.BUILDS_ENABLED ?? "true") === "true",
    gcpProject: env.GCP_PROJECT || "",
    gcpRegion: env.GCP_REGION || "",
    gcpJob: env.GCP_JOB || "",
    maxCellsPerRequest: num(env.MAX_CELLS_PER_REQUEST, 40),
    cellsPerExecution: num(env.CELLS_PER_EXECUTION, 4),
    // builds running at the same time (Cloud Run executions); one slot is always kept free for cells someone waits
    // for (map-hosting.md §7f). 1 = the old one-builder behaviour.
    parallelBuilds: Math.max(1, num(env.PARALLEL_BUILDS, 4)),
    maxExecPerHour: num(env.MAX_EXEC_PER_HOUR, 16),
    maxExecPerDay: num(env.MAX_EXEC_PER_DAY, 80),
    maxQueue: num(env.MAX_QUEUE, 300),
    maxAttempts: num(env.MAX_ATTEMPTS, 3),
    rateLimitPerMin: num(env.RATE_LIMIT_PER_MIN, 30),
    refreshDays: num(env.REFRESH_DAYS, 35),
    jobTimeoutS: num(env.JOB_TIMEOUT_S, 3600),
    avgCellS: num(env.AVG_CELL_S, 330), // raw cuts since 2026-10-21: 300–370 s per cell (map-hosting.md §7e)
    startupS: num(env.STARTUP_S, 90),
    // first cell in an area without a cached source extract: download (+ lock wait) – measured, map-hosting.md
    newAreaS: num(env.NEW_AREA_S, 90), // measured 2026-10-21: slovenia 20 s, nord-est + austria ~80 s
    etaSamples: num(env.ETA_SAMPLES, 10), // rolling average over the last N measured builds
    etaSmoothS: num(env.ETA_SMOOTH_S, 60), // an ETA moves fully to a new estimate only after this many seconds
    pumpLagS: num(env.PUMP_LAG_S, 30), // cron every minute: a freed slot is noticed after ~30 s on average
    buildRev: env.BUILD_REV || "",
    // content schema the current pipeline builds (tools/cells/cells.py CELL_SCHEMA); older published cells are
    // rebuilt when someone asks for them
    cellSchema: num(env.CELL_SCHEMA, 5),
    // older published cells of at least this schema are still served ("ready" + updating) while their rebuild runs:
    // the style works with them, they only look less good (4 = POI priority "pr"); below it the map lacks data
    cellSchemaServe: num(env.CELL_SCHEMA_SERVE, 3),
  };
}
