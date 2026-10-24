"""Independent MOV byte walker; not application parser output."""
from pathlib import Path
import struct,sys
containers={'moov','trak','mdia','minf','stbl','dinf','edts','udta','wave','ilst'}
def walk(b,start,end,path=''):
 p=start
 while p+8<=end:
  n,t=struct.unpack_from('>I4s',b,p);t=t.decode('latin1');h=8
  if n==1:n=int.from_bytes(b[p+8:p+16],'big');h=16
  if n==0:n=end-p
  if n<h or p+n>end:break
  q=p+h;sub=path+'/'+t
  if t in {'keys','data','mp4a','lpcm','fl32','chan','frma','enda'} or t.startswith('©'):
   print(sub,'offset',p,'size',n,'hex',b[q:p+n].hex())
  skip=0 if t in containers or path.endswith('/ilst') else {'meta':0 if b[q+4:q+8]==b'hdlr' else 4,'stsd':8,'lpcm':64, 'fl32':44, 'mp4a':44 if int.from_bytes(b[q+8:q+10],'big')==1 else 64 if int.from_bytes(b[q+8:q+10],'big')==2 else 28,'avc1':78}.get(t)
  if skip is not None:walk(b,q+skip,p+n,sub)
  p+=n
for f in sys.argv[1:]:
 print(f);b=Path(f).read_bytes();walk(b,0,len(b))
