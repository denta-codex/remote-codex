import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
const script=path.resolve('scripts/ios');
const revision='a'.repeat(40);
function fixture(t, scenario='success') {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ios-cli-test-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const bin=path.join(dir,'bin');fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin,'git'),`#!/usr/bin/env bash\nif [[ "$1" == rev-parse ]]; then echo ${revision}; fi\n`,{mode:0o700});
  fs.writeFileSync(path.join(bin,'gh'),`#!/usr/bin/env node
import fs from 'node:fs';import crypto from 'node:crypto';import path from 'node:path';
const a=process.argv.slice(2), scenario=process.env.IOS_TEST_SCENARIO;
fs.appendFileSync(process.env.IOS_TEST_CALLS,JSON.stringify(a)+'\\n');
if(a[0]==='api') {
  const endpoint=a[1];
  if(endpoint.includes('/contents/')) {if(scenario==='unregistered')process.exit(1);console.log('{}');}
  else if(endpoint.endsWith('/actions/workflows/ios-development.yml'))console.log(JSON.stringify({state:'active'}));
  else if(endpoint.includes('/commits/'))console.log('${revision}');
  else if(endpoint==='repos/denta-codex/remote-codex')console.log('main');
  else if(endpoint.includes('/artifacts?'))console.log(JSON.stringify({artifacts:[{name:'ios-development-123-1',expired:scenario==='expired'}]}));
  else if(a.includes('--jq'))console.log(scenario==='race'?'2':'1');
  else console.log(JSON.stringify({head_sha:scenario==='revision'?'b'.repeat(40):'${revision}',repository:{full_name:'denta-codex/remote-codex'},
    path:scenario==='workflow'?'.github/workflows/other.yml':'.github/workflows/ios-development.yml',event:scenario==='event'?'push':'workflow_dispatch',
    status:'completed',conclusion:scenario==='failed'?'failure':'success',run_attempt:1}));
} else if(a[0]==='workflow') {if(scenario==='uncertain')process.exit(1);}
else if(a[0]==='run'&&a[1]==='download') {
  const dir=a[a.indexOf('--dir')+1],ipa=Buffer.from('synthetic IPA'),sha256=crypto.createHash('sha256').update(ipa).digest('hex');
  fs.writeFileSync(path.join(dir,'RemoteCodex.ipa'),ipa);
  fs.writeFileSync(path.join(dir,'SHA256SUMS'),sha256+'  RemoteCodex.ipa\\n');
  fs.writeFileSync(path.join(dir,'metadata.json'),JSON.stringify({schema_version:1,repository:'denta-codex/remote-codex',revision:'${revision}',run_id:'123',run_attempt:'1',
    artifact:'RemoteCodex.ipa',sha256,bundle_id:'dev.codexops.client.ios',version:'0.1.0',build_number:'7.1',signing:'development',minimum_ios:'18.0',fixture_launch_argument:'--fixture'}));
} else process.exit(2);
`,{mode:0o700});
  const calls=path.join(dir,'calls'),destination=path.join(dir,'package');
  const env={...process.env,PATH:bin+path.delimiter+process.env.PATH,IOS_TEST_SCENARIO:scenario,IOS_TEST_CALLS:calls};
  return {dir,destination,run:(...args)=>spawnSync('bash',[script,...args],{env,encoding:'utf8'}),
    calls:()=>fs.existsSync(calls)?fs.readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse):[]};
}
test('retrieves only the named exact-run artifact and verifies package',t=>{
  const f=fixture(t),r=f.run('download','123',revision,f.destination);assert.equal(r.status,0,r.stderr);assert.ok(fs.existsSync(path.join(f.destination,'RemoteCodex.ipa')));
  assert.deepEqual(f.calls().find(a=>a[0]==='run'),['run','download','123','--repo','denta-codex/remote-codex','--name','ios-development-123-1','--dir',f.calls().find(a=>a[0]==='run').at(-1)]);
});
for(const scenario of ['revision','workflow','event','failed','expired','race'])test(`rejects ${scenario} without accepting a package`,t=>{
  const f=fixture(t,scenario),r=f.run('download','123',revision,f.destination);assert.notEqual(r.status,0);assert.ok(!fs.existsSync(f.destination));
  if(scenario!=='race')assert.ok(!f.calls().some(a=>a[0]==='run'));assert.ok(!fs.readdirSync(f.dir).some(n=>n.startsWith('.ios-download')));
});
test('refuses existing download destination',t=>{const f=fixture(t);fs.mkdirSync(f.destination);const r=f.run('download','123',revision,f.destination);assert.notEqual(r.status,0);assert.ok(!f.calls().some(a=>a[0]==='run'));});
test('refuses dispatch before default-branch registration',t=>{const f=fixture(t,'unregistered'),r=f.run('build','codex/test',revision);assert.notEqual(r.status,0);assert.ok(!f.calls().some(a=>a[0]==='workflow'));});
test('does not replay uncertain workflow dispatch',t=>{const f=fixture(t,'uncertain'),r=f.run('build','codex/test',revision);assert.notEqual(r.status,0);assert.equal(f.calls().filter(a=>a[0]==='workflow').length,1);assert.match(r.stderr,/uncertain/);});
