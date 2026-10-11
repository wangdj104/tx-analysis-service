import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'collect-native-diagnostics.py'
class DiagnosticsTest(unittest.TestCase):
    def test_bounded_failure_only_and_secret_redaction(self):
        spec = importlib.util.spec_from_file_location('diagnostics', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        result = module.summarize(b'''<testsuite tests="2" failures="1"><properties><property name="password" value="hidden"/></properties><testcase name="bad" classname="Synthetic"><failure type="AssertionError">expected row; password=hidden; abcdef0123456789abcdef0123456789</failure><system-out>excluded raw SQL data</system-out></testcase><testcase name="ok"/></testsuite>''')
        rendered = str(result)
        self.assertIn('expected row', rendered)
        self.assertNotIn('hidden', rendered)
        self.assertNotIn('abcdef0123456789abcdef0123456789', rendered)
        self.assertNotIn('excluded raw SQL data', rendered)
        self.assertEqual(1, len(result['problems']))
    def test_quoted_secrets_and_bearer_are_hidden_and_counts_bounded(self):
        spec = importlib.util.spec_from_file_location('diagnostics', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        text = '{"password":"short secret", "token": "abc-def_123"} Authorization: Bearer abc-def_456 secret=plain'
        clean = module.scrub(text)
        for value in ('short secret', 'abc-def_123', 'abc-def_456', 'plain'):
            self.assertNotIn(value, clean)
        result = module.summarize(b'<testsuite tests="private-data" failures="' + b'9' * 100000 + b'"/>')
        self.assertEqual({'tests': None, 'failures': None, 'errors': None, 'skipped': None}, result['counts'])

    def test_rejects_oversize_and_malformed_xml(self):
        spec = importlib.util.spec_from_file_location('diagnostics', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for value in (b'x' * (64 * 1024 * 1024 + 1), b'<invalid', b'<!DOCTYPE x><testsuite/>', '<!DOCTYPE testsuite [<!ENTITY x "expanded">]><testsuite/>'.encode('utf-16')):
            with self.assertRaises(ValueError): module.summarize(value)

if __name__ == '__main__': unittest.main()
