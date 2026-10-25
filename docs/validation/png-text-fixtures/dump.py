from pathlib import Path
import zlib,struct
root=Path(__file__).resolve().parents[3]
for file in sorted((root/'app/src/test/resources/png-text').glob('*.png')):
 data=file.read_bytes();pos=8;print(file.name,len(data))
 while pos<len(data):
  n=int.from_bytes(data[pos:pos+4],'big');kind=data[pos+4:pos+8];p=pos+8;body=data[p:p+n]
  assert zlib.crc32(kind+body)==int.from_bytes(data[p+n:p+n+4],'big')
  print(kind.decode(),pos,n)
  if kind in [b'tEXt',b'zTXt',b'iTXt',b'iCCP']:
   name,rest=body.split(b'\0',1);q=p+len(name)+1;print(' name',repr(name),'at',p)
   if kind in [b'zTXt',b'iCCP']:
    print(' method',rest[0],'at',q);rest=rest[1:];q+=1
   elif kind==b'iTXt':
    flags,method=rest[:2];q+=2;rest=rest[2:]
    lang,rest=rest.split(b'\0',1);print(' language',repr(lang),'at',q);q+=len(lang)+1
    translated,rest=rest.split(b'\0',1);print(' translated',repr(translated),'at',q);q+=len(translated)+1
    print(' flag/method',flags,method)
   raw=zlib.decompress(rest) if kind in [b'zTXt',b'iCCP'] or kind==b'iTXt' and flags==1 else rest
   print(' value_source',q,len(rest),'decoded_length',len(raw))
   if kind==b'iCCP':print(' ICC size',int.from_bytes(raw[:4],'big'),'signature',raw[36:40],'color',raw[16:20],'tag_count',int.from_bytes(raw[128:132],'big'))
   else:print(' value',repr(raw))
  pos+=n+12
