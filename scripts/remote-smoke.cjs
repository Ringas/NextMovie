// Run from the project directory with the home page open. Uses only D-pad key events.
const {spawnSync} = require('child_process');
const fs = require('fs'), path = require('path');
const adb = process.env.ADB || 'adb', serial = process.env.ANDROID_SERIAL || 'emulator-5556';
const out = process.env.QA_DIR || 'artifacts/remote-smoke'; fs.mkdirSync(out,{recursive:true});
function run(args) {
  const r = spawnSync(adb, ['-s',serial,...args], {timeout:25000,maxBuffer:5*1024*1024});
  if (r.status !== 0) throw Error(r.stderr?.toString() || 'adb failed'); return r.stdout;
}
const sleep = ms => new Promise(r=>setTimeout(r,ms));
async function focused(name) {
  run(['shell','uiautomator','dump','/sdcard/remote-smoke.xml']);
  const xml = run(['exec-out','cat','/sdcard/remote-smoke.xml']).toString();
  fs.writeFileSync(path.join(out,name+'.xml'),xml);
  const match = /<node\b[^>]*focused="true"[^>]*>/.exec(xml);
  if (!match) throw Error(name+': no keyboard focus');
  const own = match[0], following = xml.slice(match.index,xml.indexOf('</node>',match.index)+7);
  const labels = [...following.matchAll(/(?:text|content-desc)="([^"]+)"/g)].map(m=>m[1]);
  const bounds=own.match(/bounds="([^"]+)"/)?.[1];
  return {step:name,label:labels[0]||'',bounds};
}
(async()=>{
  await sleep(1000);
  const results=[await focused('initial')];
  for(const [name,key] of [['right',22],['down',20],['left',21],['up',19]]) {
    run(['shell','input','keyevent',String(key)]); await sleep(250); results.push(await focused(name));
  }
  for(let i=1;i<results.length;i++) {
    if(results[i].label===results[i-1].label && results[i].bounds===results[i-1].bounds) throw Error(results[i].step+': focus did not move');
  }
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));
  fs.writeFileSync(path.join(out,'focused-home.png'),run(['exec-out','screencap','-p']));
  console.log(JSON.stringify(results));
})().catch(e=>{console.error(e.message);process.exitCode=1});
