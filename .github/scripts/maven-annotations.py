"""Turns Maven compile errors and failed tests into GitHub annotations and a step summary, so a failed build can be read
from the run page (and through the public API) without downloading the log. Usage: maven-annotations.py <module> <build.log>"""
import glob, os, re, sys
import xml.etree.ElementTree as ET

module, log = sys.argv[1], sys.argv[2]
root = os.environ.get('GITHUB_WORKSPACE', os.getcwd())
notes = []          # (file, line, title, message)

# compile errors: [ERROR] /path/File.java:[12,5] message
seen = set()
for line in open(log, errors='replace') if os.path.exists(log) else []:
    m = re.match(r'\[ERROR\] (/\S+\.java):\[(\d+),\d+\] (.*)', line.strip())
    if m and (m.group(1), m.group(2)) not in seen:
        seen.add((m.group(1), m.group(2)))
        notes.append((os.path.relpath(m.group(1), root), m.group(2), 'Compile error', m.group(3)))

# failed tests from the surefire / failsafe reports
failed = []
for f in glob.glob(f'{module}/target/surefire-reports/TEST-*.xml') + glob.glob(f'{module}/target/failsafe-reports/TEST-*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        for kind in ('failure', 'error'):
            e = tc.find(kind)
            if e is None:
                continue
            name = f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}"
            text = (e.get('message') or '') + '\n' + (e.text or '')
            where = re.search(r'at (tz\.co\.msingi\.[\w.$]+)\((\w+\.java):(\d+)\)', text)
            loc = None
            if where:
                hits = glob.glob(f"{module}/src/**/{where.group(2)}", recursive=True)
                loc = (hits[0], where.group(3)) if hits else None
            failed.append((name, kind, text.strip(), loc))

if failed:
    notes.insert(0, (None, None, f'{len(failed)} test(s) failed', ', '.join(n for n, *_ in failed)))
    for name, kind, text, loc in failed[:8]:
        lines = [l for l in text.splitlines() if l.strip()]
        keep = [l for l in lines if not l.strip().startswith('at ') or 'tz.co.msingi' in l][:25]
        notes.append((loc[0] if loc else None, loc[1] if loc else None, f'{name} ({kind})', '\n'.join(keep)))

def esc(s):
    return s.replace('%', '%25').replace('\r', '').replace('\n', '%0A')


def prop(s):
    return esc(s).replace(':', '%3A').replace(',', '%2C')

for file, line, title, msg in notes[:10]:
    where = (f'file={prop(file)},line={line},' if file and line else f'file={prop(file)},' if file else '')
    print(f"::error {where}title={prop(title)}::{esc(msg[:4000])}")

summary = os.environ.get('GITHUB_STEP_SUMMARY')
if summary:
    with open(summary, 'a') as s:
        s.write(f'## {module}: build problems\n\n' + ('\n'.join(f'- **{t}** {(f or "")}{":" + l if l else ""}\n\n  ```\n  {m[:1500]}\n  ```'
                                                         for f, l, t, m in notes) or 'None.') + '\n')
