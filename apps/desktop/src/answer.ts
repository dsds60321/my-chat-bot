import './style.css';
import { applyBranding } from './branding';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { emitTo, listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { answerSnapshot, displaySourceImage, sourceFile, renderSources, appendAttachments, sourceDownloadFiles, snapshotText, type AnswerSnapshot, type SourceFile } from './sources';
import { scrollingIndicator } from './scroll';

void applyBranding();

const status = document.getElementById('status')!;
const copy = document.getElementById('copy') as HTMLButtonElement;
const pin = document.getElementById('pin') as HTMLButtonElement;
let snapshot: AnswerSnapshot | null = null;
const pendingImages = new Map<string, HTMLImageElement>();
const imageKey = (file: SourceFile) => `${file.documentId}:${file.version}`;
const previewImage = (file: SourceFile, image: HTMLImageElement): void => {
  const key = imageKey(file);
  if (pendingImages.has(key)) return;
  pendingImages.set(key, image);
  if (isTauri()) {
    void emitTo('main', 'source:preview', file).catch(() => {
      pendingImages.delete(key); status.textContent = '이미지를 불러오지 못했어요. 다시 눌러 주세요.';
    });
  } else if (window.opener) window.opener.postMessage({ type: 'source:preview', file }, location.origin);
  else { pendingImages.delete(key); status.textContent = '대화창에서 이미지를 확인해 주세요.'; }
};
async function showImage(value: unknown): Promise<void> {
  const file = sourceFile(value);
  if (!file || !value || typeof value !== 'object') return;
  const key = imageKey(file);
  const image = pendingImages.get(key);
  pendingImages.delete(key);
  if (!image?.isConnected) return;
  if ('error' in value) {
    status.textContent = typeof value.error === 'string' ? value.error : '이미지를 불러오지 못했어요.';
    return;
  }
  try { await displaySourceImage(image, 'image' in value ? value.image : null); }
  catch (error) { status.textContent = error instanceof Error ? error.message : '이미지를 불러오지 못했어요.'; }
}
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
    pendingImages.clear();
    document.getElementById('answer')!.textContent = snapshot.answer;
    const files = document.getElementById('answer-files')!; files.replaceChildren();
    const attachments = sourceDownloadFiles(snapshot.sources); files.hidden = !attachments.length;
    appendAttachments(files, attachments, (file, image) => image ? previewImage(file, image) : download(file), true, false);
    renderSources(document.getElementById('sources') as HTMLUListElement, snapshot.sources, download, previewImage, false, false);
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
  void getCurrentWindow().listen('source:preview-result', event => { void showImage(event.payload); });
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
    if (event.data?.type === 'source:preview-result') { void showImage(event.data); return; }
    if (event.data?.type === 'source:result') status.textContent = event.data.message;
    else show(event.data);
  });
  window.opener?.postMessage('answer:ready', location.origin);
}
