/**
 * The only piece of wizard state kept in the browser: the reference of the server-side draft.
 *
 * The legacy tier held the whole wizard (`draft`, `completed`, `completedReference`,
 * `completedKey`, `csrf`) in the HTTP session. Here the authoritative draft stays on the
 * server; the browser persists `draftId` and `createdAt` only, so a reload can rehydrate.
 * No personal data and no confirmation key is ever written to `sessionStorage`.
 */

export const DRAFT_STORAGE_KEY = 'souscription.draft';

export interface DraftRef {
  draftId: string;
  createdAt: number;
}

export function saveDraftRef(draftId: string, createdAt: number): void {
  sessionStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify({ draftId, createdAt }));
}

export function readDraftRef(): DraftRef | null {
  const raw = sessionStorage.getItem(DRAFT_STORAGE_KEY);
  if (!raw) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }
  const { draftId, createdAt } = parsed as Partial<DraftRef>;
  return typeof draftId === 'string' && typeof createdAt === 'number'
    ? { draftId, createdAt }
    : null;
}

/** Raw persisted payload, used to assert that nothing but the draft reference is stored. */
export function readRawDraftRef(): string | null {
  return sessionStorage.getItem(DRAFT_STORAGE_KEY);
}

export function clearDraftRef(): void {
  sessionStorage.removeItem(DRAFT_STORAGE_KEY);
}
