import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { LogicalSize, PhysicalPosition, PhysicalSize } from '@tauri-apps/api/dpi';
import { availableMonitors, currentMonitor, getCurrentWindow, primaryMonitor, type Window } from '@tauri-apps/api/window';
import { saveWindowState, StateFlags } from '@tauri-apps/plugin-window-state';
import { fitToWorkArea, resizeFromEdge, type ResizeDirection } from './logic.ts';

export const native = isTauri();
let expandedSize = { width: 392, height: 580 };
let expanded = false;

async function windowGeometry(win: Window) {
  const [pos, size, current] = await Promise.all([win.outerPosition(), win.outerSize(), currentMonitor()]);
  const monitor = current ?? (await availableMonitors()).find(m => {
    const x = pos.x + size.width - 48 * m.scaleFactor;
    const y = pos.y + size.height - 48 * m.scaleFactor;
    return x >= m.position.x && x <= m.position.x + m.size.width && y >= m.position.y && y <= m.position.y + m.size.height;
  }) ?? await primaryMonitor();
  if (!monitor) throw new Error('사용 가능한 모니터를 찾지 못했어요.');
  const { position, size: workSize } = monitor.workArea;
  return {
    current: { x: pos.x, y: pos.y, width: size.width, height: size.height },
    area: { x: position.x, y: position.y, width: workSize.width, height: workSize.height },
    scale: monitor.scaleFactor,
  };
}

export async function resizeWindow(open: boolean, adjustment = 0): Promise<boolean> {
  if (!native) return true;
  const win = getCurrentWindow();
  const { current, area, scale } = await windowGeometry(win);
  if (expanded) expandedSize = { width: current.width / scale, height: current.height / scale };
  if (adjustment) expandedSize = { width: Math.max(360, expandedSize.width + adjustment), height: Math.max(580, expandedSize.height + adjustment) };
  const rect = fitToWorkArea(current, (open ? expandedSize.width : 128) * scale, (open ? expandedSize.height : 128) * scale, area);
  await win.setMinSize(open ? new LogicalSize(Math.min(360, area.width / scale), Math.min(580, area.height / scale)) : null);
  await win.setResizable(open);
  await win.setSize(new LogicalSize(rect.width / scale, rect.height / scale));
  await win.setPosition(new PhysicalPosition(rect.x, rect.y));
  expanded = open;
  try { await saveWindowState(StateFlags.POSITION | StateFlags.SIZE); return true; }
  catch { return false; }
}

export async function startDragging(): Promise<void> {
  if (native) await getCurrentWindow().startDragging();
}

export async function watchPetDrag(onMove: (dx: number, dy: number) => void, onEnd: () => void, onPause: () => void): Promise<() => void> {
  const win = getCurrentWindow();
  let previous = await win.outerPosition();
  let active = true;
  let idle: ReturnType<typeof setTimeout>;
  let poll: ReturnType<typeof setTimeout>;
  let nativeButton = false;
  let checkingButton = false;
  const unlisten: Array<() => void> = [];
  const stop = () => {
    if (!active) return;
    active = false; clearTimeout(idle); clearTimeout(poll);
    unlisten.forEach(remove => remove());
    onEnd();
  };
  const resetTimeout = () => {
    clearTimeout(idle);
    if (!nativeButton) idle = setTimeout(onPause, 650);
  };
  const checkButton = async () => {
    if (!active || checkingButton) return;
    checkingButton = true;
    let down: boolean | null;
    try { down = await invoke<boolean | null>('pet_drag_button_down'); }
    catch { down = null; }
    checkingButton = false;
    if (!active) return;
    if (down === false) { stop(); return; }
    nativeButton = down === true;
    if (nativeButton) { clearTimeout(idle); poll = setTimeout(() => { void checkButton(); }, 80); }
    else resetTimeout();
  };
  const listeners = await Promise.allSettled([
    win.onMoved(({ payload }) => {
      if (!active) return;
      const dx = payload.x - previous.x;
      const dy = payload.y - previous.y;
      previous = payload;
      if (dx || dy) { onMove(dx, dy); resetTimeout(); }
    }),
    win.onFocusChanged(({ payload }) => { if (!payload) void checkButton(); }),
  ]);
  let failed = false;
  for (const listener of listeners) {
    if (listener.status === 'fulfilled') {
      if (active) unlisten.push(listener.value); else listener.value();
    } else failed = true;
  }
  if (failed) { stop(); throw new Error('펫 이동 상태를 확인하지 못했어요.'); }
  if (active) { resetTimeout(); poll = setTimeout(() => { void checkButton(); }, 80); }
  return stop;
}

export async function initializeWindow(onShow: () => Promise<void>, initialOpen = false, onFocusLost?: () => void): Promise<void> {
  if (!native) return;
  const win = getCurrentWindow();
  await listen('pet:show', onShow);
  await win.onCloseRequested(async (event) => { event.preventDefault(); await win.hide(); });
  if (onFocusLost) await win.onFocusChanged(({ payload }) => { if (!payload) onFocusLost(); });
  await resizeWindow(initialOpen);
  await win.show();
  await win.setFocus();
}

export async function showAfterFailure(): Promise<void> {
  if (native) await getCurrentWindow().show();
}

export async function startResizing(direction: ResizeDirection) {
  const win = getCurrentWindow();
  const { current, area, scale } = await windowGeometry(win);
  const start = fitToWorkArea(current, current.width, current.height, area);
  const minimum = { width: Math.min(360 * scale, area.width), height: Math.min(580 * scale, area.height) };
  let last = current;
  // Tao's native resize drag is unsupported on macOS.
  return async (dx: number, dy: number): Promise<void> => {
    const next = resizeFromEdge(start, direction, Math.round(dx * scale), Math.round(dy * scale), area, minimum);
    const sizeChanged = next.width !== last.width || next.height !== last.height;
    if (sizeChanged) await win.setSize(new PhysicalSize(next.width, next.height));
    if (sizeChanged || next.x !== last.x || next.y !== last.y) await win.setPosition(new PhysicalPosition(next.x, next.y));
    last = next;
  };
}
