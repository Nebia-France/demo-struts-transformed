import { TestBed } from "@angular/core/testing";
import type { ProductDetail, SubscriptionConfirmation } from "../api/models";
import { clearDraftRef, readRawDraftRef } from "./draft-storage";
import { DRAFT_WINDOW_MS } from "./draft-window";
import { DraftStore } from "./draft.store";

const PRODUCT: ProductDetail = {
  code: "PRUDENT",
  name: "Horizon Prudent",
  profile: "Prudent",
  description: "Support sécurisé.",
  annualRate: "0.020000",
};

const DRAFT_ID = "7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
const KEY = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";

const CONFIRMATION: SubscriptionConfirmation = {
  reference: "S-2024-0001",
  product: {
    code: PRODUCT.code,
    name: PRODUCT.name,
    profile: PRODUCT.profile,
    annualRate: PRODUCT.annualRate,
  },
  subscriber: {
    firstName: "Camille",
    lastName: "Durand",
    email: "camille.durand@example.com",
    birthDate: "1985-03-14",
  },
  simulation: {
    initialAmount: "1000.00",
    monthlyAmount: "100.00",
    durationYears: 10,
    contributed: "13000.00",
    finalCapital: "14000.00",
  },
  createdAt: "2024-05-01T10:00:00Z",
};

describe("DraftStore", () => {
  let store: DraftStore;

  beforeEach(() => {
    clearDraftRef();
    TestBed.configureTestingModule({});
    store = TestBed.inject(DraftStore);
  });

  it("start() opens a draft", () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    expect(store.hasDraft()).toBe(true);
    expect(store.draftId()).toBe(DRAFT_ID);
    expect(store.confirmationKey()).toBe(KEY);
    expect(store.hasSubscriber()).toBe(false);
    expect(store.hasSimulation()).toBe(false);
  });

  it("hasSubscriber and hasSimulation follow the applied draft", () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    store.apply({
      draftId: DRAFT_ID,
      product: PRODUCT,
      subscriber: CONFIRMATION.subscriber,
      confirmationKey: KEY,
      status: "SUBSCRIBER_CAPTURED",
    });
    expect(store.hasSubscriber()).toBe(true);
    expect(store.hasSimulation()).toBe(false);

    store.apply({
      draftId: DRAFT_ID,
      product: PRODUCT,
      subscriber: CONFIRMATION.subscriber,
      simulation: CONFIRMATION.simulation,
      confirmationKey: KEY,
      status: "SIMULATED",
    });
    expect(store.hasSimulation()).toBe(true);
  });

  it("clear() resets everything", () => {
    store.start(PRODUCT, DRAFT_ID, KEY);
    store.clear();

    expect(store.hasDraft()).toBe(false);
    expect(store.draftId()).toBeNull();
    expect(store.confirmationKey()).toBeNull();
    expect(store.completedReference()).toBeNull();
    expect(readRawDraftRef()).toBeNull();
  });

  it("complete() clears the draft but keeps the reference and the key", () => {
    store.start(PRODUCT, DRAFT_ID, KEY);
    store.complete(CONFIRMATION);

    expect(store.hasDraft()).toBe(false);
    expect(store.draftId()).toBeNull();
    expect(store.completedReference()).toBe("S-2024-0001");
    expect(store.completedKey()).toBe(KEY);
    expect(readRawDraftRef()).toBeNull();
  });

  it("persists no personal field: the persisted payload is exactly draftId and createdAt", () => {
    store.start(PRODUCT, DRAFT_ID, KEY);
    store.apply({
      draftId: DRAFT_ID,
      product: PRODUCT,
      subscriber: CONFIRMATION.subscriber,
      simulation: CONFIRMATION.simulation,
      confirmationKey: KEY,
      status: "SIMULATED",
    });

    const raw = readRawDraftRef() ?? "";
    expect(
      Object.keys(JSON.parse(raw) as Record<string, unknown>).sort(),
    ).toEqual(["createdAt", "draftId"]);
    expect(raw).not.toContain("Camille");
    expect(raw).not.toContain(KEY);
  });

  describe("draft window (former <session-timeout>20</session-timeout>)", () => {
    afterEach(() => vi.useRealTimers());

    const startAt = (epoch: number) => {
      vi.useFakeTimers();
      vi.setSystemTime(epoch);
      store.start(PRODUCT, DRAFT_ID, KEY);
    };

    it("keeps the draft just inside the window", () => {
      startAt(1_000_000);

      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS - 1);
      expect(store.hasDraft()).toBe(true);
    });

    it("drops the draft at and after the window", () => {
      startAt(1_000_000);

      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS);
      expect(store.hasDraft()).toBe(false);

      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS + 60_000);
      expect(store.hasDraft()).toBe(false);
    });

    it("expireIfStale() clears the stored reference so a reload cannot resurrect the journey", () => {
      startAt(1_000_000);

      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS - 1);
      expect(store.expireIfStale()).toBe(false);
      expect(readRawDraftRef()).not.toBeNull();

      vi.setSystemTime(1_000_000 + DRAFT_WINDOW_MS);
      expect(store.expireIfStale()).toBe(true);
      expect(store.hasDraft()).toBe(false);
      expect(store.draftId()).toBeNull();
      expect(readRawDraftRef()).toBeNull();
    });
  });
});
