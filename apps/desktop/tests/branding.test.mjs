import test from 'node:test';
import assert from 'node:assert/strict';
import { parseBranding } from '../src/branding.ts';

test('branding accepts the supported themes and can hide the badge', () => {
  for (const theme of ['gold', 'blue', 'green', 'mono']) {
    assert.deepEqual(parseBranding({ name: ' 우리 회사 ', badge: '', theme }),
      { appTitle: '우리 회사', name: '우리 회사', badge: '', theme });
  }
});

test('invalid branding fields fall back independently', () => {
  assert.deepEqual(parseBranding({ name: 'x'.repeat(41), badge: 'TEAM', theme: 'invalid' }),
    { appTitle: 'MyChatBot', name: 'MyChatBot', badge: 'TEAM', theme: 'gold' });
  assert.deepEqual(parseBranding(null), { appTitle: 'MyChatBot', name: 'MyChatBot', badge: 'CHAT', theme: 'gold' });
});

test('a partial PC configuration inherits packaged branding and can override its theme', () => {
  const packaged = parseBranding({ name: '팀 챗봇', badge: 'TEAM', theme: 'mono' });
  assert.deepEqual(parseBranding({ badge: '' }, packaged), { appTitle: '팀 챗봇', name: '팀 챗봇', badge: '', theme: 'mono' });
  assert.equal(parseBranding({ theme: 'gold' }, packaged).theme, 'gold');
});

test('appTitle and chat name change independently with PC overrides', () => {
  const packaged = parseBranding({ appTitle: 'MyChatBot', name: '사내 챗봇', badge: 'E2U' });
  assert.equal(packaged.appTitle, 'MyChatBot');
  assert.equal(packaged.name, '사내 챗봇');
  assert.equal(parseBranding({ name: '지원 봇' }, packaged).appTitle, 'MyChatBot');
  assert.equal(parseBranding({ appTitle: 'My Bot' }, packaged).name, '사내 챗봇');
});
