import sys, os, io, contextlib, subprocess
sys.path.insert(0, os.path.dirname(__file__))
from c2pa_probe import jpeg, bmff, webp
from c2pa_dump import cbor, boxes, label_of
for f in sys.argv[1:]:
    d=open(f,'rb').read()
    with contextlib.redirect_stdout(io.StringIO()):
        data = jpeg(d) if d[:2]==b'\xff\xd8' else (webp(d) if d[:4]==b'RIFF' else bmff(d))
    for t,store,_,_ in boxes(data):
        for t2,man,_,_ in boxes(store):
            if t2!='jumb': continue
            _,_,ml=label_of(man)
            for t3,sub,_,_ in boxes(man):
                if t3=='jumb' and label_of(sub)[2]=='c2pa.signature':
                    c=[b for t,b,_,_ in boxes(sub) if t=='cbor'][0]
                    v,_=cbor(c); prot,_=cbor(v.v[0]); un=v.v[1]
                    ch = prot.get(33) or un.get('x5chain')
                    ch = ch if isinstance(ch,list) else [ch]
                    r=subprocess.run(['openssl','x509','-inform','DER','-noout','-subject','-issuer','-nameopt','RFC2253'],input=ch[0],capture_output=True)
                    print(os.path.basename(f), ml[-12:], 'alg',prot.get(1),'chain',len(ch), r.stdout.decode().strip().replace('\n',' | '))
