import { markStepClick, measureStepPaint } from './step-marks';

describe('step marks (W7-T02)', () => {
  beforeEach(() => {
    performance.clearMarks();
    performance.clearMeasures();
  });

  it('produces a step:<name> measure after a click-then-render sequence', () => {
    markStepClick('simulation');
    expect(performance.getEntriesByName('step:simulation:click', 'mark')).toHaveLength(1);

    measureStepPaint('simulation');

    const measures = performance.getEntriesByName('step:simulation', 'measure');
    expect(measures).toHaveLength(1);
    expect(measures[0].duration).toBeGreaterThanOrEqual(0);
  });

  it('carries the trace id of the step request as the join key of the gateway logs', () => {
    markStepClick('recapitulatif');
    measureStepPaint('recapitulatif', 'a1b2c3d4');

    const [measure] = performance.getEntriesByName('step:recapitulatif', 'measure');
    const detail = (measure as PerformanceMeasure).detail as {
      clickMark: string;
      traceId: string | null;
    };
    expect(detail.traceId).toBe('a1b2c3d4');
    expect(detail.clickMark).toBe('step:recapitulatif:click');
  });

  it('measures nothing when the step was entered without a submit', () => {
    measureStepPaint('confirmation');
    expect(performance.getEntriesByName('step:confirmation', 'measure')).toHaveLength(0);
  });

  it('does not measure the same transition twice', () => {
    markStepClick('souscripteur');
    measureStepPaint('souscripteur');
    measureStepPaint('souscripteur');
    expect(performance.getEntriesByName('step:souscripteur', 'measure')).toHaveLength(1);
  });
});
