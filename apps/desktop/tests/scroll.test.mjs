import test from 'node:test';
import assert from 'node:assert/strict';
import { scrollingIndicator, scrollToEnd } from '../src/scroll.ts';

test('only user-driven scrolling shows a thumb for 800ms; automatic streaming and layout changes stay hidden', t => {
  t.mock.timers.enable({apis:['setTimeout','Date']});
  class Area extends EventTarget {
    scrollTop=0; scrollHeight=400;
    classes=new Set();
    classList={add: value=>this.classes.add(value),remove:value=>this.classes.delete(value)};
  }
  const area=new Area(); scrollingIndicator(area);
  area.scrollTop=20; area.dispatchEvent(new Event('scroll'));
  assert.equal(area.classes.has('is-scrolling'),false);
  area.dispatchEvent(new Event('wheel'));
  scrollToEnd(area); area.dispatchEvent(new Event('scroll'));
  assert.equal(area.classes.has('is-scrolling'),false);
  area.scrollTop=100; area.dispatchEvent(new Event('scroll'));
  assert.equal(area.classes.has('is-scrolling'),true);
  t.mock.timers.tick(799); assert.equal(area.classes.has('is-scrolling'),true);
  t.mock.timers.tick(1); assert.equal(area.classes.has('is-scrolling'),false);
  t.mock.timers.tick(201);
  area.scrollTop=0; area.dispatchEvent(new Event('scroll'));
  assert.equal(area.classes.has('is-scrolling'),false);
});
