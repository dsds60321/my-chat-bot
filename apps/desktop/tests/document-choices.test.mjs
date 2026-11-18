import assert from 'node:assert/strict';
import test from 'node:test';
import { setupDocumentChoices } from '../src/document-choices.ts';

class Node extends EventTarget {
  children = []; textContent = ''; isConnected = true; opened = false;
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute() {}
  get open() { return this.opened; }
  set open(value) { this.opened = value; this.dispatchEvent(new Event('toggle')); }
}
const source = number => ({ documentId: String(number).repeat(64), version: 'a'.repeat(64), title: `문서 ${number}`, path: `guide-${number}.md` });
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

test('each document can close and reopen with its answer and sources retained independently', async () => {
  const previous = { document: globalThis.document, button: globalThis.HTMLButtonElement };
  globalThis.document = { createElement() { return new Node(); } };
  globalThis.HTMLButtonElement = Node;
  try {
    const container = new Node(); const calls = []; const readers = [];
    const choices = setupDocumentChoices(container, {
      async load(question, file, controller, emit) {
        calls.push(file.documentId);
        emit({ event: 'sources', data: { items: [source(file.documentId[0])] } });
        emit({ event: 'delta', data: { text: `답변 ${file.documentId[0]}` } });
      },
      download() {}, preview() {},
      async read(snapshot) { readers.push(snapshot); },
    });
    choices.show('원래 질문', [source(1), source(2)]);
    assert.equal(choices.toggle(0), true); await flush();
    assert.equal(container.children[0].children[2].textContent, '답변 1');
    choices.toggle(0); choices.toggle(1); await flush();
    assert.equal(container.children[0].open, false);
    assert.equal(container.children[1].children[2].textContent, '답변 2');
    choices.toggle(0); await flush();
    assert.equal(container.children[0].children[2].textContent, '답변 1');
    assert.deepEqual(calls, [source(1).documentId, source(2).documentId]);
    container.children[0].children[4].children[0].dispatchEvent(new Event('click'));
    await flush();
    assert.equal(readers[0].answer, '답변 1');
    assert.equal(readers[0].sources[0].documentId, source(1).documentId);
    assert.equal(choices.toggle(2), false);
    choices.show('새 질문', []);
    assert.equal(container.children.length, 0);
  } finally {
    globalThis.document = previous.document; globalThis.HTMLButtonElement = previous.button;
  }
});


test('download rows survive an AI failure after source metadata arrives', async () => {
  const previous = { document: globalThis.document, button: globalThis.HTMLButtonElement };
  globalThis.document = { createElement() { return new Node(); } };
  globalThis.HTMLButtonElement = Node;
  try {
    const attachment = { documentId: 'c'.repeat(64), version: 'd'.repeat(64), filename: '입력 양식.xlsx', description: '', image: false };
    const current = { ...source(1), attachments: [attachment] };
    const container = new Node(); const downloaded = [];
    const choices = setupDocumentChoices(container, {
      async load(question, file, controller, emit) {
        emit({ event: 'sources', data: { items: [current] } });
        throw new Error('AI 요청 한도를 초과했습니다.');
      },
      download(file) { downloaded.push(file); }, preview() {}, async read() {},
    });
    choices.show('양식 알려줘', [source(1)]);
    choices.toggle(0); await flush();
    const section = container.children[0];
    assert.equal(section.children[5].textContent, 'AI 요청 한도를 초과했습니다.');
    const downloads = section.children[1];
    assert.equal(downloads.children[0].children[0].children[0].textContent, '입력 양식.xlsx');
    downloads.children[0].children[0].children[2].dispatchEvent(new Event('click'));
    assert.equal(downloaded[0].documentId, attachment.documentId);
    assert.equal(downloaded[0].version, attachment.version);
  } finally {
    globalThis.document = previous.document; globalThis.HTMLButtonElement = previous.button;
  }
});
