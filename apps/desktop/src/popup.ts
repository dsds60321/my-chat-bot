import { invoke } from '@tauri-apps/api/core';
import { emitTo } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { native } from './native';
import type { Source } from './chat';
import { answerSnapshot, findSourceFile, sourceFile, type AnswerSnapshot, type SourceFile } from './sources';

let preview: Window | null = null;
let transfer: ((event: MessageEvent) => void) | null = null;
let readerSources: Source[] = [];
export function listenForSourceDownloads(download: (source: SourceFile) => Promise<string>): void {
  const handle = async (value: unknown): Promise<string> => {
    const file = sourceFile(value);
    const source = file && findSourceFile(readerSources, file);
    if (!source) return '읽기 창의 출처를 찾지 못했어요. 대화창에서 다시 열어 주세요.';
    return download(source);
  };
  if (native) {
    void getCurrentWindow().listen('source:download', async event => {
      const message = await handle(event.payload);
      await emitTo('answer', 'source:result', message);
    });
  } else {
    window.addEventListener('message', async event => {
      const child = preview;
      if (!child || event.origin !== location.origin || event.source !== child || event.data?.type !== 'source:download') return;
      const message = await handle(event.data.file);
      child.postMessage({ type: 'source:result', message }, location.origin);
    });
  }
}
export async function openAnswer(value: AnswerSnapshot): Promise<void> {
  const snapshot = answerSnapshot(value);
  readerSources = snapshot.sources;
  if (native) { await invoke('open_answer', { snapshot: JSON.stringify(snapshot) }); return; }
  if (transfer) window.removeEventListener('message', transfer);
  preview?.close();
  preview = window.open('/answer.html', 'my-chat-bot-answer', 'popup,width=640,height=720,resizable=yes,scrollbars=yes');
  if (!preview) throw new Error('읽기 창이 차단되었어요. 브라우저 팝업을 허용해 주세요.');
  const child = preview;
  transfer = event => {
    if (event.origin !== location.origin || event.source !== child || event.data !== 'answer:ready') return;
    child.postMessage(snapshot, location.origin);
    if (transfer) window.removeEventListener('message', transfer);
    transfer = null;
  };
  window.addEventListener('message', transfer);
}
export async function closeAnswer(): Promise<void> {
  readerSources = [];
  if (transfer) window.removeEventListener('message', transfer);
  transfer = null; preview?.close(); preview = null;
  if (native) await invoke('close_answer');
}
