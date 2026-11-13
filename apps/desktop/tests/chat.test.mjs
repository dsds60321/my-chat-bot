import test from 'node:test';
import assert from 'node:assert/strict';
import { SseParser, streamChat } from '../src/chat.ts';

test('SSE lines support fragmented CRLF, comments and multiline JSON', () => {
  const p = new SseParser();
  const input = ': comment\r\nevent: delta\r\ndata: {"text":\r\ndata: "한글"}\r\n\r\nevent: done\ndata: {}\n\n';
  assert.deepEqual([...input].flatMap(c => p.push(c)), [{event:'delta',data:{text:'한글'}},{event:'done',data:{}}]);
});
test('SSE rejects malformed data and oversized events', () => {
  assert.throws(() => new SseParser().push('data: synthetic-private-value\n\n'), e => e.message === '잘못된 응답 이벤트입니다.');
  assert.throws(() => new SseParser().push('data: '+ 'x'.repeat(70000)));
});
test('chat reads split UTF-8 and stops at done without cookie credentials', async t => {
  let cancelled = false;
  const bytes = new TextEncoder().encode('event: delta\ndata: {"text":"안녕"}\n\nevent: done\ndata: {"provider":"mock"}\n\n');
  let i = 0;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url,'http://127.0.0.1:9001/api/chat/stream');
    assert.equal(options.credentials,'omit');
    assert.equal(options.redirect,'error');
    assert.deepEqual(JSON.parse(options.body),{question:'test'});
    return new Response(new ReadableStream({pull(c){if(i<bytes.length)c.enqueue(bytes.slice(i,i+=1));},cancel(){cancelled=true;}}),{headers:{'content-type':'text/event-stream'}});
  });
  const events=[];
  await streamChat('test','developer','test-only',new AbortController().signal,e=>events.push(e));
  assert.equal(events[0].data.text,'안녕');
  assert.equal(cancelled,true);
});
test('incomplete SSE, provider errors, HTTP errors and missing auth fail safely', async t => {
  for(const body of ['event: delta\ndata: {"text":"partial"}\n\n','event: error\ndata: {"message":"do-not-display-upstream-body"}\n\n']){
    const mock=t.mock.method(globalThis,'fetch',async()=>new Response(body,{headers:{'content-type':'text/event-stream'}}));
    await assert.rejects(streamChat('q','u','p',new AbortController().signal,()=>{}), e=>!e.message.includes('do-not-display-upstream-body'));
    mock.mock.restore();
  }
  const mock=t.mock.method(globalThis,'fetch',async()=>new Response('private server body',{status:401}));
  await assert.rejects(streamChat('q','u','p',new AbortController().signal,()=>{}),e => e.constructor.name === 'AuthenticationError');
  mock.mock.restore();
  await assert.rejects(streamChat('q','u','',new AbortController().signal,()=>{}),/비밀번호/);
});
test('aborting chat cancels the fetch signal', async t => {
  const controller=new AbortController();
  t.mock.method(globalThis,'fetch',async(_url,{signal})=>{controller.abort();signal.throwIfAborted();});
  await assert.rejects(streamChat('q','u','p',controller.signal,()=>{}),{name:'AbortError'});
});

test('length limit preserves the last delta and reports a partial answer', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(
    'event: delta\ndata: {"text":"마지막 부분"}\n\nevent: error\ndata: {"code":"ANSWER_LENGTH_LIMIT"}\n\n',
    { headers:{'content-type':'text/event-stream'} }));
  const events = [];
  await assert.rejects(streamChat('q','u','p',new AbortController().signal,e=>events.push(e)), /길이 한도/);
  assert.deepEqual(events, [{event:'delta',data:{text:'마지막 부분'}}]);
});

test('many small SSE events in one large HTTP chunk remain valid', () => {
  const events = new SseParser().push('event: delta\ndata: {"text":"a"}\n\n'.repeat(2200)+'event: done\ndata: {}\n\n');
  assert.equal(events.length,2201);
  assert.equal(events.at(-1).event,'done');
});

test('AI and server limits show the retry time only when provided', async t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-11-12T04:00:00Z'));
  const expected = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'medium',
  }).format(Date.now() + 90000);
  const scenarios = [
    [new Response('event: error\ndata: {"code":"RATE_LIMITED","retryAfterSeconds":90}\n\n', {
      headers: { 'content-type': 'text/event-stream' },
    }), `AI 요청 한도를 초과했습니다. 한국 시간 ${expected} 이후에 다시 시도해 주세요.`],
    [new Response('event: error\ndata: {"code":"RATE_LIMITED","retryAfterSeconds":null}\n\n', {
      headers: { 'content-type': 'text/event-stream' },
    }), 'AI 요청 한도를 초과했습니다. 서버에서 재시도 가능 시각을 받지 못했습니다.'],
    [new Response('{"code":"LOCAL_RATE_LIMITED","retryAfterSeconds":90}', { status: 429 }),
      `서버 요청 한도를 초과했습니다. 한국 시간 ${expected} 이후에 다시 시도해 주세요.`],
  ];
  for (const [response, message] of scenarios) {
    const fetch = t.mock.method(globalThis, 'fetch', async () => response);
    await assert.rejects(streamChat('안녕', 'u', 'p', new AbortController().signal, () => {}), { message });
    fetch.mock.restore();
  }
});
