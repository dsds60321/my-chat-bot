import test from 'node:test';
import assert from 'node:assert/strict';
import { dragPose, PetMotion } from '../src/pet-motion.ts';

test('drag leans left/right/up/down with bounded, finite transforms', () => {
  const right = dragPose(30, 0); const left = dragPose(-30, 0);
  assert.ok(right.x > 0 && right.angle > 0 && left.x < 0 && left.angle < 0);
  const up = dragPose(0, -30); const down = dragPose(0, 30);
  assert.ok(up.y < 0 && up.pitch > 0 && up.stretch > 1);
  assert.ok(down.y > 0 && down.pitch < 0 && down.stretch < 1);
  assert.deepEqual(dragPose(Infinity, 2), dragPose(0, 0));
  const diagonal = dragPose(-100000, 100000);
  assert.ok(diagonal.x < 0 && diagonal.y > 0 && Math.abs(diagonal.angle) <= 13 && Math.abs(diagonal.pitch) <= 15);
});

test('latest movement reverses the pose and dropping during an active answer restores thinking', () => {
  const poses = [];
  const motion = new PetMotion(pose => poses.push(pose));
  assert.equal(poses.at(-1).motion, 'idle');
  motion.setThinking(true);
  assert.equal(poses.at(-1).motion, 'thinking');
  motion.beginDrag(30, 0); motion.move(-5, 0);
  assert.equal(poses.at(-1).motion, 'dragging');
  assert.ok(poses.at(-1).angle < 0);
  motion.endDrag();
  assert.equal(poses.at(-1).motion, 'thinking');
  assert.equal(poses.at(-1).angle, 0);
  motion.setThinking(false);
  assert.equal(poses.at(-1).motion, 'idle');
  motion.move(20, 0);
  assert.equal(poses.at(-1).motion, 'idle');
});
