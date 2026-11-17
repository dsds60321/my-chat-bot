import test from 'node:test';
import assert from 'node:assert/strict';
import { formatSource, safeSources, answerSnapshot, snapshotText } from '../src/sources.ts';

test('source uses actual path basename and preserves relative path, section and row', () => {
  const source = { title:'Document', path:'notes/database/schema.md', section:'결제', row:12 };
  assert.deepEqual(formatSource(source, 0), { label:'[1] schema.md', detail:'notes/database/schema.md · 결제 · 행 12' });
  assert.equal(formatSource({title:'ignored',path:'notes\\book.xlsx'},1).label,'[2] book.xlsx');
  assert.equal(snapshotText({answer:'본문', sources:[source]}),'본문\n\n검색 참고 자료\n[1] schema.md\nnotes/database/schema.md · 결제 · 행 12');
});

test('snapshot whitelists bounded answer/source metadata and never copies credentials or invents sources', () => {
  const snapshot = answerSnapshot({ answer:'<script>plain text</script>', username:'private-user', password:'test-only-private', Authorization:'secret', sources:[{title:'표',path:'db/table.md', password:'private',row:-1}, null,{title:'bad'}] });
  assert.deepEqual(snapshot, { answer:'<script>plain text</script>', sources:[{title:'표',path:'db/table.md'}] });
  assert.equal(JSON.stringify(snapshot).includes('private'),false);
  assert.equal(safeSources(Array.from({length:20},()=>({title:'t',path:'p'}))).length,10);
  assert.equal(safeSources([{title:'t',path:'p'.repeat(2001)}]).length,0);
  assert.deepEqual(answerSnapshot({answer:'일반 개발 답변'}),{answer:'일반 개발 답변',sources:[]});
  assert.throws(()=>answerSnapshot({answer:''}));
  assert.throws(()=>answerSnapshot({answer:'x'.repeat(500001)}));
});

test('partial reader snapshots retain only validated download IDs and versions', () => {
  const file = { title:'원본', path:'notes/원본.xlsx', documentId:'a'.repeat(64), version:'b'.repeat(64) };
  const snapshot = answerSnapshot({ answer:'부분', partial:true, sources:[file, {...file, version:'../secret'}] });
  assert.deepEqual(snapshot, { answer:'부분', partial:true, sources:[file, { title:file.title, path:file.path }] });
});

test('answer snapshots keep valid attachments with only their public metadata', () => {
  const file = { documentId: 'a'.repeat(64), version: 'b'.repeat(64), filename: '취소.png', description: '입금취소 화면', image: true };
  const snapshot = answerSnapshot({ answer: '본문', sources: [{ title: '가이드', path: 'guide.md',
    attachments: [{ ...file, password: 'omit' }, { ...file, documentId: '../private' }] }] });
  assert.deepEqual(snapshot.sources[0].attachments, [file]);
});

test('answer attachments load images automatically once per source and keep downloads explicit', async t => {
  class Node extends EventTarget {
    children = []; textContent = ''; isConnected = true; attributes = new Map();
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    querySelector(tag) { return this.children.find(node => node.tag === tag) ?? null; }
    setAttribute(name, value) { this.attributes.set(name, value); }
    hasAttribute(name) { return this.attributes.has(name); }
  }
  const priorDocument = globalThis.document;
  const priorButton = globalThis.HTMLButtonElement;
  globalThis.HTMLButtonElement = Node;
  globalThis.document = {
    createElement(tag) { const node = new Node(); node.tag = tag; return node; },
    getElementById() { return null; },
  };
  const { renderSources } = await import('../src/sources.ts');
  try {
    const file = { documentId: 'a'.repeat(64), version: 'b'.repeat(64), filename: '화면.png', description: '취소 화면', image: true };
    const source = { title: '절차', path: 'guide.md', attachments: [file] };
    const loaded = []; const downloaded = [];
    renderSources(new Node(), [source, source], value => downloaded.push(value), (value, image) => loaded.push([value, image.tag]));
    await Promise.resolve();
    assert.deepEqual(loaded, [[file, 'img']]);
    assert.deepEqual(downloaded, []);
    loaded.length = 0;
    renderSources(new Node(), [source], () => {}, value => loaded.push(value), false);
    await Promise.resolve();
    assert.deepEqual(loaded, []);
  } finally {
    globalThis.document = priorDocument;
    globalThis.HTMLButtonElement = priorButton;
  }
});

test('inline image rendering accepts image data only and ignores detached answer windows', async () => {
  const { displaySourceImage } = await import('../src/sources.ts');
  const image = { isConnected: true, src: '', async decode() {}, removeAttribute() { this.src = ''; } };
  await assert.rejects(displaySourceImage(image, 'https://external.invalid/pixel'));
  await assert.rejects(displaySourceImage(image, 'data:text/html;base64,PHNjcmlwdD4='));
  await displaySourceImage(image, 'data:image/png;base64,YWJj');
  assert.equal(image.src, 'data:image/png;base64,YWJj');
  image.isConnected = false; image.src = '';
  await displaySourceImage(image, 'data:image/png;base64,YWJj');
  assert.equal(image.src, '');
});
