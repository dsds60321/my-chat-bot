// Native file dialogs transfer focus away from the app without being an outside click.
export function trackFilePicker(input: EventTarget, appWindow: EventTarget) {
  let active = false;
  let release: ReturnType<typeof setTimeout> | undefined;
  const finish = () => {
    if (!active) return;
    clearTimeout(release);
    // Let the file dialog's queued focus events finish before outside-click handling resumes.
    release = setTimeout(() => { active = false; }, 0);
  };
  input.addEventListener('click', () => {
    clearTimeout(release);
    active = true;
  });
  input.addEventListener('change', finish);
  input.addEventListener('cancel', finish);
  appWindow.addEventListener('focus', finish);
  return { get active() { return active; } };
}
