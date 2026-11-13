export type ResizeDirection = 'North' | 'South' | 'West' | 'East' | 'NorthWest' | 'NorthEast' | 'SouthWest' | 'SouthEast';
export interface Rect { x: number; y: number; width: number; height: number }

export function shouldSend(event: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'isComposing' | 'keyCode'>): boolean {
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229;
}

export function isDrag(start: { x: number; y: number }, current: { x: number; y: number }): boolean {
  return Math.hypot(current.x - start.x, current.y - start.y) >= 6;
}

export function fitToWorkArea(current: Rect, width: number, height: number, area: Rect): Rect {
  const w = Math.min(width, area.width);
  const h = Math.min(height, area.height);
  const valid = Object.values(current).every(Number.isFinite) && current.width > 0 && current.height > 0;
  const right = current.x + current.width;
  const bottom = current.y + current.height;
  const visible = valid && right > area.x && current.x < area.x + area.width && bottom > area.y && current.y < area.y + area.height;
  return {
    x: Math.min(Math.max(visible ? right - w : area.x + area.width - w - 24, area.x), area.x + area.width - w),
    y: Math.min(Math.max(visible ? bottom - h : area.y + area.height - h - 24, area.y), area.y + area.height - h),
    width: w,
    height: h,
  };
}

export function mockAnswer(question: string): string {
  const text = question.trim();
  if (!text || text.length > 4000) throw new Error('질문은 1~4000자 이내로 입력해 주세요.');
  return `안녕하세요! “${text}” 질문을 받았어요.

로컬 Mock 체험에서는 예시 답변을 보여 드려요. 실제 답변은 서버에 접속한 뒤 받을 수 있어요.`;
}

const previewMinimum = { width: 328, height: 360 };

export function previewArea(viewportWidth: number, viewportHeight: number): Rect {
  return { x: 16, y: 16, width: Math.max(0, viewportWidth - 32), height: Math.max(0, viewportHeight - 140) };
}

export function fitPreviewSize(width: number, height: number, viewportWidth: number, viewportHeight: number): { width: number; height: number } {
  const area = previewArea(viewportWidth, viewportHeight);
  return { width: Math.min(Math.max(previewMinimum.width, width), area.width), height: Math.min(Math.max(previewMinimum.height, height), area.height) };
}

export function resizeFromEdge(start: Rect, direction: ResizeDirection, dx: number, dy: number, area: Rect, minimum = previewMinimum): Rect {
  let { x, y, width, height } = start;
  const right = x + width;
  const bottom = y + height;
  if (direction.endsWith('West')) {
    const available = right - area.x;
    width = Math.min(available, Math.max(minimum.width, width - dx));
    x = right - width;
  } else if (direction.endsWith('East')) {
    const available = area.x + area.width - x;
    width = Math.min(available, Math.max(minimum.width, width + dx));
  }
  if (direction.startsWith('North')) {
    const available = bottom - area.y;
    height = Math.min(available, Math.max(minimum.height, height - dy));
    y = bottom - height;
  } else if (direction.startsWith('South')) {
    const available = area.y + area.height - y;
    height = Math.min(available, Math.max(minimum.height, height + dy));
  }
  return { x, y, width, height };
}
