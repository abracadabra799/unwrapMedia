"""Actual ffmpeg MOV metadata, retaining default MOV sound entry layout."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[3]
out=root/'app/src/test/resources/mov-details';out.mkdir(parents=True,exist_ok=True)
base=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','testsrc2=size=64x64:rate=5','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','0.2','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-metadata','title=검증 MOV','-metadata','com.apple.quicktime.make=Test Camera','-metadata','com.example.custom=custom-value']
for name,flags in [('keyed.mov',['-movflags','+use_metadata_tags']),('legacy.mov',[])]:
 subprocess.run(base+flags+[str(out/name)],check=True)

subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','sine=frequency=440:sample_rate=96000','-t','0.02','-ac','2','-c:a','pcm_f32le',str(out/'float-audio.mov')],check=True)

# Ambiguous raw FourCC: preserve raw RGB video as uninterpreted until track-aware dispatch.
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','color=red:s=16x16:d=0.04','-c:v','rawvideo','-pix_fmt','rgb24',str(out/'raw-video.mov')],check=True)
