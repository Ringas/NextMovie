"""Run explicit non-cloud samples on the ARM-translation emulator; no cross-source fallback."""
import json, os, subprocess, time
from pathlib import Path
ADB=os.environ.get('ADB','/Users/sean/toolchain/sdk/platform-tools/adb')
SERIAL=os.environ.get('ANDROID_SERIAL','emulator-5556')
OUT=Path(os.environ.get('QA_DIR','artifacts/noncloud-20261124')); OUT.mkdir(parents=True,exist_ok=True)
cases=[
 ('szy-aid-live','https://szyyds.cn/tv/x.json','Aid','live'),
 ('fanty-aid-snapshot','http://10.0.2.2:8766/config-1','Aid','snapshot'),
 ('wex-children-snapshot','http://10.0.2.2:8766/config-2','ChildrenAiTuTu','snapshot'),
 ('feimao-preview-live','http://肥猫.net','豆瓣预告','live'),
 ('szy-cms-json-live','https://szyyds.cn/tv/x.json','电影天堂','live'),
 ('szy-cms-xml-live','https://szyyds.cn/tv/x.json','索尼','live'),
 ('fanty-libvio-snapshot','http://10.0.2.2:8766/config-1','立播','snapshot'),
]
selected_cases = set(filter(None, os.environ.get('NONCLOUD_CASES','').split(',')))
if selected_cases: cases = [c for c in cases if c[0] in selected_cases]
def adb(*args): return subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,text=True,timeout=25)
results=[]
for name,config,site,mode in cases:
 print('START',name,flush=True)
 adb('shell','am','force-stop','com.nextmovie')
 adb('shell','run-as','com.nextmovie','rm','-f','files/compat-probe.json')
 launch=adb('shell','am','start','-n','com.nextmovie/.CompatibilityProbeActivity','--es','config',config,'--es','site',site,'--ez','strict','true')
 deadline=time.monotonic()+180
 result=None
 while time.monotonic()<deadline:
  time.sleep(2)
  raw=adb('exec-out','run-as','com.nextmovie','cat','files/compat-probe.json')
  try: candidate=json.loads(raw.stdout)
  except (ValueError,TypeError): continue
  if candidate.get('running') is False: result=candidate; break
 if result is None: result={'error':'Test deadline reached (180s)'}
 # Older probe builds emit this event again after seek; do not label it initial-frame latency.
 if result.get('probeVersion',1) < 2 and 'firstFrameMs' in result: result['lastFrameEventMs']=result.pop('firstFrameMs')
 result.update({'case':name,'inputMode':mode})
 (OUT/(name+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
 results.append(result)
 (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
 print(json.dumps({k:result[k] for k in ['case','home','detail','detailSite','playback','firstFrame','seek','error'] if k in result},ensure_ascii=False),flush=True)
adb('shell','am','force-stop','com.nextmovie')
