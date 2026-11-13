"""Local API smoke check. Set PET_API_USER/PET_API_PASSWORD; never prints credentials."""
import base64
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:9001'
username = os.environ.get('PET_API_USER', 'developer')
password = os.environ.get('PET_API_PASSWORD', '')
if not password:
    raise SystemExit('PET_API_PASSWORD must be set to the local API password (not the Groq key).')
auth = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()


def call(path, body=None, authenticated=True):
    headers = {'Accept': 'text/event-stream' if body else 'application/json'}
    if authenticated:
        headers['Authorization'] = auth
    data = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(body).encode()
    return urlopen(Request(BASE + path, data=data, headers=headers), timeout=100)


try:
    call('/api/search?q=tb_comp_mng_info', authenticated=False)
    raise AssertionError('Unauthenticated search was accepted')
except HTTPError as error:
    assert error.code == 401
print('PASS unauthenticated search rejected')

for query in ['tb_comp_mng_info', '정산']:
    with call('/api/search?' + urlencode({'q': query})) as response:
        hits = json.load(response)
    assert hits, f'No authorized search results for {query}'
    assert all(hit.get('path') and hit.get('documentId') and hit.get('version') for hit in hits)
    assert all(not hit['path'].startswith(('/', '../')) for hit in hits)
    print('PASS search', query, 'hits', len(hits), 'first-source', hits[0]['path'])

with call('/api/search?' + urlencode({'q': 'zxqv_never_registered_93af'})) as response:
    assert json.load(response) == []
print('PASS unknown identifier search empty')

for scenario, question in [('greeting', '안녕'),
                           ('grounded', 'tb_comp_mng_info 테이블의 역할을 문서에 근거해서 설명해 주세요.')]:
    with call('/api/chat/stream', {'question': question}) as response:
        request_id = response.headers.get('X-Request-Id')
        event = 'message'
        data = []
        events = []
        for raw in response:
            line = raw.decode().rstrip('\r\n')
            if not line:
                if data:
                    events.append((event, json.loads('\n'.join(data))))
                event, data = 'message', []
            elif line.startswith('event:'):
                event = line[6:].strip()
            elif line.startswith('data:'):
                data.append(line[5:].lstrip(' '))
    assert events and events[-1][0] == 'done', 'Stream did not complete normally'
    assert not any(name == 'error' for name, _ in events), 'Stream emitted an error'
    answer = ''.join(value['text'] for name, value in events if name == 'delta')
    assert answer
    assert request_id and events[-1][1]['requestId'] == request_id
    if scenario == 'grounded':
        assert any(name == 'sources' and value['items'] for name, value in events)
    print('PASS chat', scenario, 'events', len(events), 'answer-characters', len(answer), 'provider', events[-1][1].get('provider'))
