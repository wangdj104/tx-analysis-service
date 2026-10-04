# PDF font regression fixtures

These reduced fonts are test fixtures only, not production assets.

- `wqy-report-subset.ttf`: subset of the first WenQuanYi Micro Hei TrueType collection face, retaining report/test characters and its original naming/copyright metadata. Source: Debian's official `fonts-wqy-microhei_0.2.0-beta-4_all.deb`, https://deb.debian.org/debian/pool/main/f/fonts-wqy-microhei/. Used under its Apache-2.0 option; see WQY-COPYRIGHT.txt and Apache-2.0.txt
- `noto-cff-subset.ttc`: one-face CFF collection subset from Noto Sans CJK Regular containing the characters 患者测试. It deliberately reproduces PDFBox's unsupported CFF/glyf case. Source: the distribution's fonts-noto-cjk package; upstream https://github.com/notofonts/noto-cjk. See NOTO-COPYRIGHT.txt for SIL Open Font License and notices

Both were subset with fontTools, preserving the underlying outline format. The supported fixture includes actual Chinese glyphs so tests assert extractable Chinese text, not merely a `%PDF` header. Production installs the complete WenQuanYi font from the distribution package manager.

- `wqy-mixed-coverage.ttc`: two derived WenQuanYi faces, ASCII-only first and the complete report subset second, for collection fallback regression. The Apache-2.0 notices above apply to both.

## Execution-report fixture (Task 5)

`wqy-care-report-subset.ttf` is a test-only expanded subset with 515 real glyph mappings
for the synthetic English/Chinese execution-report template and test text. No glyphs
were invented, reassigned, or substituted. The original WQY copyright and Apache-2.0
license above apply. It is not a production font or a claim of universal Unicode coverage.

Source: the already-retained official Debian `fonts-wqy-microhei_0.2.0-beta-4_all.deb`.
The package SHA-256 was independently checked on 2026-10-04 against
https://packages.debian.org/sid/all/fonts-wqy-microhei/download :
`3fb0ff79033124b863bf9d28af56c62646d5b74774d26be0ce8a5beefe2fed55` (1603588 bytes).
The extracted `wqy-microhei.ttc` was compared byte-for-byte with the package entry:
SHA-256 `2420e8078af796b19a3f6ef13de527a1a91c1e7171eea115926c614ced1009b3`.
Fixture SHA-256: `71017306b753ec8311110368dfc0f58a0b63d286e62e97b2a4c0395a08bc1e14`.

Reproduce from the verified source with fontTools 4.61.1 (the checked-in Unicode
manifest deliberately fixes the fixture repertoire independently of later source edits):

```python
from fontTools import subset
from fontTools.ttLib import TTFont
from pathlib import Path
font = TTFont('wqy-microhei.ttc', fontNumber=0)
characters = [int(c.strip()[2:], 16) for c in Path('care-report-unicodes.txt').read_text().split(',')]
options = subset.Options()
options.name_IDs = ['*']
options.name_languages = ['*']
options.name_legacy = True
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=characters)
subsetter.subset(font)
font.recalcTimestamp = False
font.save('wqy-care-report-subset.ttf')
```

Tests explicitly reject unsupported emoji, combining sequences, variation selectors,
joiners, supplementary ideographs, unpaired surrogates, missing glyphs and CFF-only fonts.
The current stack has no verified shaping path for these sequences; an explicit HTML
fallback error is safer than silently losing original characters. New glyph claims must
include successful real-font rendering and page-by-page pixel inspection.

## Covered-glyph bidi/shaping failure fixture (Task 5 review fix I1)

`dejavu-bidi-subset.ttf` is a 105-codepoint test-only subset of DejaVu Sans with
real ASCII, Arabic, Hebrew and Lao glyphs. Its family/full/PostScript names are
changed to `Care Report Bidi Fixture` / `CareReportBidiFixture`; original copyright
and license metadata are retained. See `DEJAVU-COPYRIGHT.txt` for the Bitstream
Vera permission notice and the public-domain status of DejaVu changes. This font
is not a production asset or a bidi/shaping implementation.

Official source package:
https://deb.debian.org/debian/pool/main/f/fonts-dejavu/fonts-dejavu-core_2.37-8_all.deb
Its SHA-256 was independently checked on 2026-10-04 against
https://packages.debian.org/trixie/all/fonts-dejavu-core/download :
`86635b3d25b3655fc11cb3ecc3af59f0bf19643b02b94f2de48bd10253cdba12` (840460 bytes).
The package was extracted without installing it. Its `DejaVuSans.ttf` is identical
to the already-installed font used by the reviewer:
`57f73e11f51999432bf7ab22ce55b6f945d5eca1bf824404cfa9ec2e3718c84e`.
Fixture SHA-256: `d356066c4769043204f0233cf6d74c70dd08bcf8d937c4f49c85babb392b8ca3`.

Reproduce with fontTools 4.61.1 and the checked-in `bidi-probe-unicodes.txt`:

```python
from fontTools import subset
from fontTools.ttLib import TTFont
from pathlib import Path
font = TTFont('DejaVuSans.ttf')
characters = [int(c.strip()[2:], 16) for c in Path('bidi-probe-unicodes.txt').read_text().split(',')]
options = subset.Options()
options.name_IDs = ['*']
options.name_languages = ['*']
options.name_legacy = True
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=characters)
subsetter.subset(font)
names = {1: 'Care Report Bidi Fixture', 2: 'Regular', 3: 'CareReportBidiFixture-1.0',
         4: 'Care Report Bidi Fixture', 6: 'CareReportBidiFixture',
         16: 'Care Report Bidi Fixture', 17: 'Regular'}
for record in font['name'].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(record.getEncoding())
font.recalcTimestamp = False
font.save('dejavu-bidi-subset.ttf')
```

Each regression first proves **all actual visible characters** have nonzero cmap
entries and are encodable by PDFBox, then requires explicit rendering failure for
plain Arabic, plain Hebrew, mixed Arabic/Hebrew, a Lao letter outside the verified
script set, and Common-script Arabic tatweel. These would otherwise succeed with
this font, so the tests do not depend on incidental missing-glyph rejection.

The strict capability policy accepts only BMP Latin, Han, Hiragana, Katakana,
precomposed Hangul syllables (U+AC00–U+D7A3), and non-RTL Common-script characters,
subject to the existing exclusion of marks, format/control characters, invalid
surrogates and unsupported glyphs. Tab/CR/LF remain ordinary layout whitespace.
Right-to-left/Arabic directionality is always rejected, including Common-script
characters; every other script and conjoining Hangul Jamo is declined until an
independently verified shaping/bidi path is intentionally provided. Original
text is never normalized, reordered or rewritten to fit this policy. The error
remains `REPORT_RENDER_UNAVAILABLE` with the HTML alternative.
