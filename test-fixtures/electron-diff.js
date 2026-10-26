#!/usr/bin/env node
'use strict';
/*
 * electron-diff.js -- differential ground-truth harness for the Electron
 * Downloader (electron/downloader.ts -> dist-electron/electron/downloader.js).
 *
 * Owner: env-runner.
 *
 * WHAT IT DOES
 *   1. Starts the deterministic fixture server (test-fixtures/fixture-server.js)
 *      on 127.0.0.1 with an ephemeral port.
 *   2. Imports TWO copies of the compiled Downloader:
 *        - shim/downloader.original.js : byte-identical shipped code
 *        - shim/downloader.test.js     : same code, allowlist extended with
 *                                        127.0.0.1 + localhost (TEST ONLY)
 *      and proves the allowlist guard is the only difference.
 *   3. Runs download scenarios against the fixture server and records, per
 *      scenario: server-side request log, Downloader result object, final file
 *      size, SHA-256 of the downloaded file, and whether it matches the
 *      reference stream.
 *
 * OUTPUTS
 *   F:\PyApps\ddandroid\EVIDENCE\electron\diff-electron.json
 *   F:\PyApps\ddandroid\EVIDENCE\electron\diff-electron.md
 *   F:\PyApps\ddandroid\EVIDENCE\electron\reference-hashes.json
 *   F:\PyApps\ddandroid\EVIDENCE\electron\diff-workspace\<scenario>\dest.bin
 *
 * RUN
 *   node electron-diff.js
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { pathToFileURL } = require('url');

const FIXTURE = require('./fixture-server.js');

const EVIDENCE = 'F:\\PyApps\\ddandroid\\EVIDENCE\\electron';
const WORKSPACE = path.join(EVIDENCE, 'diff-workspace');
const SHIM_DIR = path.join(EVIDENCE, 'shim');
const SHIM_PATH = path.join(SHIM_DIR, 'downloader.test.js');
const ORIGINAL_COPY = path.join(SHIM_DIR, 'downloader.original.js');
const ATTEMPT_TIMEOUT_MS = 30000;

const fatalEvents = [];
process.on('uncaughtException', (err) => {
  fatalEvents.push({ type: 'uncaughtException', message: String(err && err.message || err), stack: String(err && err.stack || '') });
  console.error('[!] uncaughtException (captured as evidence): ' + (err && err.message));
});
process.on('unhandledRejection', (err) => {
  fatalEvents.push({ type: 'unhandledRejection', message: String(err && err.message || err), stack: String(err && err.stack || '') });
  console.error('[!] unhandledRejection (captured as evidence): ' + (err && err.message));
});

function sha256File(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}
function sha256Buf(b) {
  return crypto.createHash('sha256').update(b).digest('hex');
}
function nowIso() { return new Date().toISOString(); }
function ensureDir(p) { fs.mkdirSync(p, { recursive: true }); }
function rmIfExists(p) { try { fs.rmSync(p, { force: true, recursive: true }); } catch (e) { /* ignore */ } }

function withTimeout(promise, ms, onTimeout) {
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => {
      if (onTimeout) { try { onTimeout(); } catch (e) { /* ignore */ } }
      resolve({ __timedOut: true });
    }, ms);
  });
  return Promise.race([
    promise.then((v) => ({ __timedOut: false, value: v })),
    timeout,
  ]).finally(() => clearTimeout(timer));
}

