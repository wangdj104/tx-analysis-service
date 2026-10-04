#!/usr/bin/env python3
"""Test-only bounded logical inspection. Standard CSV parsing preserves embedded newlines.
No clinical content or input path is printed on a failure.
"""
from collections import Counter
import csv
import datetime
from html.parser import HTMLParser
import io
import json
import re
import sys
import unicodedata

MAX_BYTES = 32 * 1024 * 1024
MAX_TEXT = 16 * 1024 * 1024


def require(condition):
    if not condition:
        raise ValueError('Report content inspection failed')


def dangerous(value):
    leading_control = False
    for char in value:
        if unicodedata.category(char) in ('Cc', 'Cf'):
            leading_control = True
            continue
        if char.isspace() or unicodedata.category(char).startswith('Z'):
            continue
        return leading_control or char in '=+-@'
    return leading_control


def check_text(text, expected):
    require(len(text.encode('utf-8')) <= MAX_TEXT)
    # PDF line wrapping changes whitespace. The comparison keeps all other Unicode
    # code points intact; it does not translate, normalize, or strip clinical text.
    normalized = re.sub(r'\s+', ' ', text)
    for value in expected.get('contains', []):
        require(isinstance(value, str) and re.sub(r'\s+', ' ', value) in normalized)
    for value in expected.get('excludes', []):
        require(isinstance(value, str) and re.sub(r'\s+', ' ', value) not in normalized)


def normalized(value):
    return re.sub(r'\s+', ' ', value).strip()


def check_document(text, options, links):
    expected = options.get('expected', {})
    flat = normalized(text)
    zh = options['language'] == 'zh-CN'
    label = lambda en, cn: cn if zh else en
    # These are output-contract labels, independent of the renderer's labels map.
    def field(name, value):
        require(re.search(re.escape(name) + r'\s+' + re.escape(str(value)).replace(r'\ ', r'\s*') + r'(?=\s|$)', flat) is not None)
    if 'patientId' in expected:
        check_text(text, {'contains': [label('Care execution report', '照护执行报告'), label('Current state and counting basis', '当前状态与统计口径'), label('Period activity', '期间活动')]})
        for name, value in [(label('Patient ID', '患者ID'), expected['patientId']),
                            (label('Scope', '范围'), label('All plans', '全部计划') if expected.get('planId') is None else label('This plan only', '仅此计划')),
                            (label('Scope plan ID', '范围计划ID'), label('Not applicable', '不适用') if expected.get('planId') is None else expected['planId']),
                            (label('Report schema version', '报告结构版本'), 1),
                            (label('From date', '开始日期'), expected['fromDate']),
                            (label('To date (inclusive)', '结束日期（含当日）'), expected['toDate']),
                            (label('Time zone', '时区'), expected['timeZone']),
                            (label('Language', '界面语言'), options['language']),
                            (label('Completeness', '完整性'), label('Complete for selected scope and authorized sections', '所选范围及已授权区域完整'))]:
            field(name, value)
        instants = []
        for name in [label('Generated at', '生成时间'), label('Current state as of', '当前状态读取时间'), label('Range start', '期间开始'), label('Range end (exclusive)', '期间结束（不含）')]:
            match = re.search(re.escape(name) + r'\s+(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z)', flat)
            require(match is not None)
            instants.append(datetime.datetime.fromisoformat(match[1].replace('Z', '+00:00')))
        require(instants[1] <= instants[0] and instants[2] < instants[3])
        if 'generatedSecond' in expected:
            require(instants[0].replace(microsecond=0).isoformat().replace('+00:00', 'Z') == expected['generatedSecond'])
        if expected['timeZone'] == 'UTC':
            require(instants[2].isoformat() == expected['fromDate'] + 'T00:00:00+00:00')
            require(instants[3].date() == datetime.date.fromisoformat(expected['toDate']) + datetime.timedelta(days=1))
            require(instants[3].time() == datetime.time(0))
    for name, value in expected.get('fields', []):
        field(name, value)
    if 'allowedReferences' in expected:
        allowed = set(expected['allowedReferences'])
        require(all(link in allowed for link in links))
        # References are printed as protected locations as well as hrefs. Source
        # numbers alone cannot be globally banned because plan/action IDs overlap.
        # PDF can wrap a long protected path between any two glyphs. Parse only
        # the fixed source-locator grammar after whitespace removal, never a URL fetch.
        compact = re.sub(r'\s+', '', text)
        visible = re.findall(r'/(?:care-journey\?tab=measurements&patientId=[1-9][0-9]*&measurementId=[1-9][0-9]*|medical-record\?tab=list&patientId=[1-9][0-9]*&recordId=[1-9][0-9]*)(?![0-9&])', compact)
        require(len(visible) == compact.count('/care-journey?') + compact.count('/medical-record?'))
        require(all(link in allowed for link in visible))
        if options['format'] == 'html':
            require(set(links) == allowed)
        else:
            require(set(visible) == allowed)
    if 'evidenceEntries' in expected:
        names = [label('Evidence type', '证据类型'), label('Evidence source ID', '证据源ID'), label('Evidence title', '证据标题'), label('Protected in-app detail', '应用内受保护详情')]
        pattern = re.escape(names[0]) + r'\s+(.+?)\s+' + re.escape(names[1]) + r'\s+([1-9][0-9]*)\s+' + re.escape(names[2]) + r'\s+(.+?)\s+' + re.escape(names[3])
        actual = Counter(re.findall(pattern, flat))
        wanted = Counter({(entry['type'], entry['id'], entry['title']): entry['count'] for entry in expected['evidenceEntries']})
        require(actual == wanted and sum(actual.values()) == flat.count(names[1]))
    if expected.get('evidenceRestricted'):
        require(not any(value in flat for value in ['Evidence type', 'Evidence source ID', 'Evidence title', '证据类型', '证据源ID', '证据标题']))


