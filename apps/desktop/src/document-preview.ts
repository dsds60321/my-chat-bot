import { knowledgeRequest } from './knowledge';
import { downloadSource } from './download';
import { browsableDocuments, documentVersions, type BrowsableDocument } from './documents';
import { AuthenticationError, type LoginSession } from './session';
import { fileSize } from './sources';
import { showFilePreview } from './file-preview';

const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
export function setupDocumentPreview(session: LoginSession, run: (message: string, work: (signal: AbortSignal) => Promise<void>, mutating?: boolean) => Promise<void>,
    restored: (file: BrowsableDocument, expandPreview?: boolean) => void, announce: (message: string) => void, expire: (message: string) => void) {
  const details = element<HTMLDetailsElement>('document-preview');
  const content = element('document-preview-content');
  const contentPreview = element<HTMLDetailsElement>('document-content-preview');
  const attachments = element('document-attachments');
  const status = element('document-preview-status');
  const versions = element('document-versions');
  const versionSelect = element<HTMLSelectElement>('document-version');
  const restoreConsent = element<HTMLInputElement>('document-restore-consent');
  const restore = element<HTMLButtonElement>('document-restore');
  const download = element<HTMLButtonElement>('document-download');
  let selected: BrowsableDocument | null = null;
  let reading: AbortController | null = null;


  function failure(error: unknown, signal: AbortSignal): void {
    if (signal.aborted) return;
    if (error instanceof AuthenticationError) { reset(); expire(error.message); }
    else status.textContent = error instanceof TypeError ? '서버에 연결하지 못했어요.' : error instanceof Error ? error.message : '미리보기를 불러오지 못했어요.';
  }
  function reset(): void {
    reading?.abort(); reading = null; selected = null;
    contentPreview.ontoggle = null; contentPreview.open = false; attachments.replaceChildren(); attachments.hidden = true;
    content.replaceChildren(); status.textContent = ''; details.hidden = true; details.open = false;
    versions.hidden = true; versionSelect.replaceChildren(); restoreConsent.checked = false; restore.disabled = true; download.disabled = true;
  }
  async function show(file: BrowsableDocument, expandPreview = true): Promise<void> {
    reset(); selected = file; details.hidden = false; details.open = true; download.disabled = false;
    element('document-preview-name').textContent = file.title;
    element('document-preview-path').textContent = `${file.type.toUpperCase()} · ${fileSize(file.bytes)}`;
    download.textContent = file.attachments?.length ? '설명 문서 다운로드' : '원본 다운로드';
    const active = new AbortController(); reading = active;
    if (file.attachments?.length) {
      attachments.hidden = false;
      for (const attached of file.attachments) {
        const row = document.createElement('div'); row.className = 'source-attachment';
        const name = document.createElement('span'); name.className = 'attachment-name'; name.textContent = attached.title; name.title = attached.title;
        const size = document.createElement('small'); size.className = 'attachment-size'; size.textContent = fileSize(attached.bytes);
        const save = document.createElement('button'); save.type = 'button'; save.className = 'attachment-action'; save.textContent = '다운로드';
        save.setAttribute('aria-label', `${attached.title} 다운로드`);
        save.addEventListener('click', () => { void run('첨부파일을 다운로드하고 있어요.', async () => { announce(await downloadSource(attached, session)); }); });
        const view = document.createElement('button'); view.type = 'button'; view.className = 'attachment-action'; view.textContent = '미리보기';
        view.addEventListener('click', () => restored(attached, true));
        row.append(name, size, save, view);
        if (attached.replaceable !== false) {
          const replace = document.createElement('button'); replace.type = 'button'; replace.className = 'attachment-action'; replace.textContent = '교체';
          replace.setAttribute('aria-label', `${attached.title} 교체`); replace.addEventListener('click', () => restored(attached, false)); row.append(replace);
        }
        attachments.append(row);
      }
    }
    let loaded = false;
    const load = async () => {
      if (loaded || active.signal.aborted) return;
      loaded = true; status.textContent = '미리보기를 불러오고 있어요.';
      try { await showFilePreview(content, file, session, active.signal); if (!active.signal.aborted) status.textContent = ''; }
      catch (error) { loaded = false; failure(error, active.signal); }
    };
    contentPreview.ontoggle = () => { if (contentPreview.open) void load(); };
    contentPreview.open = expandPreview;
    if (expandPreview) void load();
    if (session.user?.admin && file.replaceable !== false) {
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
