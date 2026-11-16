/**
 * W7-T02: step transition instrumentation.
 *
 * The three local server hops of the legacy wizard (`souscripteur`, `simulation`,
 * `recapitulatif` actions) became a click handler, a gateway call and a client render. The budget
 * that replaces their server-side timing is measured here: `step:<name>:click` is marked when
 * the user submits a step, and `step:<name>` is measured when the destination step has painted,
 * so the p95 of the click-to-paint duration can be read from the User Timing entries.
 *
 * The measurement is correlated with the gateway access logs by the `traceparent` request
 * identifier (plan 37): the trace id is carried in the measure `detail`, which the telemetry
 * sink of plan 36 forwards alongside the entry name.
 */

/** Detail carried by a `step:<name>` measure, read by the telemetry sink (plan 36). */
export interface StepMeasureDetail {
  /** Mark the measure started from, kept so the sink reports the pair, not just the duration. */
  readonly clickMark: string;
  /** Trace id of the `traceparent` header of the step request, the join key of the gateway logs. */
  readonly traceId: string | null;
}

/** Name of the mark laid when the user submits `step`. */
export function stepClickMark(step: string): string {
  return `step:${step}:click`;
}

/** Marks the click that starts a step transition. */
export function markStepClick(step: string): void {
  performance.mark(stepClickMark(step));
}

/**
 * Measures the click-to-paint duration of `step`. A missing click mark means the step was
 * entered without a submit (direct URL, reload, back navigation): there is nothing to measure
 * and no entry is produced, rather than a throw inside a render hook.
 */
export function measureStepPaint(step: string, traceId: string | null = null): void {
  const start = stepClickMark(step);
  if (performance.getEntriesByName(start, 'mark').length === 0) {
    return;
  }
  const detail: StepMeasureDetail = { clickMark: start, traceId };
  performance.measure(`step:${step}`, { start, detail });
  performance.clearMarks(start);
}
