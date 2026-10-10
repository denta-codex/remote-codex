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
    for (const test of manifest) for (const attachment of test.attachments ?? []) {
      if (!/^fixture-(chat|settings|draft)\.png$/.test(attachment.suggestedHumanReadableName ?? '')) continue;
      const {copyFileSync} = await import('node:fs');
      copyFileSync(path.join(output, 'attachments', attachment.exportedFileName),
        path.join(report, 'screenshots', attachment.suggestedHumanReadableName));
    }
  } catch (error) {
    // Reporting failures must be visible without masking a test failure.
    identity.report_error = 'xcresult summary or attachment export failed';
    console.error(identity.report_error);
  }
}
writeFileSync(path.join(report, 'result.json'), JSON.stringify(identity, null, 2)+'\n');
const durations = readFileSync(path.join(report, 'durations.tsv'), 'utf8').trim().split('\n').slice(1);
const lines = [ `iOS checks: **${identity.status}**`, '', `Revision: \`${identity.revision}\``,
  `Run: ${identity.run_id}, attempt ${identity.run_attempt}`, '',
  'Apple Silicon · Xcode 16.4 (16F6) · iOS 18.6 · iPhone 16', '',
  '| Stage | Seconds | Exit status |', '| --- | ---: | ---: |',
  ...durations.map(row => '| '+row.split('\t').join(' | ')+' |') ];
if (identity.tests) lines.push('', `Tests: ${identity.tests.passedTests} passed; ${identity.tests.failedTests} failed.`);
if (identity.report_error) lines.push('', identity.report_error);
const summary = lines.join('\n')+'\n';
writeFileSync(path.join(report,'summary.md'), summary);
if (process.env.GITHUB_STEP_SUMMARY) writeFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
