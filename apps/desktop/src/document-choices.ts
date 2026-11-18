import type { StreamEvent } from './chat.ts';
import { AuthenticationError } from './session.ts';
import {
  safeSources, sourceFile, sourceName, renderSources, renderDownloadFiles, snapshotText,
  type AnswerSnapshot, type Attachment, type SourceFile,
} from './sources.ts';

interface Actions {
  load(question: string, file: SourceFile, controller: AbortController, onEvent: (event: StreamEvent) => void): Promise<void>;
  download(file: SourceFile): void;
  preview(file: Attachment, image: HTMLImageElement): void;
  read(snapshot: AnswerSnapshot): Promise<void>;
  previewFile?(file: Attachment, area: HTMLElement): void | Promise<void>;
}

export function setupDocumentChoices(container: HTMLElement, actions: Actions) {
  let generation = 0;
  let queue: Promise<void> = Promise.resolve();
  let active: AbortController | null = null;
  let sections: HTMLDetailsElement[] = [];

  function show(question: string, items: unknown): void {
    generation++;
    active?.abort();
    const current = generation;
    const candidates = safeSources(items).filter(source => sourceFile(source));
    sections = [];
    container.replaceChildren();
    container.hidden = candidates.length === 0;
    candidates.forEach((source, index) => {
      const file = sourceFile(source)!;
      const section = document.createElement('details');
      const summary = document.createElement('summary');
      const name = source.path.startsWith('.approved/') ? source.title : sourceName(source);
      summary.textContent = `${index + 1}. ${name}`;
      const detail = document.createElement('span');
      detail.textContent = [source.title, source.section, source.row ? `행 ${source.row}` : ''].filter(Boolean).join(' · ');
      summary.append(detail);
      const answer = document.createElement('p'); answer.className = 'answer';
      const downloads = document.createElement('div'); downloads.className = 'compact-file-list';
      renderDownloadFiles(downloads, [source], actions.download, actions.previewFile);
      const status = document.createElement('p'); status.className = 'status'; status.setAttribute('role', 'status');
      const files = document.createElement('ul'); files.className = 'sources'; files.hidden = true;
      const tools = document.createElement('div'); tools.className = 'request-actions';
      const read = document.createElement('button'); read.type = 'button'; read.textContent = '새창 ↗'; read.disabled = true;
      const copy = document.createElement('button'); copy.type = 'button'; copy.textContent = '복사'; copy.disabled = true;
      const retry = document.createElement('button'); retry.type = 'button'; retry.textContent = '다시 시도'; retry.hidden = true;
      tools.append(read, copy, retry);
      section.append(summary, downloads, answer, files, tools, status);
      container.append(section); sections.push(section);
      let snapshot: AnswerSnapshot = { answer: '', sources: [], partial: true };
      let complete = false;
      let pending = false;
      read.addEventListener('click', () => {
        void actions.read(snapshot).catch(() => { status.textContent = '답변 새창을 열지 못했어요.'; });
      });
      copy.addEventListener('click', () => {
        void navigator.clipboard.writeText(snapshotText(snapshot))
          .then(() => { status.textContent = '답변과 출처를 복사했어요.'; })
          .catch(() => { status.textContent = '텍스트를 선택해 직접 복사해 주세요.'; });
      });
      function load(): void {
        if (pending || complete) return;
        pending = true; retry.hidden = true; status.textContent = '답변을 기다리고 있어요.';
        queue = queue.catch(() => {}).then(async () => {
          if (generation !== current || !section.open) { pending = false; return; }
          const controller = new AbortController(); active = controller;
          snapshot = { answer: '', sources: [], partial: true };
          answer.textContent = ''; files.replaceChildren(); files.hidden = true;
          read.disabled = true; copy.disabled = true; status.textContent = '이 문서만 기준으로 답변하고 있어요.';
          try {
            await actions.load(question, file, controller, event => {
              if (generation !== current) return;
              if (event.event === 'delta' && typeof event.data.text === 'string') {
                snapshot.answer += event.data.text; answer.textContent = snapshot.answer;
              }
              if (event.event === 'sources') {
                snapshot.sources = safeSources(event.data.items);
                renderDownloadFiles(downloads, snapshot.sources, actions.download, actions.previewFile);
              }
            });
            if (generation !== current) return;
            if (!snapshot.answer.trim()) throw new Error('답변을 받지 못했어요. 다시 시도해 주세요.');
            complete = true; snapshot.partial = false; status.textContent = '';
          } catch (error) {
            if (generation !== current) return;
            status.textContent = controller.signal.aborted ? '응답을 취소했어요.' : error instanceof Error ? error.message : '답변을 받지 못했어요.';
            retry.hidden = error instanceof AuthenticationError;
          } finally {
            pending = false;
            if (active === controller) active = null;
            if (generation === current) {
              read.disabled = !snapshot.answer.trim(); copy.disabled = read.disabled;
              renderSources(files, snapshot.sources, actions.download, actions.preview, false, false);
            }
          }
        });
      }
      section.addEventListener('toggle', () => { if (section.open) load(); });
      retry.addEventListener('click', load);
    });
  }

  return {
    show,
    toggle(index: number): boolean {
      const section = sections[index];
      if (!section) return false;
      section.open = !section.open;
      return true;
    },
  };
}
