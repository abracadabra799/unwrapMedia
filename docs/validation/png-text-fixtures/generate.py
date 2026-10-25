"""Real Pillow11.3/lcms-generated PNG text and ICC fixtures."""
from pathlib import Path
from PIL import Image,ImageCms,PngImagePlugin
root=Path(__file__).resolve().parents[3];out=root/'app/src/test/resources/png-text';out.mkdir(parents=True,exist_ok=True)
im=Image.new('RGB',(4,4),'red')
info=PngImagePlugin.PngInfo()
info.add_text('Author','Cafe Latin-1: café')
info.add_text('Description','Compressed Latin-1: déjà vu',zip=True)
info.add_itxt('Title','검증 이미지',lang='ko',tkey='제목',zip=False)
info.add_itxt('Comment','압축된 설명',lang='ko-KR',tkey='설명',zip=True)
profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
im.save(out/'metadata.png',pnginfo=info,icc_profile=profile)
(out/'reference.icc').write_bytes(profile)
im.save(out/'default.png')
