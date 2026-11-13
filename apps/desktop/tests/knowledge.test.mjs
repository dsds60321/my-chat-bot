import test from 'node:test';
import assert from 'node:assert/strict';
import { documentTargets, knowledgeRequest, proposalIntent } from '../src/knowledge.ts';
import { LoginSession, AuthenticationError } from '../src/session.ts';

test('explicit document edits route to proposals and ordinary knowledge questions remain chat', () => {
  const instruction = '차액 정산 재매입 절차에서 예약은 영업일 기준이라고 추가해 줘';
  assert.deepEqual(proposalIntent(instruction), { kind: 'AMENDMENT', instruction, query: '차액 정산 재매입 절차' });
  assert.deepEqual(proposalIntent('/수정 E2U_WBS.xlsx 영업일 기준 추가'), { kind: 'AMENDMENT', instruction: 'E2U_WBS.xlsx 영업일 기준 추가', query: 'E2U_WBS.xlsx' });
  for (const input of ['문서를 수정해 주세요.', '문서 수정 부탁해요', '문서 수정해줘!']) {
    assert.deepEqual(proposalIntent(input), { kind: 'AMENDMENT', instruction: input, query: '' });
  }
  assert.equal(proposalIntent('/새문서 재매입 기준 정리')?.kind, 'NEW');
  assert.equal(proposalIntent('재매입 기준에 관한 새 문서를 작성해 주세요.')?.kind, 'NEW');
  for (const question of ['차액 정산 재매입 절차를 설명해 줘', 'E2U_WBS.xlsx에서 예약 기준이 뭐야?', '문서 수정 방법을 알려줘', 'JS 코드를 수정해 주세요.', '/수정사항은 무엇인가요?']) {
    assert.equal(proposalIntent(question), null, question);
  }
});

test('candidate selection deduplicates search excerpts and omits approved virtual copies', () => {
  const original = { documentId: 'a'.repeat(64), version: 'b'.repeat(64), project: 'paye2u+syslink', title: 'WBS', path: '팀/E2U_WBS.xlsx' };
  assert.deepEqual(documentTargets([
    { ...original, chunkId: '1' }, { ...original, chunkId: '2' },
    { ...original, documentId: 'c'.repeat(64), path: '.approved/example.md' },
    { ...original, documentId: 'invalid' }, null,
  ]), [original]);
});

test('proposal client separates previews from consent submissions and never surfaces error bodies', async t => {
  const session = new LoginSession();
  const requests = [];
  const mock = t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/api/session')) return Response.json({ username: 'u', projects: ['paye2u'], admin: true });
    requests.push({ url, ...options });
    return Response.json({});
  });
  await session.login('u', 'synthetic-password');
  assert.equal(session.user.admin, true);
  const signal = new AbortController().signal;
  const scope = { project: 'paye2u', sourceDocumentId: 'a'.repeat(64), sourceVersion: 'b'.repeat(64) };
  await knowledgeRequest(session, '/api/knowledge/proposals/draft', signal, { ...scope, instruction: 'draft instruction' });
  await knowledgeRequest(session, '/api/knowledge/proposals', signal, { ...scope, title: 'Title', content: 'Edited text', consent: true });
  assert.deepEqual(requests.map(request => new URL(request.url).pathname), ['/api/knowledge/proposals/draft', '/api/knowledge/proposals']);
  assert.deepEqual(JSON.parse(requests[0].body), { ...scope, instruction: 'draft instruction' });
  assert.deepEqual(JSON.parse(requests[1].body), { ...scope, title: 'Title', content: 'Edited text', consent: true });
  for (const request of requests) {
    assert.equal(request.method, 'POST');
    assert.equal(request.credentials, 'omit');
    assert.equal(request.cache, 'no-store');
    assert.equal(request.redirect, 'error');
    assert.ok(request.headers.Authorization.startsWith('Basic '));
  }
  mock.mock.mockImplementation(async () => new Response('server private details', { status: 409 }));
  await assert.rejects(knowledgeRequest(session, '/api/knowledge/proposals', signal), { message: '문서가 변경되었거나 제안이 이미 처리됐어요. 다시 불러와 주세요.' });
  mock.mock.mockImplementation(async () => new Response('server private details', { status: 401 }));
  await assert.rejects(knowledgeRequest(session, '/api/knowledge/proposals', signal), AuthenticationError);
});
