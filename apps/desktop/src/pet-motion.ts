export interface DragPose { x: number; y: number; angle: number; pitch: number; stretch: number }
export interface PetPose extends DragPose { motion: 'idle' | 'dragging' | 'thinking' }
const resting: DragPose = { x: 0, y: 0, angle: 0, pitch: 0, stretch: 1 };

export function dragPose(dx: number, dy: number): DragPose {
  const distance = Math.hypot(dx, dy);
  if (!Number.isFinite(distance) || distance === 0) return { ...resting };
  const strength = Math.min(distance / 12, 1);
  const x = dx / distance * strength;
  const y = dy / distance * strength;
  return { x: x * 4, y: y * 5, angle: x * 13, pitch: -y * 15, stretch: 1 - y * 0.06 };
}

export class PetMotion {
  private thinking = false;
  private dragging = false;
  private pose = resting;
  private readonly render: (pose: PetPose) => void;
  constructor(render: (pose: PetPose) => void) { this.render = render; this.update(); }
  setThinking(value: boolean): void { this.thinking = value; this.update(); }
  beginDrag(dx: number, dy: number): void {
    this.dragging = true; this.pose = dragPose(dx, dy); this.update();
  }
  move(dx: number, dy: number): void {
    if (!this.dragging || (!dx && !dy)) return;
    this.pose = dragPose(dx, dy); this.update();
  }
  endDrag(): void { this.dragging = false; this.pose = resting; this.update(); }
  private update(): void {
    this.render({ ...(this.dragging ? this.pose : resting), motion: this.dragging ? 'dragging' : this.thinking ? 'thinking' : 'idle' });
  }
}
