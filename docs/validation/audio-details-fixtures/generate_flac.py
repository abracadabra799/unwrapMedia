"""Real ffmpeg FLAC audio with an explicitly authored standard SEEKTABLE.
Seek offsets/sample counts come from ffprobe packet positions, not guessed bytes.
"""
from pathlib import Path
import json, struct, subprocess
root=Path(__file__).resolve().parents[3]
out=root/'app/src/test/resources/detail-parsing'
base=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','0.25','-c:a','flac']
path=out/'seekpoints.flac'
subprocess.run(base+[str(path)],check=True)
frames=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','a','-show_frames','-show_entries','frame=pkt_pos,nb_samples','-of','json',str(path)]))['frames']
b=path.read_bytes()
assert b[:4]==b'fLaC' and b[4]&127==0 and b[4]&128==0 and int.from_bytes(b[5:8],'big')==34
origin=int(frames[0]['pkt_pos']);sample=0;points=[]
for f in frames:
 pos=int(f['pkt_pos']); count=int(f['nb_samples'])
 assert b[pos]==255 and b[pos+1]&0xfc==0xf8
 points.append((sample,pos-origin,count)); sample+=count
payload=b''.join(struct.pack('>QQH',*p) for p in points)
path.write_bytes(b[:42]+bytes([3])+len(payload).to_bytes(3,'big')+payload+b[42:])
subprocess.run(base+['-strict','-2',str(out/'default-flac.mp4')],check=True)
print('authored seek points from actual frames:',points)
