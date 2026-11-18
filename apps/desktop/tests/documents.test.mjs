import test from 'node:test';
import assert from 'node:assert/strict';
import { browsableDocuments, documentPreview, documentVersions, replacementFile, proposalScope, previewPdf } from '../src/documents.ts';
import { LoginSession, AuthenticationError } from '../src/session.ts';
import { knowledgeRequest } from '../src/knowledge.ts';

const file = { documentId: 'a'.repeat(64), version: 'b'.repeat(64), path: 'common/보고서.xlsx', project: 'common', title: '보고서', type: 'xlsx', bytes: 123, modified: 1794902400000 };

test('browser validates source identity and file metadata without interpreting document text as HTML', () => {
  assert.deepEqual(browsableDocuments([file]), [file]);
  for (const invalid of [null, {}, [{ ...file, version: '../secret' }], [{ ...file, modified: 'today' }], [{ ...file, bytes: -1 }]]) {
    assert.throws(() => browsableDocuments(invalid));
  }
  const sheets = { kind: 'sheets', sheets: [{ name: '시트 1', rows: [['<script>do-not-run</script>', '=SUM(A1)']] }], text: null, message: null };
  assert.deepEqual(documentPreview(sheets), sheets);
  assert.deepEqual(documentPreview({ kind: 'unavailable', message: '변환 도구가 없어요.' }), { kind: 'unavailable', message: '변환 도구가 없어요.' });
  for (const invalid of [{ kind: 'html', text: '<script>' }, { kind: 'text', text: 1 }, { kind: 'sheets', sheets: [{ name: 'Sheet', rows: [[null]] }] }]) {
    assert.throws(() => documentPreview(invalid));
  }
  assert.deepEqual(documentVersions([{ version: 'c'.repeat(64), filename: '보고서.xlsx', createdAt: '2026-11-17T00:00:00Z' }]).length, 1);
  assert.throws(() => documentVersions([{ version: '../secret', filename: 'a', createdAt: '' }]));
});

test('replacement requires a selected document, nonempty bounded file and original extension', () => {
  assert.match(replacementFile({ name: '보고서.xlsx', size: 1 }, null), /선택/);
  for (const size of [0, 8 * 1024 * 1024 + 1]) assert.match(replacementFile({ name: '보고서.xlsx', size }, file), /8MB/);
  assert.match(replacementFile({ name: '보고서.exe', size: 100 }, file), /확장자/);
  assert.equal(replacementFile({ name: '수정한 보고서.XLSX', size: 8 * 1024 * 1024 }, file), null);
});

test('replacement multipart retains consent and original version and does not set JSON content type', async t => {
  const session = new LoginSession(); const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/api/session')) return Response.json({ username: 'u', projects: ['common'] });
    calls.push(options); return Response.json({ kind: 'REPLACEMENT' });
  });
  await session.login('u', 'synthetic');
  const body = new FormData(); const metadata = { project: file.project, sourceDocumentId: file.documentId, sourceVersion: file.version, title: file.title, consent: true };
  body.append('metadata', JSON.stringify(metadata)); body.append('file', new Blob(['changed']), '보고서.xlsx');
  await knowledgeRequest(session, '/api/knowledge/replacements', new AbortController().signal, body);
  assert.equal(calls[0].body, body); assert.equal(calls[0].headers['Content-Type'], undefined);
  assert.deepEqual(JSON.parse(body.get('metadata')), metadata);
  assert.equal(Object.hasOwn(JSON.parse(body.get('metadata')), 'content'), false);
});

