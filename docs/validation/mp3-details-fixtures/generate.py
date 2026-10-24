"""Real libmp3lame/ffmpeg MP3 variants with default ID3 framing; synthetic sine/cover."""
from pathlib import Path
from PIL import Image
import subprocess
root=Path(__file__).resolve().parents[3]
out=root/'app/src/test/resources/mp3-details'
out.mkdir(parents=True,exist_ok=True)
cover=out/'cover.png'
Image.new('RGB',(8,8),(32,96,160)).save(cover)
for name,rate,bitrate,version in [('mpeg1-v23.mp3',48000,'128k',3),('mpeg2-v24.mp3',24000,'64k',4),('mpeg25-v24.mp3',12000,'32k',4)]:
 args=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',f'sine=frequency=440:sample_rate={rate}']
 if version==3: args+=['-i',str(cover),'-map','0:a','-map','1:v','-c:v','copy','-metadata:s:v','title=Cover','-metadata:s:v','comment=Cover (front)']
 args+=['-t','0.15','-c:a','libmp3lame','-b:a',bitrate,'-id3v2_version',str(version),'-metadata','title=검증 제목','-metadata','artist=Test Artist','-metadata','comment=Comment text','-metadata','custom_key=custom-value',str(out/name)]
 subprocess.run(args,check=True)
