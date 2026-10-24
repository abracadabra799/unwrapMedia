"""Pillow 11.3.0 real AVIF fixture with default encoder settings."""
from pathlib import Path
from PIL import Image
root = Path(__file__).resolve().parents[3]
Image.new('RGB', (16, 16), (32, 96, 160)).save(
    root / 'app/src/test/resources/detail-parsing/default-avif.avif', format='AVIF')
