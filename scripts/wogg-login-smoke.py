"""Remote-only test on an already selected Wogg home. Never captures QR pixels or input values."""
import json, os, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
ADB = os.environ.get('ADB', '/Users/sean/toolchain/sdk/platform-tools/adb')
OUT = Path(os.environ.get('QA_DIR', 'artifacts/wogg-login'))
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output([ADB, *args], timeout=20).decode('utf-8', errors='replace')
def key(code): adb('shell', 'input', 'keyevent', str(code))
def state():
    adb('shell', 'uiautomator', 'dump', '/sdcard/wogg-check.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/wogg-check.xml'))
def texts(root): return [n.get('text', '') for n in root.iter('node') if n.get('class') != 'android.widget.EditText']
def wait_for(predicate, label, seconds=45):
    start = time.monotonic()
    while time.monotonic() - start < seconds:
        root = state()
        if predicate(root): return root
        time.sleep(.5)
    raise AssertionError('Timed out: ' + label)
def has_play(root):
    return any(n.get('focused') == 'true' and n.get('enabled') == 'true' and any('▶ 播放' in x for x in texts(n)) for n in root.iter('node'))
def has_login(root): return any('网盘源需登录' in x for x in texts(root))
def has_qr(root):
    return any(n.get('class') == 'android.widget.ImageView' and n.get('bounds') != '[0,0][0,0]' for n in root.iter('node'))

results = []
wait_for(lambda r: any('玩偶哥哥' in x for x in texts(r)) and '最近更新' in texts(r) and any(n.get('focused') == 'true' and any(x.strip() and x not in ['搜索', '设置'] for x in texts(n)) for n in r.iter('node')), 'focused Wogg home')
key(23)
wait_for(has_play, 'Wogg detail')
for attempt in range(2):
    started = time.monotonic()
    key(23)
    wait_for(has_login, 'automatic play login prompt')
    prompt = round(time.monotonic() - started, 3)
    key(20); key(23)
    root = wait_for(has_qr, 'native QR ImageView')
    results.append({'attempt': attempt + 1, 'loginPromptObservedSeconds': prompt, 'qrImageWindow': True})
    key(4)
    wait_for(has_play, 'return to detail after cancel', seconds=25)
    time.sleep(.3)
(OUT / 'result.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps(results, ensure_ascii=False, indent=2))
