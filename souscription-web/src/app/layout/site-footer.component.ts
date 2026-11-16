import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { LAYOUT_LABELS } from './labels';

/** Deployed version shown by the diagnostic footer: technical fields only, no personal data. */
export interface FooterRelease {
  /** Full commit sha of `/version.json`, exposed as `data-release` for support diagnostics. */
  readonly release: string;
  /** Short form rendered as text. */
  readonly short: string;
  /** Build date (YYYY-MM-DD) of the artifact. */
  readonly builtOn: string;
}

/** Turns the `/version.json` values read at bootstrap into the footer diagnostic. */
export function footerRelease(release: string, builtAt: string): FooterRelease {
  return {
    release,
    short: /^[0-9a-f]{12,}$/i.test(release) ? release.slice(0, 12) : release,
    builtOn: /^\d{4}-\d{2}-\d{2}/.test(builtAt) ? builtAt.slice(0, 10) : builtAt,
  };
}

/**
 * Footer part of the former `footer` fragment, plus the diagnostic release line (W9-T04): the commit
 * sha and build date of the deployed artifact, as reported with every telemetry error payload.
 */
@Component({
  selector: 'app-site-footer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './site-footer.component.html',
  styleUrl: './site-footer.component.scss',
})
export class SiteFooterComponent {
  readonly labels = LAYOUT_LABELS;
  readonly version = signal<FooterRelease | null>(null);

  constructor() {
    // The telemetry configuration is loaded lazily by the bootstrap initializer (app.config.ts),
    // which has resolved `/version.json` before the root component renders. Importing it lazily
    // here too reuses that chunk and keeps it out of the initial bundle.
    void import('../core/telemetry/config').then(({ telemetryConfig }) => {
      const { release, builtAt } = telemetryConfig();
      this.version.set(footerRelease(release, builtAt));
    });
  }
}
