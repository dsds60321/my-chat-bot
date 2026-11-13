import assert from 'node:assert/strict';
import test from 'node:test';
import { fitPreviewSize, fitToWorkArea, isDrag, mockAnswer, previewArea, resizeFromEdge, shouldSend } from '../src/logic.ts';

test('Enter sends while Shift+Enter and IME Enter do not', () => {
  const enter = { key: 'Enter', shiftKey: false, isComposing: false, keyCode: 13 };
  assert.equal(shouldSend(enter), true);
  assert.equal(shouldSend({ ...enter, shiftKey: true }), false);
  assert.equal(shouldSend({ ...enter, isComposing: true }), false);
  assert.equal(shouldSend({ ...enter, keyCode: 229 }), false);
});
test('6px movement separates drag from a click', () => {
  assert.equal(isDrag({ x: 10, y: 10 }, { x: 15, y: 10 }), false);
  assert.equal(isDrag({ x: 10, y: 10 }, { x: 16, y: 10 }), true);
});
test('window expands anchored at bottom-right and clamps to work area', () => {
  assert.deepEqual(fitToWorkArea({ x: 900, y: 600, width: 128, height: 128 }, 392, 580, { x: 0, y: 0, width: 1200, height: 800 }), { x: 636, y: 148, width: 392, height: 580 });
  const area = { x: -800, y: 40, width: 800, height: 600 };
  const restored = fitToWorkArea({ x: 4000, y: 4000, width: 128, height: 128 }, 392, 580, area);
  assert.deepEqual(restored, { x: -416, y: 40, width: 392, height: 580 });
  assert.deepEqual(fitToWorkArea({ x: NaN, y: 0, width: 1, height: 1 }, 392, 580, { x: 0, y: 0, width: 320, height: 480 }), { x: 0, y: 0, width: 320, height: 480 });
});
test('local Mock validates input and identifies itself', () => {
  assert.throws(() => mockAnswer('   '));
  assert.throws(() => mockAnswer('x'.repeat(4001)));
  assert.match(mockAnswer('<script>alert(1)</script>'), /<script>alert\(1\)<\/script>/);
  assert.match(mockAnswer('도움'), /로컬 Mock/);
});

test('preview minimum never exceeds available viewport dimensions', () => {
  assert.deepEqual(fitPreviewSize(600,800,320,400),{width:288,height:260});
  assert.deepEqual(fitPreviewSize(200,200,1200,900),{width:328,height:360});
  assert.deepEqual(fitPreviewSize(560,600,1200,900),{width:560,height:600});
});

test('all eight resize directions keep the opposite edges fixed', () => {
  const area = previewArea(1200, 900);
  const start = { x: 300, y: 200, width: 500, height: 440 };
  for (const [direction, x, y, width, height] of [
    ['North', 300, 230, 500, 410], ['South', 300, 200, 500, 470],
    ['West', 340, 200, 460, 440], ['East', 300, 200, 540, 440],
    ['NorthWest', 340, 230, 460, 410], ['NorthEast', 300, 230, 540, 410],
    ['SouthWest', 340, 200, 460, 470], ['SouthEast', 300, 200, 540, 470],
  ]) {
    assert.deepEqual(resizeFromEdge(start, direction, 40, 30, area), { x, y, width, height }, direction);
  }
});

test('resize limits prevent the dragged edge crossing the minimum or the viewport', () => {
  const area = previewArea(1200, 900);
  const start = { x: 300, y: 200, width: 500, height: 440 };
  assert.deepEqual(resizeFromEdge(start, 'NorthWest', -1000, -1000, area), { x: 16, y: 16, width: 784, height: 624 });
  assert.deepEqual(resizeFromEdge(start, 'NorthWest', 1000, 1000, area), { x: 472, y: 280, width: 328, height: 360 });
  assert.deepEqual(resizeFromEdge(start, 'SouthEast', 1000, 1000, area), { x: 300, y: 200, width: 884, height: 576 });
  const tiny = previewArea(320, 400);
  assert.deepEqual(resizeFromEdge(tiny, 'SouthEast', -300, -300, tiny), tiny);
});
