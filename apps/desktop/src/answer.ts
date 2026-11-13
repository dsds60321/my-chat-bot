import './style.css';
import { applyBranding } from './branding';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { emitTo, listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { answerSnapshot, renderSources, snapshotText, type AnswerSnapshot, type SourceFile } from './sources';
import { scrollingIndicator } from './scroll';

void applyBranding();

const status = document.getElementById('status')!;
const copy = document.getElementById('copy') as HTMLButtonElement;
const pin = document.getElementById('pin') as HTMLButtonElement;
let snapshot: AnswerSnapshot | null = null;
const download = (file: SourceFile): void => {
  status.textContent = '파일 사본을 다운로드하고 있어요.';
  if (isTauri()) {
    void emitTo('main', 'source:download', file).catch(() => { status.textContent = '대화창에서 파일을 다운로드해 주세요.'; });
  } else if (window.opener) window.opener.postMessage({ type: 'source:download', file }, location.origin);
  else status.textContent = '대화창에서 파일을 다운로드해 주세요.';
};
function show(value: unknown): void {
  try {
    snapshot = answerSnapshot(value);
    document.getElementById('answer')!.textContent = snapshot.answer;
    renderSources(document.getElementById('sources') as HTMLUListElement, snapshot.sources, download);
    document.getElementById('answer-state')!.textContent = snapshot.partial ? '부분 답변' : '';
    copy.disabled = false; status.textContent = '';
  } catch { status.textContent = '답변을 불러오지 못했어요. 대화창에서 다시 열어 주세요.'; }
}
copy.addEventListener('click', async () => {
  if (!snapshot) return;
  try { await navigator.clipboard.writeText(snapshotText(snapshot)); status.textContent = '답변과 출처를 복사했어요.'; }
  catch { status.textContent = '복사 권한이 없어요. 텍스트를 선택해 직접 복사해 주세요.'; }
});
scrollingIndicator(document.getElementById('reader-content')!);
if (isTauri()) {
  void getCurrentWindow().listen<string>('source:result', event => { status.textContent = event.payload; });
  let pinned = true;
  pin.addEventListener('click', async () => {
    try { await getCurrentWindow().setAlwaysOnTop(!pinned); pinned = !pinned; pin.setAttribute('aria-pressed', String(pinned)); pin.textContent = `항상 위 ${pinned ? '켜짐' : '꺼짐'}`; }
    catch { status.textContent = '항상 위 설정을 바꾸지 못했어요.'; }
  });
  const load = async () => { try { const data = await invoke<string | null>('read_answer'); if (data) show(JSON.parse(data)); } catch { status.textContent = '답변을 불러오지 못했어요.'; } };
  void listen('answer:update', load).then(load);
} else {
  pin.disabled = true; pin.textContent = '항상 위 · 앱에서 지원';
  window.addEventListener('message', event => {
    if (event.origin !== location.origin || event.source !== window.opener) return;
    if (event.data?.type === 'source:result') status.textContent = event.data.message;
    else show(event.data);
  });
  window.opener?.postMessage('answer:ready', location.origin);
}
