#!/usr/bin/env python3
"""Bounded failure-only diagnostics from the disposable CI MySQL test."""
import json
import os
from pathlib import Path
import re
import sys
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
    (output / 'native-diagnostics.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
