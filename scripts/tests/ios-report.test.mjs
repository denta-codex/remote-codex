import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const script = path.resolve('scripts/ios-report.mjs');
function fixture(t, {skipped = 0, screenshots = true, result = true} = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ios-report-test-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  const report = path.join(dir, 'report');
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(report);
  fs.mkdirSync(bin);
  if (result) fs.mkdirSync(path.join(dir, 'Tests.xcresult'));
  fs.writeFileSync(path.join(report, 'durations.tsv'),
    'stage\tseconds\tstatus\n' + ['helper-tests', 'core-tests', 'simulator-boot', 'simulator-build', 'fixture-ui-tests']
      .map(name => `${name}\t1\t0\n`).join(''));
  fs.writeFileSync(path.join(dir, 'core-tests.log'), '[6/6] Testing fixture\n');
  fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({
    passedTests: 3 - skipped, failedTests: 0, skippedTests: skipped,
  }));
  const cases = [
    ['testHelloWorldLaunch', 'launch'], ['testTapAndSaveNote', 'interaction'],
    ['testStateSurvivesRestart', 'persistence'],
  ];
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(cases.map(([testIdentifier, kind]) => ({
    testIdentifier, attachments: screenshots ? [{
      exportedFileName: `fixture-${kind}.png`, suggestedHumanReadableName: `fixture-${kind}`,
    }] : [],
  }))));
  fs.writeFileSync(path.join(bin, 'xcrun'), `#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
const args = process.argv.slice(2), dir = process.env.IOS_REPORT_TEST_DIR;
if (args.includes('summary')) {
  process.stdout.write(fs.readFileSync(path.join(dir, 'summary.json')));
} else if (args.includes('attachments')) {
  const destination = args.at(-1);
  fs.mkdirSync(destination);
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json')));
  fs.copyFileSync(path.join(dir, 'manifest.json'), path.join(destination, 'manifest.json'));
  for (const entry of manifest) for (const attachment of entry.attachments) {
    fs.writeFileSync(path.join(destination, attachment.exportedFileName), 'synthetic screenshot fixture');
  }
} else process.exit(2);
`, {mode: 0o700});
  const run = () => {
    if (result) assert.ok(fs.existsSync(path.join(dir, 'Tests.xcresult')));
    const child = spawnSync(process.execPath, [script, dir], {encoding: 'utf8', env: {
    ...process.env, PATH: bin + path.delimiter + process.env.PATH, IOS_REPORT_TEST_DIR: dir,
    IOS_CHECK_STATUS: '0', GITHUB_REPOSITORY: 'denta-codex/remote-codex', GITHUB_SHA: 'a'.repeat(40),
    GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1', GITHUB_STEP_SUMMARY: path.join(dir, 'job-summary.md'),
    }});
    assert.ifError(child.error);
    return child;
  };
  return {run, read: () => JSON.parse(fs.readFileSync(path.join(report, 'result.json')))};
}

test('accepts completed stages with three passing UI tests and selected screenshots', t => {
  const f = fixture(t), result = f.run();
  assert.equal(result.status, 0, JSON.stringify(result) + JSON.stringify(f.read()));
  assert.equal(f.read().status, 'success');
  assert.equal(f.read().fixture, 'hello-world');
  assert.equal(f.read().core_tests.scheduled, 6);
  assert.deepEqual(f.read().screenshots.sort(), ['fixture-interaction.png', 'fixture-launch.png', 'fixture-persistence.png']);
});

test('rejects a skipped UI test even when the command reports success', t => {
  const f = fixture(t, {skipped: 1});
  assert.notEqual(f.run().status, 0);
  assert.equal(f.read().status, 'failure');
});

test('rejects passing UI tests without their screenshots', t => {
  const f = fixture(t, {screenshots: false});
  assert.notEqual(f.run().status, 0);
  assert.equal(f.read().status, 'failure');
});

test('rejects successful command exit without an XCTest result bundle', t => {
  const f = fixture(t, {result: false});
  assert.notEqual(f.run().status, 0);
  assert.equal(f.read().status, 'failure');
});
