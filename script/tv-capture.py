"""Capture an authorized Android TV UI without reading preferences or logcat."""
import argparse,pathlib,re,subprocess
p=argparse.ArgumentParser();p.add_argument('name');p.add_argument('--device',required=True);a=p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_-]+',a.name):p.error('Use letters, digits, hyphens or underscores for the capture name')
root=pathlib.Path(__file__).resolve().parents[1];out=root/'.build-cache/device-captures';out.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.run(['adb','-s',a.device,*args],check=True)
remote='/sdcard/nova-agent-capture'
try:
 adb('shell','rm','-f',remote+'.xml',remote+'.png')
 adb('shell','uiautomator','dump',remote+'.xml')
 adb('shell','screencap','-p',remote+'.png')
 for extension in ['xml','png']:adb('pull',remote+'.'+extension,str(out/(a.name+'.'+extension)))
finally:adb('shell','rm','-f',remote+'.xml',remote+'.png')
