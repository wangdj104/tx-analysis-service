#!/usr/bin/python3
"""CI-only complete official WQY face-0 extraction. Never subsets or remaps."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
from fontTools import __version__ as fonttools_version
from fontTools.ttLib import TTFont

SOURCE = Path('/usr/share/fonts/truetype/wqy/wqy-microhei.ttc')
COPYRIGHT = Path('/usr/share/doc/fonts-wqy-microhei/copyright')
MAX_FONT_BYTES = 30 * 1024 * 1024


def regular_file(path):
    path = Path(path)
    for ancestor in [*reversed(path.parents), path]:
        assert not ancestor.is_symlink(), 'Font path must never contain a symlink'
    metadata = path.stat()
    assert stat.S_ISREG(metadata.st_mode) and 0 < metadata.st_size <= MAX_FONT_BYTES, 'Font file must be regular and bounded'
    return metadata


def sha256(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unicode_maps(font):
    return [(table.platformID, table.platEncID, table.language, table.format, dict(table.cmap))
            for table in font['cmap'].tables if table.isUnicode()]


def assert_complete_face(source, output):
    regular_file(source); regular_file(output)
    with TTFont(source, fontNumber=0, recalcTimestamp=False, recalcBBoxes=False) as original, \
            TTFont(output, recalcTimestamp=False, recalcBBoxes=False) as selected:
        original_tags = set(original.reader.keys())
        assert original_tags == set(selected.reader.keys()), 'Font table set must be preserved'
        assert {'cmap', 'glyf', 'loca', 'hmtx', 'hhea', 'head', 'maxp', 'name'} <= original_tags, 'Complete TrueType tables required'
        assert original.getGlyphOrder() == selected.getGlyphOrder(), 'Full glyph order must be preserved'
        assert unicode_maps(original) == unicode_maps(selected), 'Every Unicode mapping must be preserved'
        # Compare the original reader bytes, not recompiled/decompiled approximations.
        # All outlines, hinting, metrics, names, copyright/license and optional tables remain intact.
        for tag in original_tags:
            before, after = original.reader[tag], selected.reader[tag]
            if tag == 'head':
                before, after = before[:8] + b'\0\0\0\0' + before[12:], after[:8] + b'\0\0\0\0' + after[12:]
            assert before == after, 'Complete font table bytes must be preserved: ' + tag
        return {'sourceSha256': sha256(source), 'ttfSha256': sha256(output),
                'glyphCount': len(original.getGlyphOrder()),
                'unicodeCmapCount': len(unicode_maps(original)),
                'unicodeCodepointCount': len(set().union(*(set(item[4]) for item in unicode_maps(original)))),
                'tableCount': len(original_tags), 'nameSha256': hashlib.sha256(original.reader['name']).hexdigest()}


def extract_complete_face(source, output):
    source, output = Path(source), Path(output)
    regular_file(source)
    assert not output.exists() and not output.is_symlink(), 'Font destination already exists'
    for ancestor in [*reversed(output.parents)]:
        assert ancestor.is_dir() and not ancestor.is_symlink(), 'Font destination ancestor must be a real directory'
    # Lazy untouched tables are copied whole; do not call fontTools.subset or change cmap/name/glyph data.
    with TTFont(source, fontNumber=0, recalcTimestamp=False, recalcBBoxes=False, lazy=True) as face:
        face.save(output)
    try:
        return assert_complete_face(source, output)
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def package_version(package):
    value = subprocess.check_output(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package], text=True, timeout=10).strip()
    assert re.fullmatch(r'[A-Za-z0-9.+:~_-]{1,100}', value), 'Bounded package version required'
    return value


def main():
    assert len(sys.argv) == 1 and os.environ.get('CI') == 'true' and os.environ.get('GITHUB_ACTIONS') == 'true', 'Official CI font preparation only'
    temporary = Path(os.environ.get('RUNNER_TEMP', ''))
    assert temporary.is_absolute() and '..' not in temporary.parts and str(temporary) == os.environ.get('RUNNER_TEMP'), 'Absolute owned runner temp required'
    for directory in [*reversed(temporary.parents), temporary]:
        assert directory.is_dir() and not directory.is_symlink(), 'Runner temp ancestors must be real directories'
    assert temporary.stat().st_uid == os.getuid(), 'Owned runner temp required'
    environment = Path(os.environ.get('GITHUB_ENV', ''))
    assert environment.is_absolute() and temporary in environment.parents and not any(c in str(environment) for c in '\r\n'), 'Owned CI environment file required'
    regular_file_metadata = environment.lstat()
    assert stat.S_ISREG(regular_file_metadata.st_mode) and regular_file_metadata.st_uid == os.getuid(), 'Owned CI environment file required'
    for ancestor in [*reversed(environment.parents), environment]:
        assert not ancestor.is_symlink(), 'CI environment path must not be a symlink'
    regular_file(COPYRIGHT)
    assert COPYRIGHT.stat().st_size <= 64 * 1024, 'Bounded package copyright required'
    directory = temporary / 'care-report-font'
    directory.mkdir(mode=0o700)  # Never adopt a preexisting font/configuration directory.
    output = directory / 'wqy-microhei.ttf'
    summary = extract_complete_face(SOURCE, output)
    assert re.fullmatch(r'[A-Za-z0-9.+_-]{1,64}', fonttools_version), 'Bounded fonttools version required'
    summary.update({'fontPackageVersion': package_version('fonts-wqy-microhei'),
                    'fonttoolsPackageVersion': package_version('python3-fonttools'),
                    'fonttoolsVersion': fonttools_version, 'faceIndex': 0,
                    'copyrightSha256': sha256(COPYRIGHT)})
    shutil.copyfile(COPYRIGHT, directory / 'WQY-COPYRIGHT.txt')
    text = json.dumps(summary, indent=2)
    assert len(text.encode()) <= 4096, 'Bounded preparation summary required'
    (directory / 'font-preparation.json').write_text(text, encoding='utf-8')
    # This is the only variable written, and the native wrapper inherits it unchanged.
    assert not any(character in str(output) for character in '\r\n')
    with os.fdopen(os.open(environment, os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW), 'w') as stream:
        stream.write('REPORT_PDF_FONT_PATH=' + str(output) + '\n')
    print('Complete report font prepared; SHA-256: ' + summary['ttfSha256'])


if __name__ == '__main__':
    try:
        main()
    except BaseException:
        print('Complete report font preparation failed', file=sys.stderr)
        sys.exit(1)
