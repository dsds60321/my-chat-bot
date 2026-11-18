import type { Source } from './chat.ts';

export type SourceFile = { documentId: string; version: string };
export function sourceFile(value: unknown): SourceFile | null {
  if (!value || typeof value !== 'object' || !('documentId' in value) || !('version' in value)) return null;
  const { documentId, version } = value;
  return typeof documentId === 'string' && typeof version === 'string'
    && /^[a-f0-9]{64}$/.test(documentId) && /^[a-f0-9]{64}$/.test(version) ? { documentId, version } : null;
}

export type Attachment = SourceFile & { filename: string; description: string; image: boolean; bytes?: number };
export function safeAttachments(items: unknown): Attachment[] {
  if (!Array.isArray(items)) return [];
  return items.slice(0, 3).flatMap(item => {
    const file = sourceFile(item);
    return file && typeof item.filename === 'string' && item.filename.length <= 180
      && typeof item.description === 'string' && item.description.length <= 500
      && typeof item.image === 'boolean' && (item.bytes === undefined || (Number.isSafeInteger(item.bytes) && item.bytes >= 0))
      ? [{ ...file, filename: item.filename, description: item.description, image: item.image, ...(item.bytes === undefined ? {} : { bytes: item.bytes }) }] : [];
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

export function fileSize(bytes: number | undefined): string {
  if (bytes === undefined || bytes <= 0) return '';
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function sourceDownloadFiles(sources: Source[]): Attachment[] {
  const files = sources.flatMap(source => {
    if (source.attachments?.length) return source.attachments;
    const file = sourceFile(source);
    return file ? [{ ...file, filename: sourceName(source), description: '', image: /\.(png|jpe?g)$/i.test(source.path) }] : [];
  });
  const seen = new Set<string>();
  return files.filter(file => {
    const key = `${file.documentId}:${file.version}`;
    if (seen.has(key)) return false;
    seen.add(key); return true;
  });
}

export function appendAttachments(parent: HTMLElement, files: Attachment[],
    activate: (file: Attachment, preview?: HTMLImageElement) => void, canPreview = true, automatic = false): void {
  for (const file of files) {
    const group = document.createElement('div'); group.className = 'source-attachment';
    const name = document.createElement('span'); name.className = 'attachment-name'; name.textContent = file.filename;
    name.title = [file.filename, file.description].filter(Boolean).join(' · ');
    const size = document.createElement('small'); size.className = 'attachment-size'; size.textContent = fileSize(file.bytes);
    const download = document.createElement('button'); download.type = 'button'; download.className = 'attachment-action';
    download.textContent = '다운로드'; download.setAttribute('aria-label', `${file.filename} 다운로드`);
    download.addEventListener('click', () => activate(file));
    group.append(name, size, download);
    if (file.image && canPreview) {
      const image = document.createElement('img'); image.alt = file.description || file.filename;
      const preview = document.createElement('button'); preview.type = 'button'; preview.className = 'attachment-action'; preview.textContent = '미리보기';
      preview.addEventListener('click', () => {
        if (image.hasAttribute('src')) image.classList.toggle('expanded'); else activate(file, image);
      });
      image.addEventListener('click', () => image.classList.toggle('expanded'));
      group.append(preview, image);
    }
    parent.append(group);
    if (file.image && canPreview && automatic) {
      const image = group.querySelector('img')!;
      queueMicrotask(() => { if (image.isConnected) activate(file, image); });
    }
  }
}

export function renderDownloadFiles(parent: HTMLElement, sources: Source[], download: (file: SourceFile) => void,
    preview?: (file: Attachment, area: HTMLElement) => void | Promise<void>): void {
  parent.replaceChildren();
  const files = sourceDownloadFiles(sources);
  parent.hidden = files.length === 0;
  for (const file of files) {
    const row = document.createElement('div'); row.className = 'download-file';
    appendAttachments(row, [file], selected => download(selected), false);
    if (preview) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'attachment-action'; button.textContent = '미리보기';
      button.setAttribute('aria-label', `${file.filename} 미리보기`);
      const area = document.createElement('div'); area.className = 'attachment-preview'; area.hidden = true;
      let loaded = false;
      button.addEventListener('click', () => {
        area.hidden = !area.hidden; button.setAttribute('aria-expanded', String(!area.hidden));
        if (!area.hidden && !loaded) { loaded = true; void Promise.resolve(preview(file, area)).catch(() => { loaded = false; }); }
      });
      row.children[0].append(button); row.append(area);
    }
    parent.append(row);
  }
}

export function renderSources(list: HTMLUListElement, sources: Source[], download: (file: SourceFile) => void,
    preview?: (file: Attachment, image: HTMLImageElement) => void, automatic = true, includeAttachments = true): void {
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
      const key = `${file.documentId}:${file.version}`;
      if (attachments.has(key)) return false;
      attachments.add(key); return true;
    });
    if (includeAttachments) appendAttachments(item, files, (attachment, image) => image ? preview?.(attachment, image) : download(attachment), !!preview, automatic);
    list.append(item);
  });
  list.hidden = sources.length === 0;
  const heading = list.id === 'sources' ? document.getElementById('sources-heading') : null;
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

export async function displaySourceImage(image: HTMLImageElement, data: unknown): Promise<void> {
  if (typeof data !== 'string' || data.length > 12 * 1024 * 1024
      || !/^data:image\/(?:png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$/.test(data)) {
    throw new Error('이미지 데이터를 읽지 못했어요.');
  }
  if (!image.isConnected) return;
  try {
    image.src = data;
    await image.decode();
  } catch {
    image.removeAttribute('src');
    throw new Error('이미지 미리보기를 표시하지 못했어요.');
  }
}
