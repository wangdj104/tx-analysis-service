#!/usr/bin/env python3
"""Bounded failure-only diagnostics from the disposable CI MySQL test."""
import json
import os
from pathlib import Path
import re
import sys
import stat
import xml.etree.ElementTree as ET


def scrub(text):
    # 只保留失败栈；隐藏口令及长随机值，不发布测试系统输出或环境属性。
    text = re.sub(r'(?i)(authorization\s*:\s*bearer\s+)[^\s,;]+', r'\1[redacted]', text)
    text = re.sub(r'''(?i)(["']?(?:password|token|secret)["']?\s*[=:]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s;,}]+)''', r'\1[redacted]', text)
    text = re.sub(r'[A-Za-z0-9]{24,}', '[redacted-long-value]', text)
    return text[:12000]


def safe_count(value):
    return int(value) if value is not None and re.fullmatch(r'[0-9]{1,9}', value) else None


def summarize(data):
    if len(data) > 64 * 1024 * 1024:
        raise ValueError('report exceeds safe XML bounds')
    try:
        decoded = data.decode('utf-8-sig')
    except UnicodeDecodeError as error:
        raise ValueError('report must be UTF-8') from error
    if '\x00' in decoded or '<!DOCTYPE' in decoded or '<!ENTITY' in decoded:
        raise ValueError('report exceeds safe XML bounds')
    try:
        root = ET.fromstring(decoded)
    except ET.ParseError as error:
        raise ValueError('invalid report XML') from error
    problems = []
    for case in root.findall('testcase'):
        for tag in ('failure', 'error'):
            for issue in case.findall(tag):
                if len(problems) < 12:
                    problems.append({'test': scrub(case.get('name', '')), 'type': scrub(issue.get('type', '')), 'detail': scrub(issue.text or issue.get('message', ''))})
    return {'counts': {key: safe_count(root.get(key)) for key in ('tests', 'failures', 'errors', 'skipped')}, 'problems': problems}


# 固定枚举须与 verify-care-plan-browser.mjs 一致；严格拒绝未知字段，
# 不允许用脱敏后转发任意消息替代白名单校验。
BROWSER_STAGES = frozenset('''environment-guard report-font mysql-guards service-identity artifact-directory
backend-build production-jar production-jar-list production-jar-isolation frontend-source-list frontend-stage
frontend-build backend-classpath backend-launch backend-readiness static-server browser-tests artifact-inventory
recording-probe recording-thumbnail recording-conversion artifact-evidence static-server-stop backend-stop frontend-cleanup'''.split())
ERROR_NAMES = frozenset('Error AssertionError TypeError RangeError SyntaxError AbortError TimeoutError'.split())
ERROR_CODES = frozenset('''ERR_ASSERTION ENOENT EACCES EPERM EEXIST ENOTDIR EISDIR ELOOP ENOSPC EMFILE
EADDRINUSE ECONNREFUSED ECONNRESET ETIMEDOUT ERR_INVALID_ARG_TYPE ERR_OUT_OF_RANGE'''.split())
ERROR_SIGNALS = frozenset('SIGTERM SIGKILL SIGINT SIGABRT SIGSEGV SIGPIPE'.split())
ERROR_REASONS = frozenset('''unclassified environment-care-plan-test-only environment-care-plan-browser-required
 environment-ci environment-github-actions environment-host environment-port environment-database
 environment-distinct-databases environment-user environment-generated-database-password environment-container-id
 environment-project environment-generated-login-password environment-isolated-backend-port environment-isolated-web-port
 font-ci-contract font-runner-temp font-path font-directory font-file font-signature font-metadata-file font-metadata-keys
 font-metadata-hash font-metadata-counts font-metadata-version font-hash-mismatch jar-edition jar-file
 jar-fixture-contamination jar-test-class-contamination service-image service-password-contract service-root-host
 service-socket-identity backend-stopped backend-readiness-timeout artifact-directory-symlink artifact-symlink
 frontend-source-path frontend-source-ancestor frontend-source-symlink frontend-source-file frontend-environment-file
 missing-videos missing-screenshots recording-invalid artifact-type command-failed recording-probe-failed
 capture-command-failed capture-limit-exceeded'''.split())


