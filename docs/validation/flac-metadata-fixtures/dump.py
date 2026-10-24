"""Independent byte offsets/values, no application parser or Mutagen decoding."""
from pathlib import Path
import struct
root=Path(__file__).resolve().parents[3]
def u(data,pos,n):return int.from_bytes(data[pos:pos+n],'big')
for file in sorted((root/'app/src/test/resources/flac-metadata').glob('*.flac')):
 data=file.read_bytes();pos=4
 print(file.name,len(data))
 while pos<len(data):
  code=data[pos]&127;n=u(data,pos+1,3);p=pos+4;e=p+n
  print('block',code,'offset',pos,'payload',p,'length',n)
  if code==5:
   print('catalog',repr(data[p:p+128].rstrip(b'\0')),'lead_in',u(data,p+128,8),'flags',hex(data[p+136]),'tracks',data[p+395])
   q=p+396
   for _ in range(data[p+395]):
    count=data[q+35]
    print(' track',q,'offset',u(data,q,8),'number',data[q+8],'ISRC',repr(data[q+9:q+21]),'flags',hex(data[q+21]),'indices',count)
    q+=36
    for _ in range(count):
     print('  index',q,'offset',u(data,q,8),'number',data[q+8]);q+=12
   assert q==e
  elif code==6:
   q=p;print(' picture_type',u(data,q,4));q+=4
   for name in ['mime','description']:
    length=u(data,q,4);q+=4;print(' ',name,'offset',q,'length',length,'text',data[q:q+length].decode('utf8'));q+=length
   print(' dimensions',struct.unpack('>IIIII',data[q:q+20]));q+=20
   print(' image_data',q,'length',e-q,'prefix',data[q:min(e,q+40)].hex())
  elif code==4:
   q=p
   length=int.from_bytes(data[q:q+4],'little');q+=4
   print(' vendor',q,length,repr(data[q:q+length]));q+=length
   count=int.from_bytes(data[q:q+4],'little');print(' comment_count',count,'offset',q);q+=4
   for _ in range(count):
    ntext=int.from_bytes(data[q:q+4],'little');q+=4
    print(' comment',q,ntext,repr(data[q:q+ntext]));q+=ntext
  pos=e
  if data[p-4]&128:break
