#!/usr/bin/env node
// Validate the exact downloaded package before any device helper can consume it.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
export function verify(directory, revision, runID, attempt) {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'metadata.json'), 'utf8'));
  const keys = ['schema_version','repository','revision','run_id','run_attempt','artifact','sha256',
    'bundle_id','version','build_number','signing','minimum_ios','fixture_launch_argument'];
  if (Object.keys(manifest).sort().join() !== keys.sort().join()) throw Error('Unexpected manifest schema');
  const expected = {schema_version:1, repository:'denta-codex/remote-codex', revision,
    run_id:String(runID), run_attempt:String(attempt), artifact:'RemoteCodex.ipa',
    bundle_id:'dev.codexops.client.ios', signing:'development', minimum_ios:'18.0',
    fixture_launch_argument:'--fixture'};
  for (const [key, value] of Object.entries(expected)) if (manifest[key] !== value) throw Error(`Artifact ${key} mismatch`);
  if (!/^[0-9a-f]{40}$/.test(revision) || !/^\d+\.\d+\.\d+$/.test(manifest.version) ||
      !/^\d+\.\d+$/.test(manifest.build_number) || !/^[0-9a-f]{64}$/.test(manifest.sha256)) throw Error('Invalid manifest identity');
  if (fs.readdirSync(directory).sort().join() !== ['RemoteCodex.ipa','SHA256SUMS','metadata.json'].sort().join()) throw Error('Unexpected package contents');
  for (const file of ['RemoteCodex.ipa','SHA256SUMS','metadata.json']) {
    if (!fs.lstatSync(path.join(directory,file)).isFile()) throw Error('Package files must be regular files');
  }
  const ipa = fs.readFileSync(path.join(directory,'RemoteCodex.ipa'));
  if (ipa.length === 0) throw Error('Empty IPA');
  const hash = crypto.createHash('sha256').update(ipa).digest('hex');
  if (hash !== manifest.sha256 || fs.readFileSync(path.join(directory,'SHA256SUMS'),'utf8') !== `${hash}  RemoteCodex.ipa\n`) throw Error('IPA checksum mismatch');
  return manifest;
}
if (process.argv[1] === new URL(import.meta.url).pathname) {
  try {
    const manifest = verify(...process.argv.slice(2));
    console.log(`Verified ${manifest.artifact}: revision ${manifest.revision}, run ${manifest.run_id}, attempt ${manifest.run_attempt}, SHA256 ${manifest.sha256}`);
  } catch (error) { console.error(error.message); process.exit(1); }
}
