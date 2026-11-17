import assert from 'node:assert/strict';
import test from 'node:test';
import { mockIPC, mockWindows, clearMocks } from '@tauri-apps/api/mocks';

test('position save failure does not undo successful popup geometry', async () => {
  globalThis.window = {};
  globalThis.isTauri = true;
  mockWindows('main');
  const calls = [];
  mockIPC((command, args) => {
    calls.push([command, args]);
    switch (command) {
      case 'plugin:window|outer_position': return { x: 900, y: 600 };
      case 'plugin:window|outer_size': return { width: 128, height: 128 };
      case 'plugin:window|current_monitor': return null;
      case 'plugin:window|available_monitors': return [{ name: 'test', position: { x: 0, y: 0 }, size: { width: 1200, height: 800 }, scaleFactor: 1, workArea: { position: { x: 0, y: 0 }, size: { width: 1200, height: 800 } } }];
      case 'save_window_geometry': throw new Error('disk write denied');
      case 'plugin:window|set_min_size':
      case 'plugin:window|set_resizable':
      case 'plugin:window|set_size':
      case 'plugin:window|set_position': return;
      default: throw new Error(`Unexpected IPC: ${command}`);
    }
  });
  try {
    const { resizeWindow } = await import('../src/native.ts');
    assert.equal(await resizeWindow(true), false);
    assert.ok(calls.some(([command]) => command === 'plugin:window|set_size'));
    assert.ok(calls.some(([command]) => command === 'plugin:window|set_position'));
  } finally {
    clearMocks();
    delete globalThis.window;
    delete globalThis.isTauri;
  }
});

test('manual native size survives close/reopen and stays within negative-coordinate work area', async () => {
  globalThis.window = {}; globalThis.isTauri = true; mockWindows('main');
  let pos = { x:-900, y:100 };
  let size = { width:700, height:720 };
  let resizable;
  mockIPC((command, args) => {
    switch (command) {
      case 'plugin:window|outer_position': return pos;
      case 'plugin:window|outer_size': return size;
      case 'plugin:window|current_monitor': return null;
      case 'plugin:window|available_monitors': return [{ name:'left',position:{x:-1600,y:0},size:{width:1600,height:1000},scaleFactor:1,workArea:{position:{x:-1600,y:24},size:{width:1600,height:936}} }];
      case 'plugin:window|set_size': size = { width:args.value.size.width, height:args.value.size.height }; return;
      case 'plugin:window|set_position': pos = { x:args.value.position.x, y:args.value.position.y }; return;
      case 'plugin:window|set_resizable': resizable = args.value; return;
      case 'plugin:window|set_min_size':
      case 'save_window_geometry': return;
      default: throw new Error(`Unexpected IPC: ${command}`);
    }
  });
  try {
    const { resizeWindow } = await import('../src/native.ts');
    await resizeWindow(false);
    assert.deepEqual(size,{width:128,height:128});
    assert.equal(resizable,false);
    await resizeWindow(true);
    assert.deepEqual(size,{width:700,height:720});
    assert.equal(resizable,true);
    assert.ok(pos.x < 0 && pos.x >= -1600 && pos.x + size.width <= 0);
    await resizeWindow(true,1000);
    assert.deepEqual(size,{width:1600,height:936});
    assert.deepEqual(pos,{x:-1600,y:24});
  } finally { clearMocks(); delete globalThis.window; delete globalThis.isTauri; }
});

test('native edge drag preserves the opposite corner using physical pixels on a scaled display', async () => {
  globalThis.window = {}; globalThis.isTauri = true; mockWindows('main');
  const commands = [];
  mockIPC((command, args) => {
    switch (command) {
      case 'plugin:window|outer_position': return { x: -1700, y: 240 };
      case 'plugin:window|outer_size': return { width: 1100, height: 1300 };
      case 'plugin:window|current_monitor': return { name: 'retina', position: { x: -2400, y: 0 }, size: { width: 2400, height: 1800 }, scaleFactor: 2, workArea: { position: { x: -2400, y: 48 }, size: { width: 2400, height: 1700 } } };
      case 'plugin:window|set_size':
      case 'plugin:window|set_position': commands.push([command, args.value.toJSON()]); return;
      default: throw new Error(`Unexpected IPC: ${command}`);
    }
  });
  try {
    const { startResizing } = await import('../src/native.ts');
    const resize = await startResizing('NorthWest');
    await resize(-100, -80);
    await resize(-100, -80);
    assert.deepEqual(commands, [
      ['plugin:window|set_size', { Physical: { width: 1300, height: 1460 } }],
      ['plugin:window|set_position', { Physical: { x: -1900, y: 80 } }],
    ]);
  } finally { clearMocks(); delete globalThis.window; delete globalThis.isTauri; }
});

test('native drag uses the current monitor scale when mixed-DPI coordinates overlap', async () => {
  globalThis.window = {}; globalThis.isTauri = true; mockWindows('main');
  const position = { x: 1800, y: 120 };
  const external = { name: 'external', position: { x: 1728, y: 0 }, size: { width: 1920, height: 1080 }, scaleFactor: 1, workArea: { position: { x: 1728, y: 24 }, size: { width: 1920, height: 1056 } } };
  const retina = { name: 'retina', position: { x: 0, y: 0 }, size: { width: 3456, height: 2234 }, scaleFactor: 2, workArea: { position: { x: 0, y: 48 }, size: { width: 3456, height: 2186 } } };
  const commands = [];
  mockIPC((command, args) => {
    switch (command) {
      case 'plugin:window|outer_position': return position;
      case 'plugin:window|outer_size': return { width: 700, height: 700 };
      case 'plugin:window|current_monitor': return external;
      case 'plugin:window|available_monitors': return [retina, external];
      case 'plugin:window|set_size':
      case 'plugin:window|set_position': commands.push([command, args.value.toJSON()]); return;
      default: throw new Error(`Unexpected IPC: ${command}`);
    }
  });
  try {
    const { startResizing } = await import('../src/native.ts');
    const resize = await startResizing('East');
    await resize(40, 0);
    assert.deepEqual(commands, [
      ['plugin:window|set_size', { Physical: { width: 740, height: 700 } }],
      ['plugin:window|set_position', { Physical: position }],
    ]);
  } finally { clearMocks(); delete globalThis.window; delete globalThis.isTauri; }
});

