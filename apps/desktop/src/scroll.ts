const automatic = new WeakMap<HTMLElement, number>();
export function scrollToEnd(element: HTMLElement): void {
  element.scrollTop = element.scrollHeight;
  automatic.set(element, element.scrollTop);
}
export function scrollingIndicator(element: HTMLElement): void {
  let timer: ReturnType<typeof setTimeout>;
  let intentUntil = -1;
  const intent = () => { intentUntil = Date.now() + 1000; };
  element.addEventListener('wheel', intent, { passive: true });
  element.addEventListener('touchmove', intent, { passive: true });
  element.addEventListener('pointerdown', intent, { passive: true });
  element.addEventListener('pointermove', event => { if (event.buttons) intent(); }, { passive: true });
  element.addEventListener('keydown', event => { if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) intent(); });
  element.addEventListener('scroll', () => {
    const target = automatic.get(element);
    automatic.delete(element);
    if (target === element.scrollTop || Date.now() > intentUntil) return;
    element.classList.add('is-scrolling');
    clearTimeout(timer);
    timer = setTimeout(() => element.classList.remove('is-scrolling'), 800);
  }, { passive: true });
}
