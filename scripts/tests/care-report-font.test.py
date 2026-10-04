"""Real synthetic-font tests; these never claim the fixtures are CI CJK acceptance."""
import importlib.util
from pathlib import Path
import tempfile
import sys
sys.dont_write_bytecode = True
import unittest
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTCollection, TTFont

path = Path(__file__).resolve().parents[1] / 'prepare-care-report-font.py'
module = None
if path.exists():
    spec = importlib.util.spec_from_file_location('care_report_font', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)


def fixture_font():
    font = FontBuilder(1000, isTTF=True)
    font.setupGlyphOrder(['.notdef', 'A', 'uni4E2D'])
    font.setupCharacterMap({65: 'A', 0x4E2D: 'uni4E2D'})
    pen = TTGlyphPen(None)
    pen.moveTo((0, 0)); pen.lineTo((400, 0)); pen.lineTo((200, 700)); pen.closePath()
    glyph = pen.glyph()
    font.setupGlyf({name: glyph for name in ['.notdef', 'A', 'uni4E2D']})
    font.setupHorizontalMetrics({name: (600, 10) for name in ['.notdef', 'A', 'uni4E2D']})
    font.setupHorizontalHeader(ascent=800, descent=-200)
    font.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    font.setupNameTable({'familyName': 'Synthetic complete face', 'styleName': 'Regular', 'copyright': 'Synthetic test license notice', 'licenseDescription': 'Synthetic license retained'})
    font.setupPost(); font.setupMaxp()
    return font.font


class CompleteFaceTests(unittest.TestCase):
    def test_complete_face_round_trip_preserves_every_table_and_mapping(self):
        self.assertTrue(callable(getattr(module, 'extract_complete_face', None)), 'Complete-face extractor required')
        with tempfile.TemporaryDirectory() as root:
            source, output = Path(root) / 'source.ttc', Path(root) / 'output.ttf'
            collection = TTCollection(); collection.fonts = [fixture_font(), fixture_font()]; collection.save(source)
            summary = module.extract_complete_face(source, output)
            self.assertEqual(summary['glyphCount'], 3)
            self.assertEqual(summary['unicodeCodepointCount'], 2)
            self.assertEqual(TTFont(output).getBestCmap(), {65: 'A', 0x4E2D: 'uni4E2D'})
            self.assertEqual(len(summary['sourceSha256']), 64)
            self.assertEqual(len(summary['ttfSha256']), 64)
            with self.assertRaisesRegex(AssertionError, 'exists'):
                module.extract_complete_face(source, output)

    def test_preservation_check_rejects_mapping_outline_metrics_and_license_changes(self):
        self.assertTrue(callable(getattr(module, 'assert_complete_face', None)), 'Complete-face verification required')
        with tempfile.TemporaryDirectory() as root:
            source, output = Path(root) / 'source.ttc', Path(root) / 'output.ttf'
            collection = TTCollection(); collection.fonts = [fixture_font()]; collection.save(source)
            for mutation in ['cmap', 'glyf', 'hmtx', 'name']:
                with TTFont(source, fontNumber=0, recalcTimestamp=False, recalcBBoxes=False) as font:
                    if mutation == 'cmap':
                        for table in font['cmap'].tables:
                            table.cmap.pop(0x4E2D, None)
                    elif mutation == 'glyf': font['glyf']['A'].coordinates[0] = (80, 90)
                    elif mutation == 'hmtx': font['hmtx'].metrics['A'] = (700, 10)
                    else: font['name'].setName('Changed license', 13, 3, 1, 0x409)
                    font.save(output)
                with self.assertRaisesRegex(AssertionError, 'preserv|mapping|glyph|table'):
                    module.assert_complete_face(source, output)

    def test_extraction_refuses_symlinks_and_existing_destinations(self):
        self.assertTrue(callable(getattr(module, 'extract_complete_face', None)), 'Complete-face extractor required')
        with tempfile.TemporaryDirectory() as root:
            source, output = Path(root) / 'source.ttc', Path(root) / 'output.ttf'
            source.symlink_to('/nonexistent-sensitive')
            with self.assertRaisesRegex(AssertionError, 'symlink'):
                module.extract_complete_face(source, output)

if __name__ == '__main__': unittest.main()
