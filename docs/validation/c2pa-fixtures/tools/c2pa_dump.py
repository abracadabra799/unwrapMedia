import sys, struct, os
sys.path.insert(0, os.path.dirname(__file__))
from c2pa_probe import jpeg, bmff, webp
class Tag:
    def __init__(s,t,v): s.t=t; s.v=v
    def __repr__(s): return 'Tag(%d,%r)'%(s.t,s.v)
def cbor(b, i=0):
    ib=b[i]; mt=ib>>5; ai=ib&31; i+=1
    def arg(ai,i):
        if ai<24: return ai,i
        n={24:1,25:2,26:4,27:8}[ai]; return int.from_bytes(b[i:i+n],'big'), i+n
    if mt==7:
        if ai==20: return False,i
        if ai==21: return True,i
        if ai==22: return None,i
        if ai==25: return struct.unpack('>e',b[i:i+2])[0],i+2
        if ai==26: return struct.unpack('>f',b[i:i+4])[0],i+4
        if ai==27: return struct.unpack('>d',b[i:i+8])[0],i+8
        return ('simple',ai),i
    if ai==31:
        if mt in (2,3):
            parts=[]
            while b[i]!=0xff: v,i=cbor(b,i); parts.append(v)
            return (b''.join(parts) if mt==2 else ''.join(parts)), i+1
        if mt==4:
            out=[]
            while b[i]!=0xff: v,i=cbor(b,i); out.append(v)
            return out,i+1
        if mt==5:
            out={}
            while b[i]!=0xff: k,i=cbor(b,i); v,i=cbor(b,i); out[k if not isinstance(k,(list,dict)) else repr(k)]=v
            return out,i+1
    n,i=arg(ai,i)
    if mt==0: return n,i
    if mt==1: return -1-n,i
    if mt==2: return b[i:i+n],i+n
    if mt==3: return b[i:i+n].decode('utf8'),i+n
    if mt==4:
        out=[]
        for _ in range(n): v,i=cbor(b,i); out.append(v)
        return out,i
    if mt==5:
        out={}
        for _ in range(n): k,i=cbor(b,i); v,i=cbor(b,i); out[k]=v
        return out,i
    if mt==6:
        v,i=cbor(b,i); return Tag(n,v),i
def boxes(b):
    p=0
    while p+8<=len(b):
        L,t=struct.unpack('>I4s',b[p:p+8]); h=8
        if L==1: L=struct.unpack('>Q',b[p+8:p+16])[0]; h=16
        if L==0: L=len(b)-p
        yield t.decode('latin1'), b[p+h:p+L], p, L
        p+=L
def label_of(jumb_body):
    for t,body,_,_ in boxes(jumb_body):
        if t=='jumd':
            tog=body[16]; lab=''
            if tog&2: e=body.index(0,17); lab=body[17:e].decode()
            return body[:16], tog, lab
def short(v, n=200):
    s=repr(v); return s if len(s)<n else s[:n]+'…'
if __name__ == '__main__':
  for f in sys.argv[1:]:
        d=open(f,'rb').read()
        import io, contextlib
        with contextlib.redirect_stdout(io.StringIO()):
            data = jpeg(d) if d[:2]==b'\xff\xd8' else (webp(d) if d[:4]==b'RIFF' else bmff(d))
        print('######', os.path.basename(f))
        for t,store,_,_ in boxes(data):
            for t2,man,_,_ in boxes(store):
                if t2!='jumb': continue
                u,tog,ml=label_of(man); print('MANIFEST',ml)
                for t3,sub,_,_ in boxes(man):
                    if t3!='jumb': continue
                    u3,tog3,l3=label_of(sub)
                    contents=[(t,body) for t,body,_,_ in boxes(sub) if t!='jumd']
                    if l3.startswith('c2pa.claim'):
                        v,_=cbor(contents[0][1]); print(' CLAIM',l3,short(v,1200))
                    elif l3=='c2pa.signature':
                        v,_=cbor(contents[0][1]); prot,_=cbor(v.v[0]); unprot=v.v[1]
                        print(' SIG tag',v.t,'protected',short(prot,300),'unprot keys',list(unprot.keys()) if isinstance(unprot,dict) else unprot, 'payload',v.v[2], 'siglen',len(v.v[3]))
                    elif l3=='c2pa.assertions':
                        for t4,asub,_,_ in boxes(sub):
                            if t4!='jumb': continue
                            u4,tog4,l4=label_of(asub)
                            cs=[(t,body) for t,body,_,_ in boxes(asub) if t!='jumd']
                            kinds=[t for t,_ in cs]
                            if 'cbor' in kinds:
                                v,_=cbor(dict(cs)['cbor']); print('  A',l4,'tog=%02x'%tog4,short(v,400))
                            elif 'json' in kinds: print('  A',l4,'json',dict(cs)['json'][:100])
                            else: print('  A',l4,kinds,[len(b) for _,b in cs], dict(cs).get('bfdb',b'')[:30])
