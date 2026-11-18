import { knowledgeRequest } from './knowledge';
import { documentPreview, previewPdf } from './documents';
import { readSourceImage } from './download';
import type { Attachment, SourceFile } from './sources';
import type { LoginSession } from './session';

export async function showFilePreview(content: HTMLElement, file: SourceFile & { filename?: string; title?: string; image?: boolean },
    session: LoginSession, signal: AbortSignal): Promise<void> {
  content.replaceChildren();
  if (file.image || /\.(png|jpe?g)$/i.test(file.filename || file.title || '')) {
    const image = document.createElement('img'); image.alt = file.filename || file.title || '첨부 이미지';
    const src = await readSourceImage({ ...file, filename: file.filename || file.title || 'image.png', description: '', image: true } as Attachment, session, undefined, signal);
    signal.throwIfAborted(); if (!content.isConnected) return;
    image.src = src; content.append(image); return;
  }
  const data = documentPreview(await knowledgeRequest<unknown>(session,
    `/api/knowledge/documents/${file.documentId}/preview?version=${file.version}`, signal));
  signal.throwIfAborted(); if (!content.isConnected) return;
  if (data.kind === 'text') {
    const text = document.createElement('pre'); text.textContent = data.text; content.append(text);
  } else if (data.kind === 'sheets') {
    const label = document.createElement('label'); label.textContent = '시트 '; const sheets = document.createElement('select');
    sheets.setAttribute('aria-label', '미리보기 시트'); const area = document.createElement('div'); area.className = 'document-sheet';
    for (const [index, sheet] of data.sheets.entries()) { const option = document.createElement('option'); option.value = String(index); option.textContent = sheet.name; sheets.append(option); }
    const render = () => {
      const table = document.createElement('table');
      for (const cells of data.sheets[Number(sheets.value)]?.rows ?? []) {
        const row = document.createElement('tr');
        for (const cell of cells) { const td = document.createElement('td'); td.textContent = cell; row.append(td); }
        table.append(row);
      }
      area.replaceChildren(table);
    };
    sheets.addEventListener('change', render); label.append(sheets); content.append(label, area); render();
  } else if (data.kind === 'pdf') {
    const blob = await previewPdf(session, file, signal);
    signal.throwIfAborted(); if (!content.isConnected) return;
    const url = URL.createObjectURL(blob);
    signal.addEventListener('abort', () => URL.revokeObjectURL(url), { once: true });
    const frame = document.createElement('iframe'); frame.title = `${file.filename || file.title || '첨부파일'} PDF 미리보기`; frame.referrerPolicy = 'no-referrer'; frame.src = url; content.append(frame);
  }
  if (data.message || data.kind === 'unavailable') {
    const help = document.createElement('p'); help.className = 'status'; help.textContent = data.message || '미리보기를 지원하지 않아요. 원본을 다운로드해 주세요.'; content.append(help);
  }
}
