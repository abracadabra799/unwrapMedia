# PNG detailed metadata fixtures

Run generate.py with Pillow11.3.0; dump.py uses only Python standard library and independently verifies each chunk CRC before dumping offsets and bytes. Files are generated colors, not private/user media. Pillow encodes the indexed/gray/RGB PNG and APNG metadata. HDR metadata values are deliberate test values inserted using Pillow's putchunk API, not a claim about measured luminance of the generated red image.

Primary source: [W3C PNG Third Edition](https://www.w3.org/TR/png-3/) §§11.3.1.1(tRNS),11.3.2.4(sBIT),11.3.2.6–8(cICP/mDCV/cLLI),11.3.4.1–4(bKGD/hIST/sPLT),11.3.6(APNG). mDCV uses RGB primary order, xy units0.00002; luminance and cLLI use0.0001cd/m². cLLI zero means unknown. fcTL delay_den0 means100. fdAT's compressed frame_data may be empty while its sequence_number must be present.
