"""Pillow 11.3.0 / libjpeg-generated CMYK JPEG, no inserted synthetic markers."""
from pathlib import Path
from PIL import Image
root = Path(__file__).resolve().parents[3]
path = root / 'app/src/test/resources/detail-parsing/cmyk-adobe.jpg'
Image.new('CMYK', (8, 8), (10, 20, 30, 40)).save(path, 'JPEG', quality=85)
