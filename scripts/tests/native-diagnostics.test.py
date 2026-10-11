import importlib.util
from pathlib import Path
import unittest
import json
import os
import subprocess
import sys
import tempfile

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

    def module(self):
        spec = importlib.util.spec_from_file_location('diagnostics', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def browser_fixture(self):
        return {'version': 1, 'status': 'failed', 'stage': 'production-jar-list',
                'completedStages': ['environment-guard', 'report-font'],
                'failure': {'stage': 'production-jar-list', 'error': {'name': 'Error', 'reason': 'capture-limit-exceeded', 'signal': 'SIGTERM'}},
                'bootFailure': None, 'backendFailure': None}

    def test_browser_stage_summary_rejects_unknown_fields_values_and_size(self):
        module = self.module()
        self.assertTrue(callable(getattr(module, 'summarize_browser', None)), 'browser diagnostics validator is required')
        source = self.browser_fixture()
        self.assertEqual(source, module.summarize_browser(json.dumps(source).encode()))
        invalid = [dict(source, message='private patient'), dict(source, stage='private patient'),
                   dict(source, completedStages=['private patient']), dict(source, status='private patient'),
                   dict(source, failure={'stage': 'production-jar-list', 'error': {'name': 'Error', 'reason': 'private secret'}}),
                   dict(source, failure={'stage': 'production-jar-list', 'error': {'name': 'private secret', 'reason': 'unclassified'}}),
                   dict(source, bootFailure='CARE_PLAN_BROWSER_BOOT_FAILURE java.lang.Error password=hidden'),
                   dict(source, backendFailure={'name': 'Error', 'reason': 'command-failed', 'exitCode': True}),
                   dict(source, status='passed')]
        for value in invalid:
            with self.assertRaises(ValueError): module.summarize_browser(json.dumps(value).encode())
        for value in (b'x' * 16385, b'{invalid', b'{"version":1,"version":2}'):
            with self.assertRaises(ValueError): module.summarize_browser(value)

    def test_browser_diagnostic_file_refuses_symlink_and_oversize_input(self):
        module = self.module()
        self.assertTrue(callable(getattr(module, 'read_browser_diagnostics', None)), 'safe browser diagnostics reader is required')
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            source = base / 'report.json'
            source.write_text(json.dumps(self.browser_fixture()))
            self.assertEqual('failed', module.read_browser_diagnostics(source)['status'])
            link = base / 'link.json'
            link.symlink_to(source)
            with self.assertRaises(ValueError): module.read_browser_diagnostics(link)
            directory = base / 'target'
            directory.symlink_to(base, target_is_directory=True)
            with self.assertRaises(ValueError): module.read_browser_diagnostics(directory / 'report.json')
            source.write_bytes(b'x' * 16385)
            with self.assertRaises(ValueError): module.read_browser_diagnostics(source)

    def test_collector_preserves_native_pass_and_explicit_missing_or_rejected_browser_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            target = base / 'target'
            (target / 'surefire-reports').mkdir(parents=True)
            (target / 'surefire-reports' / 'TEST-org.familyhealthcare.service.CarePlanMysqlIntegrationTest.xml').write_text('<testsuite tests="11" failures="0" errors="0" skipped="0"/>')
            output = base / 'output'
            def collect():
                subprocess.run([sys.executable, str(SCRIPT), '.', str(output)], cwd=base, check=True, env={**os.environ, 'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123'})
                return json.loads((output / 'native-diagnostics.json').read_text())
            result = collect()
            self.assertEqual('missing', result.get('browserDiagnosticStatus'), 'missing browser diagnostics must not be mistaken for browser success or failure')
            self.assertEqual(11, result['counts']['tests'])
            report = target / 'care-plan-browser-diagnostics.json'
            report.write_text(json.dumps(self.browser_fixture()))
            result = collect()
            self.assertEqual('present', result['browserDiagnosticStatus'])
            self.assertEqual('capture-limit-exceeded', result['browser']['failure']['error']['reason'])
            report.write_text('{"message":"private patient"}')
            result = collect()
            self.assertEqual('rejected', result['browserDiagnosticStatus'])
            self.assertEqual(11, result['counts']['tests'])
            self.assertNotIn('private patient', json.dumps(result))

if __name__ == '__main__': unittest.main()