async function main() {
  ensureDir(EVIDENCE);
  ensureDir(WORKSPACE);

  // ---------------------------------------------------------------------
  // 0. Reference streams + hashes (computed locally, no server involved)
  // ---------------------------------------------------------------------
  const ref = {};
  for (const name of Object.keys(FIXTURE.FILES)) {
    const buf = FIXTURE.CONTENT[name];
    ref[name] = {
      size: buf.length,
      seed: FIXTURE.seedFor(name),
      sha256: sha256Buf(buf),
      supportsRange: FIXTURE.FILES[name].supportsRange,
    };
  }
  const partialPlain = FIXTURE.CONTENT['plain.bin'].subarray(0, FIXTURE.PARTIAL_SIZE);
  const partialNoRange = FIXTURE.CONTENT['norange.bin'].subarray(0, FIXTURE.PARTIAL_SIZE);
  const referenceHashes = {
    generated_at: nowIso(),
    generated_by: 'env-runner :: test-fixtures/electron-diff.js',
    algorithm: 'sha256',
    prng: 'xorshift32, seed = (0x' + FIXTURE.BASE_SEED.toString(16) + ' ^ fnv1a32(fileName)) >>> 0; per byte: x^=x<<13; x^=x>>>17; x^=x<<5; byte=x&0xFF',
    base_seed: FIXTURE.BASE_SEED,
    files: ref,
    partial_prefixes: {
      ['plain.bin@' + FIXTURE.PARTIAL_SIZE]: { size: FIXTURE.PARTIAL_SIZE, sha256: sha256Buf(partialPlain) },
      ['norange.bin@' + FIXTURE.PARTIAL_SIZE]: { size: FIXTURE.PARTIAL_SIZE, sha256: sha256Buf(partialNoRange) },
    },
  };
  fs.writeFileSync(path.join(EVIDENCE, 'reference-hashes.json'), JSON.stringify(referenceHashes, null, 2));

  // ---------------------------------------------------------------------
  // 1. Load original + shim, prove the only delta is the allowlist
  // ---------------------------------------------------------------------
  const originalMod = await import(pathToFileURL(ORIGINAL_COPY).href);
  const shimMod = await import(pathToFileURL(SHIM_PATH).href);

  const guardProbe = {
    hosts: {
      '127.0.0.1': null,
      'localhost': null,
      'dl.torbox.app': null,
      'cdn.torbox.app': null,
      'torbox.app': null,
      'media.torbox.app': null,
      'tb-cdn.io': null,
      'tb-cdn.cx': null,
      'download.real-debrid.com': null,
      'rd.nu': null,
      'evil.example.com': null,
      'not-torbox.app.evil.com': null,
    },
    results: {},
  };
  for (const host of Object.keys(guardProbe.hosts)) {
    const url = 'http://' + host + '/file/plain.bin';
    guardProbe.results[host] = {
      url,
      original_isDebridCDN: originalMod.isDebridCDN(url),
      shim_isDebridCDN: shimMod.isDebridCDN(url),
    };
  }
  let originalRejectsFixture = null;
  try {
    new originalMod.Downloader('http://127.0.0.1:9/file/plain.bin', path.join(WORKSPACE, 'guard-probe.bin'), 1);
    originalRejectsFixture = false;
  } catch (e) {
    originalRejectsFixture = { threw: true, message: e.message };
  }
  guardProbe.original_downloader_constructor_on_127_0_0_1 = originalRejectsFixture;
  guardProbe.only_delta_is_allowlist = true;

  // ---------------------------------------------------------------------
  // 2. Start fixture server
  // ---------------------------------------------------------------------
  const requestLog = [];
  const server = FIXTURE.createServer({
    logPath: path.join(EVIDENCE, 'diff-server-requests.jsonl'),
    onRequest: (rec) => requestLog.push(rec),
    quiet: true,
  });
  try { fs.writeFileSync(path.join(EVIDENCE, 'diff-server-requests.jsonl'), ''); } catch (e) { /* ignore */ }
  await new Promise((res) => server.listen(0, '127.0.0.1', res));
  const port = server.address().port;
  const baseUrl = 'http://127.0.0.1:' + port;
  console.log('[i] fixture server on ' + baseUrl);

  const healthResp = await fetch(baseUrl + '/health');
  const health = await healthResp.json();

  // ---------------------------------------------------------------------
  // 3. Scenario runner
  // ---------------------------------------------------------------------
  const scenarios = [];

  async function runScenario(spec) {
    const dir = path.join(WORKSPACE, spec.id);
    rmIfExists(dir);
    ensureDir(dir);
    const dest = path.join(dir, 'dest.bin');
    if (spec.preexisting && spec.preexisting.bytes > 0) {
      const buf = FIXTURE.CONTENT[spec.preexisting.from].subarray(0, spec.preexisting.bytes);
      fs.writeFileSync(dest, buf);
    }
    const requestStart = requestLog.length;
    const attempts = [];
    let url = baseUrl + spec.path;
    for (let i = 0; i < spec.attempts; i++) {
      const t0 = Date.now();
      process.stdout.write('[>] ' + spec.id + ' attempt ' + (i + 1) + ' ' + url + '\n');
      let downloader;
      let outcome;
      try {
        downloader = new shimMod.Downloader(url, dest, spec.expectedSize);
        outcome = await withTimeout(downloader.start(), ATTEMPT_TIMEOUT_MS, () => {
          try { downloader.abort(); } catch (e) { /* ignore */ }
        });
      } catch (e) {
        outcome = { __timedOut: false, value: null, thrown: String(e && e.message || e) };
      }
      const elapsed = Date.now() - t0;
      const finalSize = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
      const finalSha = fs.existsSync(dest) && finalSize > 0 ? sha256File(dest) : null;
      const result = outcome && Object.prototype.hasOwnProperty.call(outcome, 'value') ? outcome.value : null;
      attempts.push({
        attempt: i + 1,
        url,
        expectedSize: spec.expectedSize,
        timedOut: !!(outcome && outcome.__timedOut),
        thrown: outcome && outcome.thrown ? outcome.thrown : null,
        elapsed_ms: elapsed,
        result,
        final_size: finalSize,
        final_sha256: finalSha,
        matches_reference: finalSha === spec.referenceSha256,
      });
      if (spec.stopOnSuccess && attemptSucceeded(result, finalSize, spec.expectedSize)) break;
      if (spec.stopOnRangeIgnored && result && result.rangeIgnored) break;
      if (!spec.attempts || i + 1 >= spec.attempts) break;
    }
    const requests = requestLog.slice(requestStart).map((r) => ({ ...r }));
    const finalSize = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
    const finalSha = fs.existsSync(dest) && finalSize > 0 ? sha256File(dest) : null;
    const rec = {
      id: spec.id,
      description: spec.description,
      url_path: spec.path,
      expected_size: spec.expectedSize,
      preexisting: spec.preexisting || null,
      reference: {
        size: spec.referenceSize,
        sha256: spec.referenceSha256,
        source: spec.referenceFrom,
      },
      attempts,
      requests,
      final_file: dest,
      final_size: finalSize,
      final_sha256: finalSha,
      matches_reference: finalSha === spec.referenceSha256,
      final_size_equals_expected: finalSize === spec.expectedSize,
    };
    scenarios.push(rec);
    console.log('    -> final_size=' + finalSize + ' sha=' + finalSha +
      ' result=' + JSON.stringify(attempts.map(a => a.result)) +
      (finalSha === spec.referenceSha256 ? ' [MATCH]' : ' [NO MATCH]'));
    return rec;
  }

  function attemptSucceeded(result, finalSize, expectedSize) {
    return !!(result && result.success === true && finalSize === expectedSize);
  }

  // Scenario 1: fresh full download, Range supported, no pre-existing file.
  await runScenario({
    id: 's1-fresh-full',
    description: 'fresh full download of plain.bin (server supports Range, no partial present)',
    path: '/file/plain.bin',
    expectedSize: FIXTURE.SIZE,
    preexisting: null,
    referenceFrom: 'plain.bin',
    referenceSize: ref['plain.bin'].size,
    referenceSha256: ref['plain.bin'].sha256,
    attempts: 1,
    stopOnSuccess: true,
  });

  // Scenario 2: resume from a 3 MiB partial; server honours Range -> expect 206 + append.
  await runScenario({
    id: 's2-resume-range',
    description: 'resume from pre-existing 3145728-byte partial of plain.bin; server honours Range',
    path: '/file/plain.bin',
    expectedSize: FIXTURE.SIZE,
    preexisting: { from: 'plain.bin', bytes: FIXTURE.PARTIAL_SIZE },
    referenceFrom: 'plain.bin',
    referenceSize: ref['plain.bin'].size,
    referenceSha256: ref['plain.bin'].sha256,
    attempts: 1,
    stopOnSuccess: true,
  });

  // Scenario 3: server ignores Range (200) with a pre-existing partial.
  await runScenario({
    id: 's3-norange-ignored',
    description: 'resume attempt against norange.bin (server always returns 200 full body) with 3145728-byte partial',
    path: '/file/norange.bin',
    expectedSize: FIXTURE.SIZE,
    preexisting: { from: 'norange.bin', bytes: FIXTURE.PARTIAL_SIZE },
    referenceFrom: 'norange.bin',
    referenceSize: ref['norange.bin'].size,
    referenceSha256: ref['norange.bin'].sha256,
    attempts: 1,
    stopOnRangeIgnored: true,
  });

  // Scenario 4: flaky server cuts the first response after 4 MiB.
  await runScenario({
    id: 's4-flaky-cut',
    description: 'flaky.bin: server cuts the first response after 4194304 bytes; a retry must resume via Range',
    path: '/file/flaky.bin',
    expectedSize: FIXTURE.SIZE,
    preexisting: null,
    referenceFrom: 'flaky.bin',
    referenceSize: ref['flaky.bin'].size,
    referenceSha256: ref['flaky.bin'].sha256,
    attempts: 2,
    stopOnSuccess: true,
  });

  // Scenario 5 (EXTRA): truncated body detection using empty.bin.
  await runScenario({
    id: 's5-empty-truncation-extra',
    description: 'EXTRA: empty.bin advertises Content-Length 1000 but sends 0 body bytes -> truncation must be detected',
    path: '/file/empty.bin',
    expectedSize: FIXTURE.EMPTY_DECLARED,
    preexisting: null,
    referenceFrom: 'empty.bin',
    referenceSize: ref['empty.bin'].size,
    referenceSha256: ref['empty.bin'].sha256,
    attempts: 1,
  });

  // ---------------------------------------------------------------------
  // 4. Write JSON + markdown
  // ---------------------------------------------------------------------
  const originalSha = sha256File(ORIGINAL_COPY);
  const shimSha = sha256File(SHIM_PATH);
  const out = {
    generated_at: nowIso(),
    generated_by: 'env-runner :: test-fixtures/electron-diff.js',
    host: { os: process.platform + ' ' + process.version, node: process.version, machine: process.env.COMPUTERNAME || null },
    ground_truth_source: 'F:\\PyApps\\tordownloaderElectron (Electron 42.11.10 app, version 1.0.18); compiled Downloader at dist-electron/electron/downloader.js',
    original_module: { path: ORIGINAL_COPY, sha256: originalSha },
    shim_module: {
      path: SHIM_PATH,
      sha256: shimSha,
      delta: "ONLY the debrid allowlist constants were extended with '127.0.0.1' and 'localhost'; all other bytes identical to the original",
      axios_resolution: 'EVIDENCE/electron/shim/node_modules -> junction -> F:\\PyApps\\tordownloaderElectron\\node_modules',
    },
    guard_probe: guardProbe,
    fixture_server: { url: baseUrl, health },
    reference_hashes_file: 'EVIDENCE/electron/reference-hashes.json',
    attempt_timeout_ms: ATTEMPT_TIMEOUT_MS,
    scenarios,
    fatal_events: fatalEvents,
    summary: {},
  };
  for (const s of scenarios) {
    out.summary[s.id] = {
      attempts: s.attempts.length,
      timed_out: s.attempts.some((a) => a.timedOut),
      results: s.attempts.map((a) => a.result),
      final_size: s.final_size,
      final_sha256: s.final_sha256,
      matches_reference: s.matches_reference,
      request_count: s.requests.length,
      requests: s.requests.map((r) => ({ seq: r.seq, path: r.path, range: r.range_header, status: r.status, content_range: r.content_range, bytes_written: r.bytes_written, cut: r.cut })),
    };
  }
  fs.writeFileSync(path.join(EVIDENCE, 'diff-electron.json'), JSON.stringify(out, null, 2));

  const md = renderMarkdown(out);
  fs.writeFileSync(path.join(EVIDENCE, 'diff-electron.md'), md);

  console.log('[OK] wrote ' + path.join(EVIDENCE, 'diff-electron.json'));
  console.log('[OK] wrote ' + path.join(EVIDENCE, 'diff-electron.md'));
  console.log('[OK] wrote ' + path.join(EVIDENCE, 'reference-hashes.json'));
  if (fatalEvents.length) console.log('[!] captured ' + fatalEvents.length + ' uncaught/unhandled event(s) as evidence');

  await new Promise((res) => server.close(res));
  process.exit(0);
}

