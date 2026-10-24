"""Independent ID3 frame/header inventory; does not use application parsers."""
from pathlib import Path
import sys
for filename in sys.argv[1:]:
 b=Path(filename).read_bytes();v=b[3];size=sum(x<<(7*(3-i)) for i,x in enumerate(b[6:10]));end=10+size
 print(filename,'ID3 version',v,'tag_size',size,'header',b[:10].hex());p=10
 while p+10<=end and b[p]!=0:
  name=b[p:p+4].decode('ascii');n=sum(x<<(7*(3-i)) for i,x in enumerate(b[p+4:p+8])) if v==4 else int.from_bytes(b[p+4:p+8],'big')
  print('frame',name,'offset',p,'size',n,'flags',b[p+8:p+10].hex(),'hex',b[p+10:p+10+n].hex());p+=10+n
 p=end
 while p+4<=len(b):
  if b[p]==255 and b[p+1]&224==224:
   print('MPEG header','offset',p,'hex',b[p:p+4].hex(),'version_bits',(b[p+1]>>3)&3,'bitrate_index',b[p+2]>>4,'sample_rate_index',(b[p+2]>>2)&3);break
  p+=1
