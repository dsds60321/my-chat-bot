import test from 'node:test';
import assert from 'node:assert/strict';
import { folderCatalog, folderRows, folderCreation, validFolderPath, documentTreeRows } from '../src/folders.ts';

const request = (path, state = 'PENDING_APPROVAL') => ({ id: path, path, author: 'ghkang', state, createdAt: '2026-11-17', updatedAt: '2026-11-17' });
const catalog = () => folderCatalog({ folders: ['user/ghkang/개발가이드', 'user', 'user/ghkang', 'paye2u', 'user/ghkang2', 'user'],
  requests: [request('user/ghkang/정산 가이드'), request('new-project'), request('user/제외됨/비공개')], canCreateDirectly: false, canCreateProject: false });

test('folder browser shows actual nested paths, preserves pending state, and never invents missing parents', () => {
  const state = catalog();
  assert.equal(state.folders.length, 5);
  assert.deepEqual(folderRows(state, new Set()).map(row => row.path).sort(), ['new-project', 'paye2u', 'user']);
  const rows = folderRows(state, new Set(['user', 'user/ghkang']));
  assert.equal(rows.find(row => row.path === 'user/ghkang/개발가이드').depth, 2);
  assert.equal(rows.find(row => row.path === 'user/ghkang/정산 가이드').pending, true);
  assert.equal(rows.find(row => row.path === 'user/ghkang2').pending, false);
  assert.equal(rows.some(row => row.path.includes('제외됨')), false);
});

test('folder search reveals matching ancestors despite collapsed state without matching unrelated siblings', () => {
  const rows = folderRows(catalog(), new Set(), '개발가이드');
  assert.deepEqual(rows.map(row => row.path), ['user', 'user/ghkang', 'user/ghkang/개발가이드']);
  assert.deepEqual(folderRows(catalog(), new Set(), '존재하지 않음'), []);
});

test('folder creation keeps parent and child separate, accepts Korean names, and rejects traversal or invalid root IDs', () => {
  assert.deepEqual(folderCreation('user/ghkang', ' 정산 가이드 '), { parentPath: 'user/ghkang', name: '정산 가이드', path: 'user/ghkang/정산 가이드' });
  assert.equal(folderCreation('', 'new-project').path, 'new-project');
  for (const name of ['', '.', '..', '../other', '/root', 'sub/folder', 'sub\\folder', 'name.', 'a'.repeat(101)]) {
    assert.throws(() => folderCreation('user/ghkang', name), undefined, name);
  }
  for (const name of ['한글프로젝트', 'Upper', 'a'.repeat(65)]) assert.throws(() => folderCreation('', name));
  assert.equal(folderCreation('user', 'a'.repeat(100)).name.length, 100);
  assert.equal(validFolderPath('user/' + 'a'.repeat(200)), true);
  assert.equal(validFolderPath(Array(17).fill('a').join('/')), false);
});

test('catalog rejects malformed permission and request data instead of guessing admin access', () => {
  for (const value of [null, {}, { ...catalog(), folders: ['user/../private'] }, { ...catalog(), canCreateDirectly: 'admin' },
    { ...catalog(), requests: [request('user/ghkang', 'UNKNOWN')] }, { ...catalog(), requests: [{ ...request('user/ghkang'), author: null }] }]) {
    assert.throws(() => folderCatalog(value));
  }
});


test('inline document tree expands leaf folders, isolates equal filenames, and reveals matching ancestors', () => {
  const state = folderCatalog({ folders: ['common', 'common/개발', 'common/운영'], requests: [], canCreateDirectly: true, canCreateProject: true });
  const files = [
    { documentId: 'dev', path: 'common/개발/동일.md' },
    { documentId: 'ops', path: 'common/운영/동일.md' },
    { documentId: 'hidden', path: 'common/권한밖/비공개.md' },
  ];
  assert.deepEqual(documentTreeRows(state, files, new Set()).map(row => row.path), ['common']);
  const expanded = documentTreeRows(state, files, new Set(['common', 'common/개발']));
  assert.equal(expanded.find(row => row.path === 'common/개발').children, true);
  assert.equal(expanded.some(row => row.documentId === 'dev'), true);
  assert.equal(expanded.some(row => row.documentId === 'ops'), false);
  const found = documentTreeRows(state, files, new Set(), '동일.md');
  assert.deepEqual(found.map(row => row.path), ['common', 'common/개발', 'common/개발/동일.md', 'common/운영', 'common/운영/동일.md']);
  assert.deepEqual(found.filter(row => row.kind === 'file').map(row => row.documentId), ['dev', 'ops']);
  assert.equal(documentTreeRows(state, files, new Set(), '비공개').length, 0);
  assert.deepEqual(documentTreeRows(state, files, new Set(), '개발').map(row => row.path), ['common', 'common/개발', 'common/개발/동일.md']);
});

test('tree search compares Korean NFC and NFD names while retaining original source paths', () => {
  const state = folderCatalog({ folders: ['common'], requests: [], canCreateDirectly: false, canCreateProject: false });
  const path = 'common/보고서.md'.normalize('NFD');
  const rows = documentTreeRows(state, [{ documentId: 'report', path }], new Set(), '보고서');
  assert.equal(rows.at(-1).documentId, 'report'); assert.equal(rows.at(-1).path, path);
});


test('folder-only tree hides documents and filename matches without altering the file-mode tree', () => {
  const state = folderCatalog({ folders: ['user', 'user/ghkang'], requests: [], canCreateDirectly: true, canCreateProject: true });
  const files = [{ documentId: 'guide', path: 'user/ghkang/가이드.md' }];
  const expanded = new Set(['user', 'user/ghkang']);
  const folders = documentTreeRows(state, [], expanded);
  assert.deepEqual(folders.map(row => row.path), ['user', 'user/ghkang']);
  assert.ok(folders.every(row => row.kind === 'folder'));
  assert.equal(folders.at(-1).children, false);
  assert.deepEqual(documentTreeRows(state, [], expanded, '가이드'), []);
  assert.equal(documentTreeRows(state, files, expanded).at(-1).documentId, 'guide');
});
