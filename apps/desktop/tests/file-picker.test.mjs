import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { trackFilePicker } from '../src/file-picker.ts';

test('file selection and cancellation suppress outside closing until queued focus events settle', async () => {
  for (const completion of ['change', 'cancel', 'focus']) {
    const input = new EventTarget();
    const appWindow = new EventTarget();
    const picker = trackFilePicker(input, appWindow);
    assert.equal(picker.active, false);
    input.dispatchEvent(new Event('click'));
    appWindow.dispatchEvent(new Event('blur'));
    assert.equal(picker.active, true);
    (completion === 'focus' ? appWindow : input).dispatchEvent(new Event(completion));
    appWindow.dispatchEvent(new Event('blur'));
    assert.equal(picker.active, true);
    await delay(10);
    assert.equal(picker.active, false);
  }
});

test('reopening the picker cancels the previous pending release', async () => {
  const input = new EventTarget();
  const appWindow = new EventTarget();
  const picker = trackFilePicker(input, appWindow);
  input.dispatchEvent(new Event('click'));
  input.dispatchEvent(new Event('change'));
  input.dispatchEvent(new Event('click'));
  await delay(10);
  assert.equal(picker.active, true);
  input.dispatchEvent(new Event('cancel'));
  await delay(10);
  assert.equal(picker.active, false);
});