def check_records(data, schema, expected, language):
    entries = [dict(zip((column['key'] for column in schema), row)) for row in data]
    identity = 'event.id' if any(column['key'] == 'event.id' for column in schema) else 'action'
    identities = [entry[identity] for entry in entries]
    require(all(re.fullmatch(r'[1-9][0-9]*', value) for value in identities))
    require(len(set(identities)) == len(identities))
    states = {'OPEN', 'NEEDS_HELP', 'SUBMITTED', 'CONFIRMED'}
    event_types = {'PLAN_PUBLISHED', 'REVISION_PUBLISHED', 'RECEIPT_SUBMITTED', 'HELP_REQUESTED', 'FOLLOW_UP_RECORDED', 'RECEIPT_RETURNED', 'RECEIPT_CONFIRMED', 'PLAN_CANCELLED', 'PLAN_CLOSED'}
    for entry in entries:
        require(all(re.fullmatch(r'[1-9][0-9]*', entry[key]) for key in ['plan', 'revision', 'revisionNo']))
        require(entry['statusCode'] in (states if identity == 'action' else states | {''}))
        for key, value in entry.items():
            if key.endswith('.typeCode'):
                require(value in event_types or (value == '' and key != 'event.typeCode'))
    if 'records' in expected:
        key = expected['recordKey']
        wanted = expected['records']
        require(isinstance(wanted, list) and len(wanted) <= 5000 and key in [column['key'] for column in schema])
        identities = [entry[key] for entry in entries]
        require(all(re.fullmatch(r'[1-9][0-9]*', value) for value in identities))
        require(len(set(identities)) == len(identities))
        require(set(identities) == {record[key] for record in wanted} and len(wanted) == len(entries))
        for record in wanted:
            actual = next(entry for entry in entries if entry[key] == record[key])
            require(all(column in actual and actual[column] == value for column, value in record.items()))
    if expected.get('evidenceRestricted'):
        placeholder = '存在受限证据' if language == 'zh-CN' else 'Restricted evidence exists'
        for entry in entries:
            for key, value in entry.items():
                if key == 'evidence' or key.endswith('.evidence'):
                    require(not value or all(line == placeholder for line in value.split('\n')))
    if 'generatedSecond' in expected:
        require(all(datetime.datetime.fromisoformat(entry['generated'].replace('Z', '+00:00')).replace(microsecond=0).isoformat().replace('+00:00', 'Z') == expected['generatedSecond'] for entry in entries))


