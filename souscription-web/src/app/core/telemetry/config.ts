/**
 * W7-T04: runtime configuration of the telemetry layer.
 *
 * Both values are resolved at bootstrap from files served next to the bundle, never baked into
 * it: `/version.json` (plan 03) carries the deployed release, and `/runtime-config.json` carries
 * the collector URL and the on/off switch. A deployment can therefore disable the whole
 * telemetry layer, or point it at another collector, without rebuilding the SPA.
 */

/** Effective telemetry settings. Defaults apply until `loadTelemetryRuntime` has resolved. */
export interface TelemetryConfig {
  /** Master switch: when false no payload is ever emitted. */
  readonly enabled: boolean;
  /** Collector endpoint the sink beacons to. */
  readonly collectorUrl: string;
  /** Deployed version reported with every payload (commit sha + build date). */
  readonly release: string;
  /** Build timestamp of the deployed artifact, as written by `scripts/write-version.mjs`. */
  readonly builtAt: string;
}

const DEFAULTS: TelemetryConfig = {
  enabled: true,
  collectorUrl: '/telemetry/v1/events',
  release: 'unknown',
  builtAt: 'unknown',
};

let current: TelemetryConfig = DEFAULTS;

/** Settings the sink and the service read on every send. */
export function telemetryConfig(): TelemetryConfig {
  return current;
}

/** Overrides part of the configuration. Used by `loadTelemetryRuntime` and by the tests. */
export function setTelemetryConfig(patch: Partial<TelemetryConfig>): void {
  current = { ...current, ...patch };
}

/** Restores the compiled-in defaults. */
export function resetTelemetryConfig(): void {
  current = DEFAULTS;
}

/** Shape of `/runtime-config.json`; every field is optional so a partial file still boots. */
interface RuntimeConfigFile {
  readonly telemetry?: { readonly enabled?: boolean; readonly collectorUrl?: string };
}

/** Shape of `/version.json`, written at build time by `scripts/write-version.mjs`. */
interface VersionFile {
  readonly release?: string;
  readonly builtAt?: string;
}

async function readJson<T>(url: string): Promise<T | null> {
  try {
    const response = await fetch(url, { cache: 'no-store' });
    return response.ok ? ((await response.json()) as T) : null;
  } catch {
    // An unreachable configuration file must not prevent the SPA from starting: the defaults
    // above keep error reporting alive with an `unknown` release rather than losing the signal.
    return null;
  }
}

/**
 * Bootstrap step: reads the release and the collector settings so 100% of the error reports
 * emitted afterwards carry the deployed version.
 */
export async function loadTelemetryRuntime(): Promise<void> {
  const [version, runtime] = await Promise.all([
    readJson<VersionFile>('/version.json'),
    readJson<RuntimeConfigFile>('/runtime-config.json'),
  ]);
  setTelemetryConfig({
    release: version?.release ?? DEFAULTS.release,
    builtAt: version?.builtAt ?? DEFAULTS.builtAt,
    enabled: runtime?.telemetry?.enabled ?? DEFAULTS.enabled,
    collectorUrl: runtime?.telemetry?.collectorUrl ?? DEFAULTS.collectorUrl,
  });
}
