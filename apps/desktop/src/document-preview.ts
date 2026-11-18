import { knowledgeRequest } from './knowledge';
import { downloadSource } from './download';
import { browsableDocuments, documentPreview, documentVersions, previewPdf, type BrowsableDocument } from './documents';
import { AuthenticationError, type LoginSession } from './session';

const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
export function setupDocumentPreview(session: LoginSession, run: (message: string, work: (signal: AbortSignal) => Promise<void>, mutating?: boolean) => Promise<void>,
    restored: (file: BrowsableDocument) => void, announce: (message: string) => void, expire: (message: string) => void) {
  const details = element<HTMLDetailsElement>('document-preview');
  const content = element('document-preview-content');
  const status = element('document-preview-status');
  const versions = element('document-versions');
  const versionSelect = element<HTMLSelectElement>('document-version');
  const restoreConsent = element<HTMLInputElement>('document-restore-consent');
  const restore = element<HTMLButtonElement>('document-restore');
  const download = element<HTMLButtonElement>('document-download');
  let selected: BrowsableDocument | null = null;
  let reading: AbortController | null = null;
  let pdfUrl: string | null = null;

  function failure(error: unknown, signal: AbortSignal): void {
    if (signal.aborted) return;
    if (error instanceof AuthenticationError) { reset(); expire(error.message); }
    else status.textContent = error instanceof TypeError ? '서버에 연결하지 못했어요.' : error instanceof Error ? error.message : '미리보기를 불러오지 못했어요.';
  }
  function reset(): void {
    reading?.abort(); reading = null; selected = null;
    content.replaceChildren(); status.textContent = ''; details.hidden = true; details.open = false;
    if (pdfUrl) URL.revokeObjectURL(pdfUrl);
    pdfUrl = null; versions.hidden = true; versionSelect.replaceChildren(); restoreConsent.checked = false; restore.disabled = true; download.disabled = true;
  }
  async function show(file: BrowsableDocument): Promise<void> {
    reset(); selected = file; details.hidden = false; details.open = true; download.disabled = false;
    element('document-preview-name').textContent = file.path.split('/').at(-1) || file.title;
    element('document-preview-path').textContent = file.path;
    const active = new AbortController(); reading = active; status.textContent = '미리보기를 불러오고 있어요.';
    const name = document.createElement('h3'); name.textContent = file.title; content.append(name);
    try {
      const data = documentPreview(await knowledgeRequest<unknown>(session, `/api/knowledge/documents/${file.documentId}/preview?version=${file.version}`, active.signal));
      if (data.kind === 'text') {
        const text = document.createElement('pre'); text.textContent = data.text; content.append(text);
      } else if (data.kind === 'sheets') {
        const label = document.createElement('label'); label.textContent = '시트 '; const sheets = document.createElement('select');
        sheets.setAttribute('aria-label', '미리보기 시트'); const area = document.createElement('div'); area.className = 'document-sheet';
        for (const [index, sheet] of data.sheets.entries()) { const option = document.createElement('option'); option.value = String(index); option.textContent = sheet.name; sheets.append(option); }
        const renderSheet = () => {
          const table = document.createElement('table');
          for (const cells of data.sheets[Number(sheets.value)]?.rows ?? []) {
            const row = document.createElement('tr');
            for (const cell of cells) { const td = document.createElement('td'); td.textContent = cell; row.append(td); }
            table.append(row);
          }
          area.replaceChildren(table);
        };
        sheets.addEventListener('change', renderSheet); label.append(sheets); content.append(label, area); renderSheet();
      } else if (data.kind === 'pdf') {
        const blob = await previewPdf(session, file, active.signal); pdfUrl = URL.createObjectURL(blob);
        const frame = document.createElement('iframe'); frame.title = `${file.title} PDF 미리보기`; frame.referrerPolicy = 'no-referrer'; frame.src = pdfUrl; content.append(frame);
        const help = document.createElement('p'); help.className = 'status'; help.textContent = '페이지가 표시되지 않으면 원본 다운로드로 확인해 주세요.'; content.append(help);
      }
      status.textContent = data.message || (data.kind === 'unavailable' ? '이 파일은 미리보기를 지원하지 않아요. 원본을 다운로드해 주세요.' : '');
    } catch (error) { failure(error, active.signal); }
    finally {
      if (!active.signal.aborted && session.user?.admin) {
        try {
          const saved = documentVersions(await knowledgeRequest<unknown>(session, `/api/knowledge/documents/${file.documentId}/versions?version=${file.version}`, active.signal));
          for (const version of saved) {
            if (version.version === file.version) continue;
            const option = document.createElement('option'); option.value = version.version;
            option.textContent = `${version.createdAt.slice(0, 19).replace('T', ' ')} · ${version.version.slice(0, 10)}`; versionSelect.append(option);
          }
          versions.hidden = !versionSelect.options.length;
        } catch (error) { failure(error, active.signal); }
      }
      if (reading === active) reading = null;
    }
  }
  download.addEventListener('click', () => {
    if (!selected) return;
    const file = selected;
    void run('원본 파일을 다운로드하고 있어요.', async signal => {
      const message = await downloadSource(file, session); signal.throwIfAborted(); announce(message);
    });
  });
  versionSelect.addEventListener('change', () => { restoreConsent.checked = false; restore.disabled = true; });
  restoreConsent.addEventListener('change', () => { restore.disabled = !restoreConsent.checked || !versionSelect.value; });
  restore.addEventListener('click', () => {
    if (!selected || !restoreConsent.checked || !versionSelect.value) return;
    const file = selected; const restoreVersion = versionSelect.value;
    void run('이전 버전으로 복원하고 있어요.', async signal => {
      const data = await knowledgeRequest<unknown>(session, `/api/knowledge/documents/${file.documentId}/restore`, signal,
        { version: file.version, restoreVersion, consent: true });
      const current = browsableDocuments([data])[0];
      restored(current); announce('이전 버전으로 복원하고 현재 파일도 보관했어요.');
    }, true);
  });
  return { show, reset };
}
