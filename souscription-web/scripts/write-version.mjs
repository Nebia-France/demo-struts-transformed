import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

// The deployed version signal (W9-T04): `release` is the commit sha of the build, `builtAt` the
// build date. CI passes the sha through GIT_COMMIT_SHA (also as a Docker build argument, since the
// image build context has no .git); a local build falls back to `git rev-parse HEAD`.
function commitSha() {
  if (process.env.GIT_COMMIT_SHA) return process.env.GIT_COMMIT_SHA;
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return 'local';
  }
}

writeFileSync('src/version.json', JSON.stringify({ release: commitSha(), builtAt: new Date().toISOString() }) + '\n');
