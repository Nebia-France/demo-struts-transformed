import { DRAFT_WINDOW_MS, EXPIRY_REDIRECT_URL, isExpired, isExpiryStatus } from './draft-window';

describe('draft window', () => {
  it('reproduces the web.xml 20-minute session timeout', () => {
    expect(DRAFT_WINDOW_MS).toBe(20 * 60 * 1000);
  });

  it('is not expired just inside the window', () => {
    const createdAt = 1_000_000;
    expect(isExpired(createdAt, createdAt)).toBe(false);
    expect(isExpired(createdAt, createdAt + DRAFT_WINDOW_MS - 1)).toBe(false);
  });

  it('is expired at and after the window', () => {
    const createdAt = 1_000_000;
    expect(isExpired(createdAt, createdAt + DRAFT_WINDOW_MS)).toBe(true);
    expect(isExpired(createdAt, createdAt + DRAFT_WINDOW_MS + 1)).toBe(true);
  });

  it('treats 404 and 410 as expiry, other statuses as technical errors', () => {
    expect(isExpiryStatus(404)).toBe(true);
    expect(isExpiryStatus(410)).toBe(true);
    expect(isExpiryStatus(500)).toBe(false);
    expect(isExpiryStatus(0)).toBe(false);
  });

  it('sends an expired journey back to the catalogue with the parcours notice', () => {
    expect(EXPIRY_REDIRECT_URL).toBe('/catalogue?notice=parcours');
  });
});
