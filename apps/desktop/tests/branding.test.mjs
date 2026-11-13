import test from 'node:test';
import assert from 'node:assert/strict';
import { parseBranding } from '../src/branding.ts';

test('branding accepts the supported themes and can hide the badge', () => {
  for (const theme of ['gold', 'blue', 'green', 'mono']) {
    assert.deepEqual(parseBranding({ name: ' 우리 회사 ', badge: '', theme }),
      { name: '우리 회사', badge: '', theme });
  }
});

test('invalid branding fields fall back independently', () => {
  assert.deepEqual(parseBranding({ name: 'x'.repeat(41), badge: 'TEAM', theme: 'invalid' }),
    { name: 'MyChatBot', badge: 'TEAM', theme: 'gold' });
  assert.deepEqual(parseBranding(null), { name: 'MyChatBot', badge: 'CHAT', theme: 'gold' });
});

test('a partial PC configuration inherits packaged branding and can override its theme', () => {
  const packaged = parseBranding({ name: '팀 챗봇', badge: 'TEAM', theme: 'mono' });
  assert.deepEqual(parseBranding({ badge: '' }, packaged), { name: '팀 챗봇', badge: '', theme: 'mono' });
  assert.equal(parseBranding({ theme: 'gold' }, packaged).theme, 'gold');
});