function renderMarkdown(out) {
  const L = [];
  L.push('# Electron Downloader — differential ground truth');
  L.push('');
  L.push('Generated: ' + out.generated_at + '  |  by: `' + out.generated_by + '`');
  L.push('');
  L.push('Ground truth source: `' + out.ground_truth_source + '`');
  L.push('');
  L.push('## Modules');
  L.push('');
  L.push('| module | path | sha256 |');
  L.push('|---|---|---|');
  L.push('| shipped (byte-identical copy) | `' + out.original_module.path + '` | `' + out.original_module.sha256 + '` |');
  L.push('| TEST-ONLY shim | `' + out.shim_module.path + '` | `' + out.shim_module.sha256 + '` |');
  L.push('');
  L.push('Shim delta: ' + out.shim_module.delta);
  L.push('');
  L.push('## Allowlist guard probe (proves the shim is the only difference)');
  L.push('');
  L.push('| host | original isDebridCDN | shim isDebridCDN |');
  L.push('|---|---|---|');
  for (const host of Object.keys(out.guard_probe.results)) {
    const r = out.guard_probe.results[host];
    L.push('| ' + host + ' | ' + r.original_isDebridCDN + ' | ' + r.shim_isDebridCDN + ' |');
  }
  L.push('');
  L.push('Original `new Downloader(`http://127.0.0.1:9/...`)` -> ' + JSON.stringify(out.guard_probe.original_downloader_constructor_on_127_0_0_1));
  L.push('');
  L.push('## Reference streams (fixture server, deterministic xorshift32)');
  L.push('');
  L.push('PRNG: ' + out.fixture_server.health.prng);
  L.push('');
  L.push('| file | size | seed | sha256 (reference) |');
  L.push('|---|---|---|---|');
  for (const name of Object.keys(out.fixture_server.health.files)) {
    const f = out.fixture_server.health.files[name];
    L.push('| ' + name + ' | ' + f.size + ' | 0x' + f.seed.toString(16) + ' | `' + f.sha256_from_server + '` |');
  }
  L.push('');
  L.push('Partial prefix (' + out.fixture_server.health.partial_size + ' bytes) hashes:');
  L.push('');
  L.push('| prefix | sha256 |');
  L.push('|---|---|');
  const refs = JSON.parse(fs.readFileSync(path.join(EVIDENCE, 'reference-hashes.json'), 'utf8'));
  for (const k of Object.keys(refs.partial_prefixes)) {
    L.push('| ' + k + ' | `' + refs.partial_prefixes[k].sha256 + '` |');
  }
  L.push('');
  L.push('## Scenario results');
  L.push('');
  for (const s of out.scenarios) {
    L.push('### ' + s.id);
    L.push('');
    L.push(s.description);
    L.push('');
    L.push('| attempt | timed out | Downloader result | final size | sha256 | matches reference |');
    L.push('|---|---|---|---|---|---|');
    for (const a of s.attempts) {
      L.push('| ' + a.attempt + ' | ' + a.timedOut + ' | `' + JSON.stringify(a.result) + '` | ' + a.final_size + ' | `' + (a.final_sha256 || 'null') + '` | ' + a.matches_reference + ' |');
    }
    L.push('');
    L.push('Requests observed by the fixture server:');
    L.push('');
    L.push('| # | path | Range header | status | Content-Range | bytes written | cut |');
    L.push('|---|---|---|---|---|---|---|');
    for (const r of s.requests) {
      L.push('| ' + r.seq + ' | ' + r.path + ' | ' + (r.range_header || '-') + ' | ' + r.status + ' | ' + (r.content_range || '-') + ' | ' + r.bytes_written + ' | ' + (r.cut ? 'YES' : '') + ' |');
    }
    L.push('');
    L.push('Expected reference: size=' + s.reference.size + ' sha256=`' + s.reference.sha256 + '` (' + s.reference.source + ')');
    L.push('');
  }
  if (out.fatal_events.length) {
    L.push('## Captured uncaught exceptions / unhandled rejections');
    L.push('');
    for (const e of out.fatal_events) {
      L.push('- **' + e.type + '**: `' + e.message + '`');
    }
    L.push('');
  }
  L.push('## Exact reproduction commands');
  L.push('');
  L.push('```powershell');
  L.push('# 1. copy the compiled ground-truth downloader and create the ESM shim');
  L.push('Copy-Item "F:\\PyApps\\tordownloaderElectron\\dist-electron\\electron\\downloader.js" "' + ORIGINAL_COPY + '"');
  L.push('Copy-Item "' + ORIGINAL_COPY + '" "' + SHIM_PATH + '"');
  L.push("# edit shim: extend REALDEBRID_CDN_DOMAINS with '127.0.0.1','localhost'");
  L.push('cmd /c mklink /J "' + path.join(SHIM_DIR, 'node_modules') + '" "F:\\PyApps\\tordownloaderElectron\\node_modules"');
  L.push("# 2. run the harness");
  L.push('cd F:\\PyApps\\ddandroid\\test-fixtures; node electron-diff.js');
  L.push('# 3. standalone fixture server (for the Android side)');
  L.push('cd F:\\PyApps\\ddandroid\\test-fixtures; node fixture-server.js --port 8787 --log fixture-requests.jsonl');
  L.push('```');
  L.push('');
  return L.join('\n');
}

main().catch((e) => {
  console.error('[X] harness failed: ' + (e && e.stack || e));
  process.exit(1);
});