def valid_error(value):
    return (isinstance(value, dict) and {'name', 'reason'} <= value.keys()
            and value.keys() <= {'name', 'reason', 'code', 'exitCode', 'signal'}
            and isinstance(value['name'], str) and value['name'] in ERROR_NAMES
            and isinstance(value['reason'], str) and value['reason'] in ERROR_REASONS
            and ('code' not in value or isinstance(value['code'], str) and value['code'] in ERROR_CODES)
            and ('signal' not in value or isinstance(value['signal'], str) and value['signal'] in ERROR_SIGNALS)
            and ('exitCode' not in value or type(value['exitCode']) is int and 0 <= value['exitCode'] <= 255))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate browser diagnostic key')
        result[key] = value
    return result


def summarize_browser(data):
    if len(data) > 16384:
        raise ValueError('browser diagnostic exceeds bounds')
    try:
        value = json.loads(data.decode('utf-8'), object_pairs_hook=unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError('invalid browser diagnostic JSON') from error
    keys = {'version', 'status', 'stage', 'completedStages', 'failure', 'bootFailure', 'backendFailure'}
    if not isinstance(value, dict) or value.keys() != keys or type(value['version']) is not int or value['version'] != 1:
        raise ValueError('invalid browser diagnostic schema')
    if not isinstance(value['status'], str) or value['status'] not in {'running', 'passed', 'failed'}:
        raise ValueError('invalid browser diagnostic status')
    if value['stage'] is not None and (not isinstance(value['stage'], str) or value['stage'] not in BROWSER_STAGES):
        raise ValueError('invalid browser diagnostic stage')
    completed = value['completedStages']
    if (not isinstance(completed, list) or len(completed) > len(BROWSER_STAGES)
            or any(not isinstance(stage, str) or stage not in BROWSER_STAGES for stage in completed)
            or len(set(completed)) != len(completed)):
        raise ValueError('invalid browser completed stages')
    failure = value['failure']
    if value['status'] == 'failed':
        if (not isinstance(failure, dict) or failure.keys() != {'stage', 'error'}
                or not isinstance(failure['stage'], str) or failure['stage'] not in BROWSER_STAGES
                or failure['stage'] != value['stage'] or not valid_error(failure['error'])):
            raise ValueError('invalid browser failure')
    elif failure is not None:
        raise ValueError('unexpected browser failure')
    boot = value['bootFailure']
    if boot is not None and (not isinstance(boot, str) or len(boot) > 2048 or not re.fullmatch(
            r'CARE_PLAN_BROWSER_BOOT_FAILURE (?:java|javax|org|com)\.[A-Za-z0-9_.$]+(?: > (?:java|javax|org|com)\.[A-Za-z0-9_.$]+){0,7}', boot)):
        raise ValueError('invalid browser boot diagnostic')
    if value['backendFailure'] is not None and not valid_error(value['backendFailure']):
        raise ValueError('invalid backend failure')
    return value


def read_browser_diagnostics(path):
    # 先校验全部父目录，再打开唯一命名文件；禁止遍历整个 target。
    for ancestor in reversed(path.absolute().parents):
        if ancestor.is_symlink():
            raise ValueError('unsafe browser diagnostic ancestor')
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_size > 16384:
        raise ValueError('unsafe browser diagnostic file')
    with path.open('rb') as source:
        return summarize_browser(source.read(16385))


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ('.', 'cn'):
        raise SystemExit('expected edition and output directory')
    report = Path(sys.argv[1]) / 'target/surefire-reports/TEST-org.familyhealthcare.service.CarePlanMysqlIntegrationTest.xml'
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    result = {'sha': os.environ.get('GITHUB_SHA'), 'run': os.environ.get('GITHUB_RUN_ID'), 'reportPresent': report.is_file()}
    if report.is_file():
        if report.is_symlink() or report.stat().st_size > 64 * 1024 * 1024:
            raise SystemExit('unsafe report file')
        result.update(summarize(report.read_bytes()))
    browser = Path(sys.argv[1]) / 'target/care-plan-browser-diagnostics.json'
    try:
        result['browser'] = read_browser_diagnostics(browser)
        result['browserDiagnosticStatus'] = 'present'
    except FileNotFoundError:
        # 缺失不能证明浏览器已运行、通过或失败；外层 shell 可能在调用前
        # 终止，也可能在自身清理阶段失败。
        result['browserDiagnosticStatus'] = 'missing'
    except (ValueError, OSError):
        result['browserDiagnosticStatus'] = 'rejected'
    (output / 'native-diagnostics.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
