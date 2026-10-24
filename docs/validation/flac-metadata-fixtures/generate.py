"""Generate real audio and FLAC metadata via ffmpeg and Mutagen 1.47.0."""
from pathlib import Path
import shutil
import subprocess
from mutagen.flac import FLAC, CueSheet, CueSheetTrack, CueSheetTrackIndex, Picture
root=Path(__file__).resolve().parents[3]
out=root/'app/src/test/resources/flac-metadata'
out.mkdir(parents=True,exist_ok=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','2','-c:a','flac',str(out/'default.flac')],check=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','color=red:s=8x8','-frames:v','1',str(out/'cover.png')],check=True)
shutil.copyfile(out/'default.flac',out/'metadata.flac')
f=FLAC(out/'metadata.flac')
f['TITLE']='검증 FLAC'
f['ARTIST']=['First','Second']
cue=CueSheet(None)
cue.media_catalog_number=b'1234567890123'
cue.lead_in_samples=88200
cue.compact_disc=True
first=CueSheetTrack(1,0,b'USAAA2600001',0,True)
first.indexes=[CueSheetTrackIndex(0,0),CueSheetTrackIndex(1,588)]
second=CueSheetTrack(2,44100)
second.indexes=[CueSheetTrackIndex(1,0)]
cue.tracks=[first,second,CueSheetTrack(170,88200)]
f.metadata_blocks.append(cue)
p=Picture();p.type=3;p.mime='image/png';p.desc='표지';p.width=8;p.height=8;p.depth=24;p.colors=0;p.data=(out/'cover.png').read_bytes()
f.add_picture(p)
p=Picture();p.type=4;p.mime='-->';p.desc='Remote cover';p.data=b'https://example.invalid/cover.png'
f.add_picture(p)
f.save(padding=lambda _:0)
# Non-CD cuesheets use a 255 lead-out and offsets need not be multiples of 588.
shutil.copyfile(out/'default.flac',out/'non-cd.flac')
f=FLAC(out/'non-cd.flac');cue=CueSheet(None)
cue.lead_in_samples=0;cue.compact_disc=False
first=CueSheetTrack(1,17,b'',1,False);first.indexes=[CueSheetTrackIndex(1,3)]
cue.tracks=[first,CueSheetTrack(255,88200)]
f.metadata_blocks.append(cue);f.save(padding=lambda _:0)
