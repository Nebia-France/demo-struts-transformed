import type { SimulationResult, SubscriberInput } from '../api/models';
import { simulationPatch, subscriberPatch } from './repopulate';

const SUBSCRIBER: SubscriberInput = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.com',
  birthDate: '1985-03-14',
};

const SIMULATION: SimulationResult = {
  initialAmount: '2500.00',
  monthlyAmount: '250.00',
  durationYears: 12,
  contributed: '38500.00',
  finalCapital: '42000.00',
};

describe('repopulate', () => {
  it('maps a null draft to empty strings, never null on screen', () => {
    expect(subscriberPatch(null)).toEqual({
      firstName: '',
      lastName: '',
      email: '',
      birthDate: '',
    });
    expect(simulationPatch(null)).toEqual({ initial: '', monthly: '', years: '' });
  });

  it('maps a draft without subscriber or simulation to empty strings', () => {
    const empty = { subscriber: null, simulation: null };
    expect(subscriberPatch(empty)).toEqual({
      firstName: '',
      lastName: '',
      email: '',
      birthDate: '',
    });
    expect(simulationPatch(empty)).toEqual({ initial: '', monthly: '', years: '' });
  });

  it('returns the stored subscriber verbatim, without reformatting or trimming', () => {
    const stored: SubscriberInput = { ...SUBSCRIBER, firstName: ' Camille ' };
    expect(subscriberPatch({ subscriber: stored, simulation: null })).toEqual({
      firstName: ' Camille ',
      lastName: 'Durand',
      email: 'camille.durand@example.com',
      birthDate: '1985-03-14',
    });
  });

  it('returns the stored amounts verbatim and stringifies the duration', () => {
    expect(simulationPatch({ subscriber: SUBSCRIBER, simulation: SIMULATION })).toEqual({
      initial: '2500.00',
      monthly: '250.00',
      years: '12',
    });
  });

  it('keeps a zero duration as "0" instead of an empty string', () => {
    expect(simulationPatch({ subscriber: null, simulation: { ...SIMULATION, durationYears: 0 } }).years).toBe('0');
  });
});
