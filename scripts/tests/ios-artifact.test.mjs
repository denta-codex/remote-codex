import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {verify} from '../ios-artifact.mjs';
function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'ios-artifact-test-'));
  t.after(() => fs.rmSync(dir,{recursive:true,force:true}));
  const ipa = Buffer.from('synthetic IPA bytes; no real signing material');
  const hash = crypto.createHash('sha256').update(ipa).digest('hex');
  const manifest = {schema_version:1, repository:'denta-codex/remote-codex', revision:'a'.repeat(40),
    run_id:'123',run_attempt:'1',artifact:'RemoteCodex.ipa',sha256:hash,bundle_id:'dev.codexops.client.ios',
    version:'0.1.0',build_number:'7.1',signing:'development',minimum_ios:'18.0',fixture_launch_argument:'--fixture'};
  fs.writeFileSync(path.join(dir,'RemoteCodex.ipa'),ipa);
  fs.writeFileSync(path.join(dir,'SHA256SUMS'),`${hash}  RemoteCodex.ipa\n`);
  const save = () => fs.writeFileSync(path.join(dir,'metadata.json'),JSON.stringify(manifest));
  save();
  return {dir,manifest,save,check:()=>verify(dir,'a'.repeat(40),'123','1')};
}
test('accepts exact-run manifest and computed IPA checksum', t => { const f=fixture(t); assert.equal(f.check().run_id,'123'); });
for (const [key,value] of Object.entries({revision:'b'.repeat(40), run_id:'124',run_attempt:'2',
    bundle_id:'other.app',signing:'app-store',artifact:'other.ipa',fixture_launch_argument:''})) {
  test(`rejects ${key} identity substitution`, t => { const f=fixture(t);f.manifest[key]=value;f.save();assert.throws(f.check); });
}
test('rejects old manifest aliases', t => {const f=fixture(t);f.manifest.source_revision=f.manifest.revision;f.save();assert.throws(f.check);});
test('rejects modified IPA with unchanged metadata', t => {const f=fixture(t);fs.appendFileSync(path.join(f.dir,'RemoteCodex.ipa'),'tampered');assert.throws(f.check);});
test('rejects checksum file disagreement', t => {const f=fixture(t);fs.writeFileSync(path.join(f.dir,'SHA256SUMS'),'0'.repeat(64)+'  RemoteCodex.ipa\n');assert.throws(f.check);});
test('rejects extra signing files', t => {const f=fixture(t);fs.writeFileSync(path.join(f.dir,'certificate.p12'),'synthetic');assert.throws(f.check);});
test('rejects symbolic-link IPA', t => {const f=fixture(t);fs.renameSync(path.join(f.dir,'RemoteCodex.ipa'),path.join(f.dir,'other'));fs.symlinkSync('other',path.join(f.dir,'RemoteCodex.ipa'));assert.throws(f.check);});
