import test from 'node:test';
import assert from 'node:assert/strict';
import { LoginSession, AuthenticationError } from '../src/session.ts';

test('session is established only after real verification and keeps credentials in memory until logout', async t => {
  const session = new LoginSession();
  const username = '개발자';
  const password = 'test-only-password';
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:9001/api/session');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.redirect, 'error');
    const decoded = new TextDecoder().decode(Uint8Array.from(atob(options.headers.Authorization.slice(6)), c => c.charCodeAt(0)));
    assert.equal(decoded, `${username}:${password}`);
    assert.equal(options.body, undefined);
    return Response.json({ username, projects: [] });
  });
  assert.equal(session.active, false);
  await session.login(username, password);
  assert.deepEqual(session.user, { username, projects: [] });
  assert.equal(session.active, true);
  assert.deepEqual(session.auth(), { username, password });
  // Opening/closing the pet has no session operation; the same in-memory instance remains active.
  assert.equal(session.active, true);
  session.logout();
  assert.equal(session.active, false);
  assert.equal(session.user, null);
  assert.throws(() => session.auth(), AuthenticationError);
});

test('failed, missing and malformed login responses never establish a session or show server bodies', async t => {
  const session = new LoginSession();
  await assert.rejects(session.login('u', ''), AuthenticationError);
  for (const response of [new Response('private server text', { status: 401 }), new Response('', { status: 404 }), Response.json({ username:'u', projects:'admin' }), new Response('synthetic-private-invalid-json')]) {
    const mock = t.mock.method(globalThis, 'fetch', async () => response);
    await assert.rejects(session.login('u', 'test-only'), error => !error.message.includes('private'));
    assert.equal(session.active, false);
    mock.mock.restore();
  }
  const mock = t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('Failed to fetch'); });
  await assert.rejects(session.login('u', 'test-only'), TypeError);
  assert.equal(session.active, false);
  mock.mock.restore();
});

test('Mock requires no server, removes prior account and logout ends the session', async t => {
  const session = new LoginSession();
  t.mock.method(globalThis, 'fetch', async () => Response.json({ username:'u', projects:['trusted'] }));
  await session.login('u', 'test-only');
  session.useMock();
  assert.equal(session.active, true);
  assert.equal(session.local, true);
  assert.equal(session.user, null);
  assert.throws(() => session.auth(), AuthenticationError);
  session.logout();
  assert.equal(session.active, false);
});

test('aborted verification cannot commit a login', async t => {
  const controller = new AbortController();
  t.mock.method(globalThis, 'fetch', async () => { controller.abort(); return Response.json({ username:'u', projects:[] }); });
  const session = new LoginSession();
  await assert.rejects(session.login('u', 'test-only', controller.signal), { name:'AbortError' });
  assert.equal(session.active, false);
});
