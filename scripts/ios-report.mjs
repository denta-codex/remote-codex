#!/usr/bin/env node
import {readFileSync, writeFileSync, existsSync, mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
const output = process.argv[2];
const report = path.join(output, 'report');
const identity = {
  schema_version: 1, repository: process.env.GITHUB_REPOSITORY,
  revision: process.env.GITHUB_SHA, run_id: process.env.GITHUB_RUN_ID,
  run_attempt: process.env.GITHUB_RUN_ATTEMPT,
  runner_arch: process.arch, image_version: process.env.ImageVersion,
  xcode: '16.4 (16F6)', runtime: 'iOS 18.6', device: 'iPhone 16',
  status: Number(process.env.IOS_CHECK_STATUS) === 0 ? 'success' : 'failure',
};
const coreLog = path.join(output, 'core-tests.log');
if (existsSync(coreLog)) {
  const matches = [...readFileSync(coreLog,'utf8').matchAll(/\[(\d+)\/(\d+)\] Testing /g)];
  const coreStatus = readFileSync(path.join(report,'durations.tsv'),'utf8').split('\n').find(row=>row.startsWith('core-tests\t'))?.split('\t')[2];
  identity.core_tests = {scheduled: Number(matches.at(-1)?.[2] ?? 0), status: coreStatus === '0' ? 'success' : 'failure'};
}
const result = path.join(output, 'Tests.xcresult');
if (existsSync(result)) {
  try {
    const summary = execFileSync('xcrun', ['xcresulttool', 'get', 'test-results', 'summary', '--path', result], {encoding:'utf8'});
    identity.tests = JSON.parse(summary);
    mkdirSync(path.join(report, 'screenshots'), {recursive:true});
    // XCTest attachments are synthetic; only explicitly named screenshots are retained.
    execFileSync('xcrun', ['xcresulttool', 'export', 'attachments', '--path', result,
      '--output-path', path.join(output, 'attachments')]);
    const manifest = JSON.parse(readFileSync(path.join(output, 'attachments', 'manifest.json')));
    const selected = [];
    const attachmentNames = [];
    const fixtureCases = {
      'testFixtureChatAndSend': 'chat',
      'testFixtureSettingsNeverOffersLiveConnection': 'settings',
      'testDraftSurvivesRestart': 'draft',
    };
    for (const test of manifest) {
      const attachments = test.attachments ?? [];
      // Xcode's exporter can replace attachment names with test-based names.
      // Each case explicitly captures its final synthetic screen; select that
      // final PNG if the named attachment is unavailable in this manifest.
      const identifier = test.testIdentifier ?? test.testName ?? '';
      const kind = Object.entries(fixtureCases).find(([name]) => identifier.includes(name))?.[1];
      const finalPNG = attachments.filter(item => /\.png$/i.test(item.exportedFileName ?? '')).at(-1);
      if (kind && finalPNG) {
        const {copyFileSync} = await import('node:fs');
        const file = 'fixture-' + kind + '.png';
        copyFileSync(path.join(output,'attachments',finalPNG.exportedFileName), path.join(report,'screenshots',file));
        selected.push(file);
      }
      for (const attachment of attachments) {
      const name = attachment.suggestedHumanReadableName ?? attachment.name ?? '';
      attachmentNames.push({name, file: attachment.exportedFileName});
      const match = name.match(/fixture-(chat|settings|draft)(?=[^a-z]|$)/);
      if (!match || !attachment.exportedFileName) continue;
      const {copyFileSync} = await import('node:fs');
      const file = 'fixture-' + match[1] + '.png';
      copyFileSync(path.join(output, 'attachments', attachment.exportedFileName), path.join(report, 'screenshots', file));
      selected.push(file);
      }
    }
    writeFileSync(path.join(report, 'attachment-index.json'), JSON.stringify(attachmentNames, null, 2)+'\n');
    identity.screenshots = [...new Set(selected)];
    if (identity.tests.passedTests === 3 && identity.screenshots.length !== 3) {
      identity.report_error = 'Expected three selected synthetic screenshots; inspect attachment-index.json';
    }
  } catch (error) {
    // Reporting failures must be visible without masking a test failure.
    identity.report_error = 'xcresult summary or attachment export failed';
    console.error(identity.report_error);
  }
}
if (identity.report_error) identity.status = 'failure';
writeFileSync(path.join(report, 'result.json'), JSON.stringify(identity, null, 2)+'\n');
const durations = readFileSync(path.join(report, 'durations.tsv'), 'utf8').trim().split('\n').slice(1);
const lines = [ `iOS checks: **${identity.status}**`, '', `Revision: \`${identity.revision}\``,
  `Run: ${identity.run_id}, attempt ${identity.run_attempt}`, '',
  'Apple Silicon · Xcode 16.4 (16F6) · iOS 18.6 · iPhone 16', '',
  '| Stage | Seconds | Exit status |', '| --- | ---: | ---: |',
  ...durations.map(row => '| '+row.split('\t').join(' | ')+' |') ];
if (identity.core_tests) lines.push('', `Core tests: ${identity.core_tests.scheduled} scheduled, ${identity.core_tests.status}.`);
if (identity.tests) lines.push('', `Tests: ${identity.tests.passedTests} passed; ${identity.tests.failedTests} failed.`);
if (identity.report_error) lines.push('', identity.report_error);
const summary = lines.join('\n')+'\n';
writeFileSync(path.join(report,'summary.md'), summary);
if (process.env.GITHUB_STEP_SUMMARY) writeFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
if (identity.report_error) process.exitCode = 1;