class SafeReportHTML(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = []
        self.styles = []
        self.in_style = False
        self.language = None
        self.links = []

    def handle_starttag(self, tag, attrs):
        require(tag not in ('script', 'iframe', 'object', 'embed', 'img', 'link', 'base', 'form', 'input', 'video', 'audio', 'svg', 'math'))
        for key, value in attrs:
            require(not key.startswith('on') and key not in ('src', 'srcset', 'action', 'formaction'))
            if key == 'href':
                self.links.append(value)
                require(tag == 'a' and value is not None and re.fullmatch(r'/(?:care-plans/[1-9][0-9]*|care-journey\?tab=measurements&patientId=[1-9][0-9]*&measurementId=[1-9][0-9]*|medical-record\?tab=list&patientId=[1-9][0-9]*&recordId=[1-9][0-9]*)', value))
            if key == 'style':
                require(value is not None and not re.search(r'url\s*\(|@import|expression\s*\(', value, re.I))
            if tag == 'html' and key == 'lang':
                self.language = value
        if tag == 'style':
            self.in_style = True

    def handle_endtag(self, tag):
        if tag == 'style':
            self.in_style = False

    def handle_data(self, data):
        (self.styles if self.in_style else self.text).append(data)


def inspect_csv(path, options):
    with open(path, 'rb') as stream:
        raw = stream.read(MAX_BYTES + 1)
    require(0 < len(raw) <= MAX_BYTES and raw.startswith(b'\xef\xbb\xbf'))
    csv.field_size_limit(8 * 1024 * 1024)
    # newline='' is essential: a quoted CRLF must stay inside the field.
    with open(path, 'r', encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.reader(stream, strict=True))
    schema = options['schema']
    require(rows and rows[0] == [column['header'] for column in schema])
    data = rows[1:]
    limit = 1000 if options['format'] == 'actions_csv' else 5000
    require(len(data) <= limit and all(len(row) == len(schema) for row in data))
    expected = options.get('expected', {})
    if 'rows' in expected:
        require(len(data) == expected['rows'])
    if 'minRows' in expected:
        require(len(data) >= expected['minRows'])
    numeric = {'schema', 'patient', 'scopePlan', 'plan', 'revision', 'revisionNo', 'action', 'assignee'}
    numeric.update(c['key'] for c in schema if c['key'].endswith(('.id', '.actor')))
    for row in data:
        entry = dict(zip((c['key'] for c in schema), row))
        for key, value in entry.items():
            if key not in numeric:
                require(not dangerous(value))
            else:
                require(not value or re.fullmatch(r'[0-9]+', value) is not None)
        require(entry['schema'] == '1' and entry['language'] == options['language'] and entry['source'] == 'CARE_PLAN' and entry['completeCode'] == 'COMPLETE')
        require(entry['scopeCode'] in ('ALL_PLANS', 'SINGLE_PLAN'))
        if 'patientId' in expected:
            require(entry['patient'] == str(expected['patientId']))
        if 'scopePlanId' in expected:
            require(entry['scopePlan'] == str(expected['scopePlanId']))
        for expected_key, column in [('fromDate', 'from'), ('toDate', 'to'), ('timeZone', 'zone')]:
            if expected_key in expected:
                require(entry[column] == expected[expected_key])
        if 'planId' in expected:
            require(entry['scopeCode'] == ('ALL_PLANS' if expected['planId'] is None else 'SINGLE_PLAN'))
            require(entry['scopePlan'] == ('' if expected['planId'] is None else str(expected['planId'])))
        for key in ('generated', 'asOf', 'start', 'end'):
            require(re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', entry[key]) is not None)
        require(datetime.date.fromisoformat(entry['from']) <= datetime.date.fromisoformat(entry['to']))
        require(datetime.datetime.fromisoformat(entry['asOf'].replace('Z', '+00:00')) <= datetime.datetime.fromisoformat(entry['generated'].replace('Z', '+00:00')))
        if expected.get('timeZone') == 'UTC':
            require(entry['start'] == expected['fromDate'] + 'T00:00:00Z')
            require(entry['end'] == (datetime.date.fromisoformat(expected['toDate']) + datetime.timedelta(days=1)).isoformat() + 'T00:00:00Z')
    check_records(data, schema, expected, options['language'])
    cells = [cell for row in data for cell in row]
    for value in expected.get('cells', []):
        require(value in cells)
    text = '\n'.join(cells)
    check_text(text, expected)
    return {'csvRowCount': len(data), 'columnCount': len(schema), 'header': rows[0], 'rows': data, 'text': text}


def main():
    options = json.loads(sys.stdin.buffer.read(65537))
    require(isinstance(options, dict))
    path = sys.argv[1]
    if options['format'] in ('actions_csv', 'events_csv'):
        result = inspect_csv(path, options)
    else:
        with open(path, 'rb') as stream:
            raw = stream.read(MAX_BYTES + 1)
        require(0 < len(raw) <= MAX_BYTES)
        text = raw.decode('utf-8', errors='strict')
        links = []
        if options['format'] == 'html':
            parser = SafeReportHTML()
            parser.feed(text)
            parser.close()
            require(parser.language == options['language'])
            require(not re.search(r'url\s*\(|@import|expression\s*\(', ''.join(parser.styles), re.I))
            text = '\n'.join(parser.text)
            links = parser.links
        else:
            require(options['format'] == 'pdf_text')
        check_document(text, options, links)
        check_text(text, options.get('expected', {}))
        result = {'text': text}
    encoded = json.dumps(result, ensure_ascii=False).encode('utf-8')
    require(len(encoded) <= 24 * 1024 * 1024)
    sys.stdout.buffer.write(encoded)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Never serialize paths, clinical fields, subprocess errors, or failed values.
        sys.stderr.write('Report content inspection failed\n')
        sys.exit(1)
