import type { Source } from './chat.ts';

export type SourceFile = { documentId: string; version: string };
export function sourceFile(value: unknown): SourceFile | null {
  if (!value || typeof value !== 'object' || !('documentId' in value) || !('version' in value)) return null;
  const { documentId, version } = value;
  return typeof documentId === 'string' && typeof version === 'string'
    && /^[a-f0-9]{64}$/.test(documentId) && /^[a-f0-9]{64}$/.test(version) ? { documentId, version } : null;
}

export type Attachment = SourceFile & { filename: string; description: string; image: boolean };
export function safeAttachments(items: unknown): Attachment[] {
  if (!Array.isArray(items)) return [];
  return items.slice(0, 3).flatMap(item => {
    const file = sourceFile(item);
    return file && typeof item.filename === 'string' && item.filename.length <= 180
      && typeof item.description === 'string' && item.description.length <= 500
      && typeof item.image === 'boolean'
      ? [{ ...file, filename: item.filename, description: item.description, image: item.image }] : [];
  });
}

export function safeSources(items: unknown): Source[] {
  if (!Array.isArray(items)) return [];
  return items.slice(0, 10).filter((s): s is Source => !!s && typeof s === 'object'
    && typeof s.title === 'string' && s.title.length <= 1000 && typeof s.path === 'string' && s.path.length <= 2000)
    .map(s => {
      const attachments = safeAttachments(s.attachments);
      return {
        title: s.title, path: s.path, ...sourceFile(s),
        ...(attachments.length ? { attachments } : {}),
        ...(typeof s.section === 'string' ? { section: s.section.slice(0, 1000) } : {}),
        ...(typeof s.row === 'number' && Number.isSafeInteger(s.row) && s.row > 0 ? { row: s.row } : {}),
      };
    });
}
export function sourceName(source: Source): string {
  return source.path.replaceAll('\\', '/').split('/').filter(Boolean).at(-1) || source.title;
}
export function formatSource(source: Source, index: number): { label: string; detail: string } {
  return { label: `[${index + 1}] ${sourceName(source)}`, detail: [source.path, source.section, source.row ? `행 ${source.row}` : ''].filter(Boolean).join(' · ') };
}
export function findSourceFile(sources: Source[], file: SourceFile): SourceFile | undefined {
  const matches = (candidate: SourceFile) => candidate.documentId === file.documentId && candidate.version === file.version;
  for (const source of sources) {
    const parent = sourceFile(source);
    if (parent && matches(parent)) return parent;
    const attachment = source.attachments?.find(matches);
    if (attachment) return attachment;
  }
  return undefined;
}

export function appendAttachments(parent: HTMLElement, files: Attachment[],
    activate: (file: Attachment, preview?: HTMLImageElement) => void, canPreview = true): void {
  for (const file of files) {
    const group = document.createElement('div'); group.className = 'source-attachment';
    if (file.image && canPreview) {
      const image = document.createElement('img');
      image.alt = file.description || file.filename;
      const preview = document.createElement('button');
      preview.type = 'button'; preview.textContent = '이미지 미리보기';
      preview.addEventListener('click', () => {
        if (image.hasAttribute('src')) image.classList.toggle('expanded');
        else activate(file, image);
      });
      image.addEventListener('click', () => image.classList.toggle('expanded'));
      group.append(preview, image);
    }
    const download = document.createElement('button');
    download.type = 'button'; download.textContent = `${file.filename} · 다운로드`;
    download.addEventListener('click', () => activate(file));
    group.append(download);
    if (file.description) {
      const description = document.createElement('span');
      description.textContent = file.description; group.append(description);
    }
    parent.append(group);
  }
}

export function renderSources(list: HTMLUListElement, sources: Source[], download: (file: SourceFile) => void,
    preview?: (file: Attachment, image: HTMLImageElement) => void): void {
  const attachments = new Set<string>();
  list.replaceChildren();
  sources.forEach((source, index) => {
    const item = document.createElement('li');
    const file = sourceFile(source);
    const title = file ? document.createElement('button') : document.createElement('strong');
    const detail = document.createElement('span');
    const formatted = formatSource(source, index);
    title.textContent = file ? `${formatted.label} · 다운로드` : formatted.label;
    if (file && title instanceof HTMLButtonElement) {
      title.type = 'button';
      title.title = '내 기기에 파일 사본 다운로드';
      title.addEventListener('click', () => download(file));
    }
    detail.textContent = formatted.detail;
    item.append(title, detail);
    const files = (source.attachments ?? []).filter(file => {
      if (attachments.has(file.documentId)) return false;
      attachments.add(file.documentId); return true;
    });
    appendAttachments(item, files, (attachment, image) => image ? preview?.(attachment, image) : download(attachment), !!preview);
    list.append(item);
  });
  list.hidden = sources.length === 0;
  const heading = document.getElementById('sources-heading');
  if (heading) heading.hidden = list.hidden;
}
export interface AnswerSnapshot { answer: string; sources: Source[]; partial?: boolean }
export function answerSnapshot(value: unknown): AnswerSnapshot {
  if (!value || typeof value !== 'object' || !('answer' in value) || typeof value.answer !== 'string'
    || !value.answer.trim() || value.answer.length > 500000) throw new Error('읽을 수 있는 완료 답변이 없어요.');
  return { answer: value.answer, sources: safeSources('sources' in value ? value.sources : []),
    ...('partial' in value && value.partial === true ? { partial: true } : {}) };
}
export function snapshotText(snapshot: AnswerSnapshot): string {
  return snapshot.answer + (snapshot.sources.length ? '\n\n검색 참고 자료\n' + snapshot.sources.map((s, i) => { const f = formatSource(s, i); return `${f.label}\n${f.detail}`; }).join('\n\n') : '');
}
