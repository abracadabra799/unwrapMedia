import sys, struct, os
C2PA_UUID=bytes.fromhex('d8fec3d61b0e483c92975828877ec481')
def jumbf_tree(b, depth=0, out=None, maxdepth=4):
    out = out if out is not None else []
    p=0
    while p+8<=len(b):
        L,t=struct.unpack('>I4s',b[p:p+8]); h=8
        if L==1: L=struct.unpack('>Q',b[p+8:p+16])[0]; h=16
        if L==0: L=len(b)-p
        t=t.decode('latin1'); body=b[p+h:p+L]
        if t=='jumd':
            uuid=body[:16]; tog=body[16]; lab=b''
            q=17
            if tog&2:
                e=body.index(0,q); lab=body[q:e]; q=e+1
            out.append('  '*depth+'jumd type=%s toggles=%02x label=%s'%(uuid[:4]+b'' if uuid[:4].isascii() else uuid[:4].hex(), tog, lab.decode('utf8','replace')))
        elif t=='jumb':
            out.append('  '*depth+'jumb %d'%L)
            if depth<maxdepth: jumbf_tree(body, depth+1, out)
        else:
            out.append('  '*depth+'%s %d %s'%(t,L, body[:24].hex() if t not in ('json',) else body[:60]))
        p+=L
    return out
def jpeg(d):
    p=2; segs=[]
    while p<len(d) and d[p]==0xFF:
        m=d[p+1]
        if m==0xDA: break
        if m==0xD8 or 0xD0<=m<=0xD7: p+=2; continue
        L=struct.unpack('>H',d[p+2:p+4])[0]
        if m==0xEB: segs.append((p,d[p+4:p+2+L]))
        p+=2+L
    print(' APP11 segments:',len(segs))
    groups={}
    for off,s in segs:
        ci=s[:2]; en=struct.unpack('>H',s[2:4])[0]; z=struct.unpack('>I',s[4:8])[0]
        lbox,tbox=struct.unpack('>I4s',s[8:16])
        if len(groups)<6 and z<=2: print('  @%d CI=%r En=%d Z=%d LBox=%d TBox=%r payload=%d'%(off,ci,en,z,lbox,tbox,len(s)-8))
        groups.setdefault((en,lbox),[]).append(s)
    for (en,lbox),ss in groups.items():
        data=ss[0][8:]+b''.join(x[16:] for x in ss[1:])
        print(' group En=%d segs=%d assembled=%d LBox=%d'%(en,len(ss),len(data),lbox))
        return data
def bmff(d):
    p=0; found=None; tops=[]
    while p+8<=len(d):
        L,t=struct.unpack('>I4s',d[p:p+8]); h=8
        if L==1: L=struct.unpack('>Q',d[p+8:p+16])[0]; h=16
        if L==0: L=len(d)-p
        tops.append(t.decode('latin1'))
        if t==b'uuid' and d[p+h:p+h+16]==C2PA_UUID:
            ver_flags=d[p+h+16:p+h+20]; q=p+h+20
            e=d.index(0,q); purpose=d[q:e]; q=e+1
            aux=struct.unpack('>Q',d[q:q+8])[0]; q+=8
            print(' C2PA uuid @%d size %d vf=%s purpose=%s merkle_offset=%d'%(p,L,ver_flags.hex(),purpose,aux))
            if purpose==b'manifest': found=d[q:p+L]
        p+=L
    print(' top boxes:',tops)
    return found
def webp(d):
    p=12; found=None; ch=[]
    while p+8<=len(d):
        t=d[p:p+4]; L=struct.unpack('<I',d[p+4:p+8])[0]; ch.append(t.decode('latin1'))
        if t==b'C2PA': found=d[p+8:p+8+L]; print(' C2PA chunk @%d len %d'%(p,L))
        p+=8+L+(L&1)
    print(' chunks:',ch); return found
if __name__ == '__main__':
  for f in sys.argv[1:]:
        d=open(f,'rb').read(); print('==',os.path.basename(f),len(d))
        if d[:2]==b'\xff\xd8': data=jpeg(d)
        elif d[:4]==b'RIFF': data=webp(d)
        else: data=bmff(d)
        if data:
            for line in jumbf_tree(data, maxdepth=3)[:40]: print('   ',line)