test('native drag tracks window vectors across a held pause and ends when the mouse button is released', async t => {
  const { Window } = await import('@tauri-apps/api/window');
  globalThis.window = {}; globalThis.isTauri = true; mockWindows('main');
  t.mock.timers.enable({ apis:['setTimeout'] });
  let moved; let focused; let held = true; let removed = 0; let ended = 0; let paused = 0;
  t.mock.method(Window.prototype, 'outerPosition', async () => ({ x:100, y:100 }));
  t.mock.method(Window.prototype, 'onMoved', async handler => { moved = handler; return () => { removed++; }; });
  t.mock.method(Window.prototype, 'onFocusChanged', async handler => { focused = handler; return () => { removed++; }; });
  mockIPC(command => {
    assert.equal(command, 'pet_drag_button_down');
    return held;
  });
  const flush = async () => { for(let i=0;i<10;i++) await Promise.resolve(); };
  try {
    const { watchPetDrag } = await import('../src/native.ts');
    const deltas = [];
    const stop = await watchPetDrag((x,y) => deltas.push([x,y]), () => { ended++; }, () => { paused++; });
    moved({payload:{x:140,y:100}});
    t.mock.timers.tick(80); await flush();
    t.mock.timers.tick(1000); await flush();
    focused({payload:false}); await flush();
    moved({payload:{x:110,y:80}});
    assert.deepEqual(deltas,[[40,0],[-30,-20]]);
    assert.equal(ended,0); assert.equal(paused,0);
    held = false;
    t.mock.timers.tick(80); await flush();
    assert.equal(ended,1); assert.equal(removed,2);
    moved({payload:{x:200,y:200}}); stop();
    assert.equal(deltas.length,2); assert.equal(ended,1);
  } finally { clearMocks(); delete globalThis.window; delete globalThis.isTauri; }
});

test('unsupported mouse-button tracking pauses motion but can observe resumed movement and cleans up', async t => {
  const { Window } = await import('@tauri-apps/api/window');
  globalThis.window = {}; globalThis.isTauri = true; mockWindows('main');
  t.mock.timers.enable({apis:['setTimeout']});
  let moved; let removed=0; let paused=0; let ended=0;
  t.mock.method(Window.prototype,'outerPosition',async()=>({x:0,y:0}));
  t.mock.method(Window.prototype,'onMoved',async handler=>{moved=handler;return()=>{removed++;};});
  t.mock.method(Window.prototype,'onFocusChanged',async()=>()=>{removed++;});
  mockIPC(()=>null);
  try {
    const {watchPetDrag}=await import('../src/native.ts');
    const deltas=[];
    const stop=await watchPetDrag((x,y)=>deltas.push([x,y]),()=>{ended++;},()=>{paused++;});
    t.mock.timers.tick(80);
    for(let i=0;i<10;i++)await Promise.resolve();
    t.mock.timers.tick(650);
    assert.equal(paused,1);assert.equal(ended,0);
    moved({payload:{x:-10,y:12}});
    assert.deepEqual(deltas,[[-10,12]]);
    stop(); assert.equal(removed,2);assert.equal(ended,1);
    t.mock.timers.tick(1000);assert.equal(paused,1);
  } finally {clearMocks();delete globalThis.window;delete globalThis.isTauri;}
});

test('a delayed native blur after a file dialog closes does not collapse the restored window', async t => {
  const { Window } = await import('@tauri-apps/api/window');
  globalThis.window = { crypto: globalThis.crypto }; globalThis.isTauri = true; mockWindows('main');
  let focusChanged; let currentlyFocused = true; let closed = 0;
  t.mock.method(Window.prototype, 'onFocusChanged', async handler => { focusChanged = handler; return () => {}; });
  t.mock.method(Window.prototype, 'isFocused', async () => currentlyFocused);
  t.mock.method(Window.prototype, 'onCloseRequested', async () => () => {});
  mockIPC(command => {
    switch (command) {
      case 'plugin:window|outer_position': return { x: 100, y: 100 };
      case 'plugin:window|outer_size': return { width: 128, height: 128 };
      case 'plugin:window|current_monitor': return { position: { x: 0, y: 0 }, size: { width: 1600, height: 1000 }, workArea: { position: { x: 0, y: 24 }, size: { width: 1600, height: 976 } }, scaleFactor: 1 };
      case 'plugin:event|listen': return 1;
      case 'plugin:window|set_min_size':
      case 'plugin:window|set_resizable':
      case 'plugin:window|set_size':
      case 'plugin:window|set_position':
      case 'plugin:window|show':
      case 'plugin:window|set_focus':
      case 'save_window_geometry': return;
      default: throw new Error(`Unexpected IPC: ${command}`);
    }
  });
  try {
    const { initializeWindow } = await import('../src/native.ts');
    await initializeWindow(async () => {}, true, () => { closed++; });
    await focusChanged({ payload: false });
    assert.equal(closed, 0);
    currentlyFocused = false;
    await focusChanged({ payload: false });
    assert.equal(closed, 1);
  } finally { clearMocks(); delete globalThis.window; delete globalThis.isTauri; }
});