test('PDF preview uses authenticated no-store fetch and rejects unavailable, oversized and changed-account results', async t => {
  const session = new LoginSession(); let response = () => new Response('%PDF-1.4', { headers: { 'Content-Type': 'application/pdf' } }); const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/api/session')) return Response.json({ username: 'u', projects: ['common'] });
    calls.push({ url, options }); return response();
  });
  await session.login('u', 'synthetic'); const signal = new AbortController().signal;
  assert.equal((await previewPdf(session, file, signal)).type, 'application/pdf');
  assert.match(calls[0].url, new RegExp(`/api/knowledge/documents/${file.documentId}/preview/pdf\\?version=${file.version}$`));
  assert.equal(calls[0].options.credentials, 'omit'); assert.equal(calls[0].options.cache, 'no-store'); assert.equal(calls[0].options.redirect, 'error');
  assert.match(calls[0].options.headers.Authorization, /^Basic /);
  response = () => new Response('<html>untrusted</html>', { headers: { 'Content-Type': 'application/pdf' } });
  await assert.rejects(previewPdf(session, file, signal), /PDF 미리보기를 읽지/);
  response = () => new Response('%PDF-1.4', { headers: { 'Content-Type': 'application/pdf-malformed' } });
  await assert.rejects(previewPdf(session, file, signal), /원본을 다운로드/);
  response = () => new Response('private server details', { status: 404 });
  await assert.rejects(previewPdf(session, file, signal), /원본을 다운로드/);
  response = () => new Response('%PDF', { headers: { 'Content-Type': 'application/pdf', 'Content-Length': String(21 * 1024 * 1024) } });
  await assert.rejects(previewPdf(session, file, signal), /너무 커/);
  response = () => { session.logout(); return new Response('%PDF', { headers: { 'Content-Type': 'application/pdf' } }); };
  await assert.rejects(previewPdf(session, file, signal), AuthenticationError);
});


test('NEW remains source-free and replacement requires a selected source; removed edit modes are rejected', () => {
  const other = { ...file, documentId: 'c'.repeat(64), version: 'd'.repeat(64), path: 'common/운영/보고서.xlsx' };
  assert.deepEqual(proposalScope('new', 'common/운영', file), { project: 'common', folderPath: 'common/운영' });
  assert.equal(Object.hasOwn(proposalScope('new', 'common/운영', file), 'sourceDocumentId'), false);
  assert.deepEqual(proposalScope('replace', 'common/이전폴더', other), {
    project: 'common', folderPath: 'common/운영', sourceDocumentId: other.documentId, sourceVersion: other.version,
  });
  assert.throws(() => proposalScope('new', '', file), /폴더/);
  assert.throws(() => proposalScope('replace', 'common', null), /대상 파일/);
  for (const mode of ['update', 'AMENDMENT', '']) assert.throws(() => proposalScope(mode, 'common', file), /새 문서 작성 또는 수정 파일로 교체/);
});

test('approved app documents use the same replacement scope as other documents', () => {
  const managed = { ...file, replaceable: false };
  assert.deepEqual(proposalScope('replace', 'common', managed), proposalScope('replace', 'common', file));
  assert.deepEqual(proposalScope('new', 'common', managed), { project: 'common', folderPath: 'common' });
  assert.equal(browsableDocuments([managed])[0].replaceable, false);
});


test('manager attachments retain physical selection identity and reject nested or cyclic metadata', () => {
  const child = { ...file, documentId: 'c'.repeat(64), path: 'common/attachments/owner/1--보고서.xlsx', title: '원래 보고서.xlsx', attachments: [], replaceable: true };
  const parent = { ...file, path: 'common/설명.md', type: 'md', attachments: [child], password: 'omit' };
  const parsed = browsableDocuments([parent])[0];
  assert.equal(parsed.attachments[0].documentId, child.documentId);
  assert.equal(parsed.attachments[0].title, '원래 보고서.xlsx');
  assert.equal(Object.hasOwn(parsed, 'password'), false);
  assert.deepEqual(proposalScope('replace', 'common', parsed.attachments[0]), {
    project: 'common', folderPath: 'common/attachments/owner', sourceDocumentId: child.documentId, sourceVersion: child.version,
  });
  assert.equal(replacementFile({ name: '수정.xlsx', size: 20 }, parsed.attachments[0]), null);
  assert.match(replacementFile({ name: '수정.png', size: 20 }, { ...file, path: 'common/image.png', replaceable: false }), /다운로드와 미리보기/);
  assert.throws(() => browsableDocuments([{ ...parent, attachments: [parent] }]));
  assert.throws(() => browsableDocuments([{ ...parent, attachments: [child, child, child, child] }]));
});
