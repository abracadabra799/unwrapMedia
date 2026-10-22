"""Build JPEG motion photos for the Google Motion Photo 1.0 checker from a real JPEG and a real MP4.

Each variant injects an XMP APP1 right after SOI and appends the video exactly as described below.
"""
import sys

jpeg_path, mp4_path, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
jpeg = open(jpeg_path, "rb").read()
video = open(mp4_path, "rb").read()
assert jpeg[:2] == b"\xff\xd8" and video[4:8] == b"ftyp"

CAMERA = "http://ns.google.com/photos/1.0/camera/"
CONTAINER = "http://ns.google.com/photos/1.0/container/"
ITEM = "http://ns.google.com/photos/1.0/container/item/"


def xmp(camera_attrs, items):
    lis = "".join(
        '<rdf:li rdf:parseType="Resource"><Container:Item %s/></rdf:li>'
        % " ".join('Item:%s="%s"' % kv for kv in item)
        for item in items
    ) if items else ""
    directory = "<Container:Directory><rdf:Seq>%s</rdf:Seq></Container:Directory>" % lis if items else ""
    return (
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" xmlns:GCamera="%s" xmlns:Container="%s" xmlns:Item="%s" %s>%s'
        "</rdf:Description></rdf:RDF></x:xmpmeta>" % (CAMERA, CONTAINER, ITEM, camera_attrs, directory)
    ).encode()


def with_xmp(xmp_bytes):
    payload = b"http://ns.adobe.com/xap/1.0/\x00" + xmp_bytes
    return jpeg[:2] + b"\xff\xe1" + (len(payload) + 2).to_bytes(2, "big") + payload + jpeg[2:]


def write(name, data):
    open("%s/%s" % (out_dir, name), "wb").write(data)


# 1. Compliant: video appended right after the EOI, Length = video size, no Padding anywhere.
camera = 'GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="-1"'
write("PXL_compliant.MP.jpg", with_xmp(xmp(camera, [
    [("Semantic", "Primary"), ("Mime", "image/jpeg")],
    [("Semantic", "MotionPhoto"), ("Mime", "video/mp4"), ("Length", str(len(video)))],
])) + video)

# 2. Samsung-style: 161 non-box bytes before the MP4 and a trailer ending "SEFT" after it; Length covers
#    everything after the EOI; Padding="0" on both items; legacy MicroVideo fields; non-"MP" filename.
prefix = b"\x00\x00\x01\x0a" + bytes(157)
trailer = bytes(40) + b"SEFT"
declared = len(prefix) + len(video) + len(trailer)
samsung_camera = camera.replace('"-1"', '"0"') + ' GCamera:MicroVideo="1" GCamera:MicroVideoOffset="%d"' % declared
write("samsung-style.jpg", with_xmp(xmp(samsung_camera, [
    [("Semantic", "Primary"), ("Mime", "image/jpeg"), ("Padding", "0")],
    [("Semantic", "MotionPhoto"), ("Mime", "video/mp4"), ("Length", str(declared)), ("Padding", "0")],
])) + prefix + video + trailer)

# 3. Legacy MicroVideo V1 only (no Container:Directory).
write("legacy-microvideo.MP.jpg", with_xmp(xmp(
    'GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="%d"' % len(video), None,
)) + video)
