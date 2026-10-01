# PDF font regression fixtures

These reduced fonts are test fixtures only, not production assets.

- `wqy-report-subset.ttf`: subset of the first WenQuanYi Micro Hei TrueType collection face, retaining report/test characters and its original naming/copyright metadata. Source: Debian's official `fonts-wqy-microhei_0.2.0-beta-4_all.deb`, https://deb.debian.org/debian/pool/main/f/fonts-wqy-microhei/. Used under its Apache-2.0 option; see WQY-COPYRIGHT.txt and Apache-2.0.txt
- `noto-cff-subset.ttc`: one-face CFF collection subset from Noto Sans CJK Regular containing the characters 患者测试. It deliberately reproduces PDFBox's unsupported CFF/glyf case. Source: the distribution's fonts-noto-cjk package; upstream https://github.com/notofonts/noto-cjk. See NOTO-COPYRIGHT.txt for SIL Open Font License and notices

Both were subset with fontTools, preserving the underlying outline format. The supported fixture includes actual Chinese glyphs so tests assert extractable Chinese text, not merely a `%PDF` header. Production installs the complete WenQuanYi font from the distribution package manager.

- `wqy-mixed-coverage.ttc`: two derived WenQuanYi faces, ASCII-only first and the complete report subset second, for collection fallback regression. The Apache-2.0 notices above apply to both.
