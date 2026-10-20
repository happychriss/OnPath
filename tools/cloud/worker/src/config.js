// Settings from wrangler.toml [vars] (per environment) with safe defaults. All limits are cost guards
// (docs/design/map-hosting.md §Costs): the Worker never starts more builds than these allow, whatever the app asks.

const num = (v, d) => {
  const n = Number(v);
  return v === undefined || v === null || v === "" || !Number.isFinite(n) ? d : n;
};

export function config(env) {
  return {
    environment: env.ENVIRONMENT || "dev",
    formats: String(env.FORMATS || "1").split(",").map((s) => Number(s.trim())).filter((n) => n > 0),
    buildsEnabled: String(env.BUILDS_ENABLED ?? "true") === "true",
    gcpProject: env.GCP_PROJECT || "",
    gcpRegion: env.GCP_REGION || "",
    gcpJob: env.GCP_JOB || "",
    maxCellsPerRequest: num(env.MAX_CELLS_PER_REQUEST, 40),
    cellsPerExecution: num(env.CELLS_PER_EXECUTION, 4),
    maxExecPerHour: num(env.MAX_EXEC_PER_HOUR, 6),
    maxExecPerDay: num(env.MAX_EXEC_PER_DAY, 40),
    maxQueue: num(env.MAX_QUEUE, 300),
    maxAttempts: num(env.MAX_ATTEMPTS, 3),
    rateLimitPerMin: num(env.RATE_LIMIT_PER_MIN, 30),
    refreshDays: num(env.REFRESH_DAYS, 35),
    jobTimeoutS: num(env.JOB_TIMEOUT_S, 3600),
    avgCellS: num(env.AVG_CELL_S, 240),
    startupS: num(env.STARTUP_S, 90),
    buildRev: env.BUILD_REV || "",
  };
}
