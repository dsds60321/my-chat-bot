import { AuthenticationError, type LoginSession } from './session';
import { knowledgeRequest, proposalIntent, type Proposal, type ProposalDraft } from './knowledge';
import { appendAttachments, safeAttachments } from './sources';
import { downloadSource, previewSource } from './download';
import { scrollingIndicator } from './scroll';
import { trackFilePicker } from './file-picker';
import { folderCatalog, folderCreation, documentTreeRows, type FolderCatalog } from './folders';
import { setupDocumentPreview } from './document-preview';
import { browsableDocuments, proposalScope, replacementFile, type BrowsableDocument } from './documents';

const proposalPath = '/api/knowledge/proposals';
const reviewPath = '/api/knowledge/admin/proposals';
const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
type View = 'proposal' | 'history' | 'review' | 'folders';
const titles: Record<View, string> = { proposal: '학습 자료 관리', history: '내 제안', review: '제안 검토', folders: '폴더 생성 요청' };
const states: Record<Proposal['state'], string> = { PENDING_APPROVAL: '승인 대기', APPROVED: '승인됨', REJECTED: '반려됨', WITHDRAWN: '철회됨' };

export function setupKnowledgePanel(session: LoginSession, changed: () => void, announce: (message: string) => void, expire: (message: string) => void) {
  const panel = element('knowledge-panel');
  const heading = element('knowledge-title');
  const error = element('knowledge-error');
  const form = element<HTMLFormElement>('proposal-form');
  const kind = element<HTMLSelectElement>('proposal-kind');
  const project = element<HTMLInputElement>('proposal-project');
  const back = element<HTMLButtonElement>('close-knowledge');
  const instruction = element<HTMLTextAreaElement>('proposal-instruction');
  const draftTitle = element<HTMLInputElement>('proposal-draft-title');
  const after = element<HTMLTextAreaElement>('proposal-after');
  const consent = element<HTMLInputElement>('proposal-consent');
  const submit = element<HTMLButtonElement>('proposal-submit');
  const history = element('knowledge-history');
  const tabs = element('knowledge-tabs');
  const folderPanel = element('folder-panel');
  const tree = element('folder-tree');
  const folderSearch = element<HTMLInputElement>('folder-search');
  const folderCreate = element('folder-create');
  const folderName = element<HTMLInputElement>('folder-name');
  const folderCreateSubmit = element<HTMLButtonElement>('folder-create-submit');
  const rootAdd = element<HTMLButtonElement>('folder-root-add');
  let catalog: FolderCatalog = { folders: [], requests: [], canCreateDirectly: false, canCreateProject: false };
  let selectedFolder = '';
  let documentFiles: BrowsableDocument[] = [];
  let parentFolder = '';
  let creationTrigger: HTMLButtonElement | null = null;
  const expanded = new Set<string>();
  const files = element<HTMLInputElement>('proposal-files');
  const descriptions = element('proposal-file-descriptions');
  const filePicker = trackFilePicker(files, window);
  const replacement = element<HTMLInputElement>('replacement-file');
  const replacementConsent = element<HTMLInputElement>('replacement-consent');
  const replacementSubmit = element<HTMLButtonElement>('replacement-submit');
  const replacementPicker = trackFilePicker(replacement, window);
  let view: View | null = null;
  let operation: AbortController | null = null;
  let target: BrowsableDocument | null = null;
  let draft: ProposalDraft | null = null;
  const preview = setupDocumentPreview(session, run, file => {
    documentFiles = documentFiles.map(prior => prior.documentId === file.documentId ? file : prior);
    chooseFile(file);
  }, announce, expire);
  scrollingIndicator(panel);

  function report(message: string): void { error.textContent = message; error.hidden = !message; }
  function updateSubmit(): void { submit.disabled = !draft || !consent.checked || !after.value.trim() || !draftTitle.value.trim(); }
  function updateReplacementSubmit(): void {
    replacementSubmit.disabled = !replacementConsent.checked || !!replacementFile(replacement.files?.[0], target);
    replacementSubmit.textContent = session.user?.admin ? '수정 파일로 바로 교체' : '교체 제안 제출';
  }
  function clearDraft(): void {
    draft = null; draftTitle.value = ''; after.value = ''; consent.checked = false;
    element('proposal-draft').hidden = true; updateSubmit();
  }
  function clearUploads(): void {
    files.value = ''; descriptions.replaceChildren(); replacement.value = ''; replacementConsent.checked = false;
    updateReplacementSubmit();
  }
  function clearTargets(): void {
    target = null; preview.reset(); clearUploads(); clearDraft(); updateTargetHelp();
  }
  function updateTargetHelp(): void {
    element('proposal-target-help').textContent = kind.value === 'new'
      ? '왼쪽에서 폴더를 선택한 후 새 문서를 작성해 주세요.'
      : target ? `대상 파일: ${target.path} · 원본을 다운로드해 직접 수정한 뒤 교체해 주세요.` : '왼쪽 목록에서 교체할 파일을 선택해 주세요. 원본을 다운로드해 직접 수정한 뒤 올려 주세요.';
  }
  function showKind(): void {
    const isReplacement = kind.value === 'replace';
    folderPanel.hidden = false;
    element('replacement-fields').hidden = !isReplacement;
    element('proposal-attachment-fields').hidden = isReplacement;
    element('proposal-preview').hidden = isReplacement;
    element('proposal-instruction-label').hidden = isReplacement;
    instruction.hidden = isReplacement; instruction.disabled = isReplacement; instruction.required = !isReplacement;
    if (kind.value === 'new') { target = null; preview.reset(); folderSearch.value = ''; }
    clearDraft(); clearUploads(); updateTargetHelp(); report(''); renderFolders();
  }
  function setView(next: View | null): void {
    operation?.abort(); operation = null;
    form.inert = false; history.inert = false; folderPanel.inert = false; tabs.inert = false; back.disabled = false; panel.removeAttribute('aria-busy');
    view = next; panel.hidden = !next;
    form.hidden = next !== 'proposal'; history.hidden = next !== 'history' && next !== 'review' && next !== 'folders';
    folderPanel.hidden = next !== 'proposal';
    const tabViews: Record<string, View> = { 'knowledge-new': 'proposal', 'knowledge-own': 'history', 'knowledge-folders': 'folders', 'knowledge-review': 'review' };
    for (const [id, tab] of Object.entries(tabViews)) {
      const button = element(id);
      if (tab === next) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    }
    element('knowledge-review').hidden = session.user?.admin !== true;
    history.replaceChildren(); report('');
    if (next) { heading.textContent = titles[next]; panel.scrollTop = 0; }
    changed();
  }
  function reset(): void {
    preview.reset(); replacement.value = ''; replacementConsent.checked = false;
    files.value = ''; descriptions.replaceChildren();
    setView(null); folderCreate.hidden = true; form.reset(); clearTargets(); project.value = '';
    documentFiles = []; selectedFolder = ''; parentFolder = ''; folderSearch.value = ''; expanded.clear(); tree.replaceChildren();
    catalog = { folders: [], requests: [], canCreateDirectly: false, canCreateProject: false };
  }
  async function run(message: string, work: (signal: AbortSignal) => Promise<void>, mutating = false): Promise<void> {
    if (operation || !view) return;
    const active = new AbortController();
    operation = active; report(''); announce(message);
    const previousFocus = document.activeElement;
    form.inert = true; history.inert = true; folderPanel.inert = true; tabs.inert = mutating; back.disabled = mutating; panel.setAttribute('aria-busy', 'true');
    try { await work(active.signal); }
    catch (failure) {
      if (!active.signal.aborted) {
        if (failure instanceof AuthenticationError) expire(failure.message);
        else {
          announce('');
          report(failure instanceof TypeError ? '서버에 연결하지 못했어요.' : failure instanceof Error ? failure.message : '요청을 처리하지 못했어요.');
        }
      }
    } finally {
      if (operation === active) {
        operation = null; form.inert = false; history.inert = false; folderPanel.inert = false; tabs.inert = false; back.disabled = false; panel.removeAttribute('aria-busy');
        if (document.activeElement === document.body && previousFocus instanceof HTMLElement) previousFocus.focus();
      }
    }
  }
  function setFolderScope(path: string): void {
    selectedFolder = path;
    project.value = path.split('/')[0] ?? '';
    element('folder-breadcrumb').textContent = path ? path.split('/').join(' / ') : '왼쪽에서 문서를 등록할 폴더를 선택해 주세요.';
  }
  function selectFolder(path: string): void {
    if (path !== selectedFolder || target) {
      if (target && kind.value !== 'new') instruction.value = '';
      clearTargets();
    }
    setFolderScope(path); report('');
  }
  function chooseFile(file: BrowsableDocument): void {
    const changed = target?.documentId !== file.documentId || target?.version !== file.version;
    if (changed) {
      if (target && kind.value !== 'new') instruction.value = '';
      clearDraft(); clearUploads();
    }
    target = file; setFolderScope(file.path.substring(0, file.path.lastIndexOf('/')));
    const parts = selectedFolder.split('/');
    for (let i = 1; i <= parts.length; i++) expanded.add(parts.slice(0, i).join('/'));
    replacement.accept = '.' + file.type;
    updateReplacementSubmit(); updateTargetHelp(); report('');
    folderCreate.hidden = true; renderFolders();
    tree.querySelector<HTMLButtonElement>(`[data-document="${file.documentId}"]`)?.focus();
    if (changed) void preview.show(file);
    else element<HTMLDetailsElement>('document-preview').open = true;
  }
  function renderFolders(): void {
    tree.replaceChildren();
    const isNew = kind.value === 'new';
    const label = isNew ? '폴더' : '폴더 및 파일';
    element('folder-heading').textContent = isNew ? '프로젝트 및 폴더' : '프로젝트 및 파일';
    folderSearch.placeholder = `${label} 검색`;
    folderPanel.querySelector('label[for="folder-search"]')!.textContent = `${label} 검색`;
    tree.setAttribute('aria-label', isNew ? '접근 가능한 프로젝트 및 폴더' : '접근 가능한 프로젝트, 폴더 및 파일');
    folderPanel.querySelector('.folder-note')!.textContent = isNew
      ? '문서를 추가할 폴더를 선택해 주세요.' : '접근 가능한 폴더와 파일만 표시됩니다. 파일을 선택하면 오른쪽에서 미리 볼 수 있어요.';
    const rows = documentTreeRows(catalog, isNew ? [] : documentFiles, expanded, folderSearch.value);
    if (!rows.length) tree.append(text('p', folderSearch.value ? `${label} 검색 결과가 없어요.` : `접근 가능한 ${label} 목록이 비어 있어요.`, 'status'));
    for (const row of rows) {
      const item = document.createElement('li');
      if (row.kind === 'file') {
        const file = documentFiles.find(file => file.documentId === row.documentId)!;
        const button = document.createElement('button'); button.type = 'button'; button.className = 'document-file';
        button.style.setProperty('--folder-depth', String(row.depth)); button.textContent = row.name; button.title = row.path;
        button.dataset.document = file.documentId;
        const info = document.createElement('small'); info.textContent = `${file.type.toUpperCase()} · ${new Date(file.modified).toLocaleDateString('ko-KR')}`; button.append(info);
        if (target?.documentId === file.documentId) button.setAttribute('aria-current', 'true');
        button.addEventListener('click', () => chooseFile(file)); item.append(button); tree.append(item); continue;
      }
      const line = document.createElement('div'); line.className = 'folder-row';
      line.style.setProperty('--folder-depth', String(row.depth));
      if (row.path === selectedFolder) line.classList.add('is-selected');
      const toggle = document.createElement('button'); toggle.type = 'button'; toggle.className = 'folder-toggle';
      toggle.textContent = row.children ? expanded.has(row.path) || folderSearch.value ? '⌄' : '›' : '';
      toggle.setAttribute('aria-label', `${row.path} 하위 폴더 ${expanded.has(row.path) ? '접기' : '펼치기'}`);
      toggle.disabled = !row.children; toggle.tabIndex = row.children ? 0 : -1;
      if (row.children) toggle.setAttribute('aria-expanded', String(expanded.has(row.path) || !!folderSearch.value));
      toggle.addEventListener('click', () => {
        if (expanded.has(row.path)) expanded.delete(row.path); else expanded.add(row.path);
        renderFolders();
        Array.from(tree.querySelectorAll<HTMLButtonElement>('.folder-toggle')).find(button => button.dataset.path === row.path)?.focus();
      });
      toggle.dataset.path = row.path;
      const icon = document.createElement('span'); icon.className = 'folder-icon'; icon.setAttribute('aria-hidden', 'true');
      line.append(toggle);
      if (row.pending) {
        const label = document.createElement('span'); label.className = 'folder-pending'; label.append(icon, row.name);
        const badge = document.createElement('span'); badge.className = 'folder-badge'; badge.textContent = '승인 대기'; label.append(badge); line.append(label);
      } else {
        const choose = document.createElement('button'); choose.type = 'button'; choose.className = 'folder-select';
        choose.title = row.path; choose.dataset.path = row.path; choose.setAttribute('aria-label', `${row.path} 폴더 선택`);
        if (row.path === selectedFolder) choose.setAttribute('aria-current', 'location');
        choose.append(icon, row.name);
        choose.addEventListener('click', () => {
          selectFolder(row.path); expanded.add(row.path); folderCreate.hidden = true; renderFolders();
          tree.querySelector<HTMLButtonElement>('[aria-current="location"]')?.focus();
        });
        const add = document.createElement('button'); add.type = 'button'; add.className = 'folder-add'; add.textContent = '+';
        add.setAttribute('aria-label', `${row.path} 하위 폴더 추가`); add.title = `${row.path} 하위 폴더 추가`;
        add.addEventListener('click', () => openFolderCreate(row.path, add));
        line.append(choose, add);
      }
      item.append(line); tree.append(item);
    }
  }
  async function loadFolders(signal: AbortSignal, selected = selectedFolder): Promise<void> {
    const [folders, documents] = await Promise.all([
      knowledgeRequest<unknown>(session, '/api/knowledge/folders', signal),
      knowledgeRequest<unknown>(session, '/api/knowledge/documents', signal),
    ]);
    catalog = folderCatalog(folders); documentFiles = browsableDocuments(documents);
    selectFolder(catalog.folders.includes(selected) ? selected : catalog.folders[0] ?? '');
    const selectedParts = selectedFolder.split('/');
    for (let i = 1; i <= selectedParts.length; i++) expanded.add(selectedParts.slice(0, i).join('/'));
    rootAdd.hidden = catalog.canCreateDirectly && !catalog.canCreateProject;
    renderFolders();
    announce(catalog.folders.length ? '' : '이 계정으로 작성할 수 있는 폴더가 없어요. 새 프로젝트 생성을 요청할 수 있어요.');
  }
  function closeFolderCreate(): void {
    folderCreate.hidden = true;
    if (creationTrigger?.isConnected) creationTrigger.focus(); else rootAdd.focus();
  }
  function updateFolderPreview(): void {
    element('folder-create-preview').textContent = [parentFolder, folderName.value.trim() || '새 폴더'].filter(Boolean).join(' / ');
  }
  function openFolderCreate(parent: string, trigger: HTMLButtonElement): void {
    if (operation) return;
    parentFolder = parent; creationTrigger = trigger;
    folderName.value = ''; folderCreate.hidden = false;
    element('folder-create-title').textContent = parent ? '하위 폴더 추가' : '새 프로젝트';
    element('folder-create-parent').textContent = `생성 위치: ${parent ? parent.split('/').join(' / ') : '프로젝트 최상위'}`;
    element('folder-create-help').textContent = catalog.canCreateDirectly ? '허용된 범위에 바로 생성합니다.' : '관리자 승인 후 폴더가 생성됩니다.';
    folderCreateSubmit.textContent = catalog.canCreateDirectly ? parent ? '폴더 생성' : '프로젝트 생성' : '생성 요청';
    folderName.placeholder = parent ? '예: 정산 가이드' : '예: operations-guide';
    updateFolderPreview(); folderName.focus();
  }
  function createFolder(): void {
    let request: ReturnType<typeof folderCreation>;
    try { request = folderCreation(parentFolder, folderName.value); }
    catch (failure) { report(failure instanceof Error ? failure.message : '폴더 이름을 확인해 주세요.'); folderName.focus(); return; }
    void run('폴더 생성 요청을 처리하고 있어요.', async signal => {
      const saved = await knowledgeRequest<{ path: string; state: string }>(session, '/api/knowledge/folders', signal, { parentPath: request.parentPath, name: request.name });
      if (!saved || saved.path !== request.path || !['APPROVED', 'PENDING_APPROVAL'].includes(saved.state)) throw new Error('폴더 생성 결과를 확인하지 못했어요. 목록을 다시 불러와 주세요.');
      if (parentFolder) expanded.add(parentFolder);
      folderSearch.value = '';
      folderCreate.hidden = true;
      await loadFolders(signal, saved.state === 'APPROVED' ? saved.path : selectedFolder);
      announce(saved.state === 'APPROVED' ? `'${saved.path}' 폴더를 만들었어요.` : '생성 요청을 보냈어요. 관리자 승인 후 사용할 수 있어요.');
      requestAnimationFrame(() => { if (view === 'proposal') tree.querySelector<HTMLButtonElement>('[aria-current="location"]')?.focus(); });
    }, true);
  }
  rootAdd.addEventListener('click', () => openFolderCreate('', rootAdd));
  element('folder-create-cancel').addEventListener('click', closeFolderCreate);
  folderCreateSubmit.addEventListener('click', createFolder);
  folderName.addEventListener('input', updateFolderPreview);
  folderSearch.addEventListener('input', renderFolders);
  folderCreate.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeFolderCreate(); }
    else if (event.key === 'Enter' && event.target === folderName && !event.isComposing) { event.preventDefault(); createFolder(); }
  });
  files.addEventListener('change', () => {
    descriptions.replaceChildren();
    report('');
    const selected = Array.from(files.files ?? []);
    if (selected.length > 3 || selected.some(file => file.size === 0 || file.size > 8 * 1024 * 1024)) {
      files.value = '';
      report('파일은 최대 3개, 파일당 8MB까지 올릴 수 있어요.');
      return;
    }
    for (const file of selected) {
      const label = document.createElement('label');
      label.textContent = `${file.name} 설명`;
      const input = document.createElement('input');
      input.maxLength = 500;
      input.setAttribute('aria-required', String(/\.(png|jpe?g)$/i.test(file.name)));
      label.append(input); descriptions.append(label);
    }
  });
  function openProposal(intent?: NonNullable<ReturnType<typeof proposalIntent>>): void {
    if (!session.active || session.local) return;
    reset(); setView('proposal');
    kind.value = intent?.kind === 'REPLACEMENT' ? 'replace' : 'new';
    instruction.value = intent?.kind === 'NEW' ? intent.instruction : '';
    folderSearch.value = intent?.query ?? '';
    showKind(); heading.focus();
    void run('폴더 목록을 불러오고 있어요.', async signal => {
      await loadFolders(signal);
      const matches = tree.querySelectorAll<HTMLButtonElement>('.document-file');
      if (intent?.kind === 'REPLACEMENT' && intent.query && matches.length === 1) matches[0].click();
    });
  }
  function text(tag: 'p' | 'h3' | 'summary', value: string, className?: string): HTMLElement {
    const node = document.createElement(tag); node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  async function loadHistory(next: 'history' | 'review', signal: AbortSignal): Promise<void> {
    const records = await knowledgeRequest<Proposal[]>(session, next === 'review' ? reviewPath : proposalPath, signal);
    if (!Array.isArray(records)) throw new Error('제안 목록을 읽지 못했어요.');
    history.replaceChildren();
    if (!records.length) history.append(text('p', next === 'review' ? '검토할 제안이 없어요.' : '아직 제출한 제안이 없어요.'));
    for (const record of records) {
      const item = document.createElement('details'); item.className = 'proposal-record';
      const state = states[record.state] ?? '상태 확인 필요';
      item.append(text('summary', `${record.title} · ${state}`));
      item.append(text('p', [record.folderPath ?? record.project, record.sourceTitle, next === 'review' ? record.author : '', record.createdAt?.slice(0, 10)].filter(Boolean).join(' · '), 'status'));
      if (next === 'review' && record.sourceVersion) item.append(text('p', `원본 버전 ${record.sourceVersion.slice(0, 12)}`, 'status'));
      item.append(text('h3', record.kind === 'REPLACEMENT' ? '파일 교체 · 변경 내용' : '제안 내용'), text('p', record.content, 'proposal-text'));
      appendAttachments(item, safeAttachments(record.attachments), (file, preview) => {
        void run('첨부파일을 불러오고 있어요.', async signal => {
          const endpoint = `${proposalPath}/${encodeURIComponent(record.id)}/attachments/${file.documentId}`;
          if (preview) {
            await previewSource(file, session, preview, endpoint, signal);
            announce('');
          } else announce(await downloadSource(file, session, endpoint));
        });
      });
      if (next === 'review' && record.state === 'PENDING_APPROVAL') {
        if (record.author === session.user?.username) item.append(text('p', '본인이 제출한 제안은 다른 관리자가 검토합니다.', 'status'));
        else {
          appendAction(item, record, next, 'approve', '승인');
          appendAction(item, record, next, 'reject', '반려');
        }
      } else if (next === 'history' && (record.state === 'PENDING_APPROVAL' || (record.state === 'APPROVED' && record.kind !== 'REPLACEMENT'))) {
        appendAction(item, record, next, 'withdraw', '철회');
      }
      history.append(item);
    }
  }
  function appendAction(item: HTMLElement, record: Proposal, next: 'history' | 'review', action: 'approve' | 'reject' | 'withdraw', label: string): void {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
    button.addEventListener('click', () => {
      void run(`${label}하고 있어요.`, async signal => {
        const endpoint = `${next === 'review' ? reviewPath : proposalPath}/${encodeURIComponent(record.id)}/${action}`;
        await knowledgeRequest(session, endpoint, signal, {});
        await loadHistory(next, signal);
        announce(`제안을 ${label}했어요.`); heading.focus();
      }, true);
    });
    item.append(button);
  }
  async function loadFolderRequests(signal: AbortSignal): Promise<void> {
    catalog = folderCatalog(await knowledgeRequest<unknown>(session, '/api/knowledge/folders', signal));
    history.replaceChildren();
    history.append(text('p', session.user?.admin ? '내 생성 요청과 허용 범위의 승인 대기 요청입니다.' : '요청한 프로젝트와 폴더의 처리 상태를 확인하세요.', 'status'));
    if (!catalog.requests.length) history.append(text('p', '폴더 생성 요청이 없어요.'));
    for (const record of catalog.requests) {
      const item = document.createElement('article'); item.className = 'folder-request';
      item.append(text('h3', record.path.split('/').join(' / ')), text('p', `${states[record.state]} · ${record.author} · ${record.createdAt.slice(0, 10)}`, 'status'));
      if (session.user?.admin && record.state === 'PENDING_APPROVAL') {
        for (const [action, label] of [['approve', '승인'], ['reject', '반려']]) {
          const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
          button.addEventListener('click', () => {
            void run(`폴더 요청을 ${label}하고 있어요.`, async activeSignal => {
              await knowledgeRequest(session, `/api/knowledge/admin/folders/${encodeURIComponent(record.id)}/${action}`, activeSignal, {});
              await loadFolderRequests(activeSignal); announce(`폴더 요청을 ${label}했어요.`); heading.focus();
            }, true);
          });
          item.append(button);
        }
      }
      if (record.state === 'APPROVED' && !catalog.folders.includes(record.path)) item.append(text('p', '이 프로젝트를 사용하려면 관리자의 계정 접근 권한 설정이 필요해요.', 'status'));
      history.append(item);
    }
  }
  function openFolderRequests(): void {
    if (!session.active || session.local) return;
    reset(); setView('folders'); heading.focus();
    void run('폴더 생성 요청을 불러오고 있어요.', async signal => { await loadFolderRequests(signal); announce(''); });
  }
  function openList(next: 'history' | 'review'): void {
    if (!session.active || session.local || (next === 'review' && session.user?.admin !== true)) return;
    reset(); setView(next); heading.focus();
    void run('제안을 불러오고 있어요.', async signal => { await loadHistory(next, signal); announce(''); });
  }

  element('knowledge-new').addEventListener('click', () => openProposal());
  element('knowledge-own').addEventListener('click', () => openList('history'));
  element('knowledge-folders').addEventListener('click', openFolderRequests);
  element('knowledge-review').addEventListener('click', () => openList('review'));
  element('open-proposal').addEventListener('click', () => openProposal());
  element('open-history').addEventListener('click', () => openList('history'));
  element('open-review').addEventListener('click', () => openList('review'));
  back.addEventListener('click', () => { reset(); announce(''); element('prompt').focus(); });
  kind.addEventListener('change', showKind);
  for (const input of [project, instruction]) input.addEventListener('input', () => { clearDraft(); updateReplacementSubmit(); });
  for (const input of [after, draftTitle, consent]) input.addEventListener('input', updateSubmit);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (kind.value === 'replace') { submitReplacement(); return; }
    if (kind.value !== 'new') return;
    if (!project.value) { report('문서를 저장할 프로젝트를 선택해 주세요.'); return; }
    const value = instruction.value.trim();
    if (!value) { report('새 문서에 담을 내용을 입력해 주세요.'); instruction.focus(); return; }
    const scope = proposalScope('new', selectedFolder, null);
    void run('검토할 초안을 만들고 있어요.', async signal => {
      clearDraft();
      const result = await knowledgeRequest<ProposalDraft>(session, `${proposalPath}/draft`, signal, { ...scope, instruction: value });
      if (!result || result.project !== scope.project || result.kind !== 'NEW'
        || result.folderPath !== scope.folderPath
        || (result.sourceDocumentId ?? undefined) !== scope.sourceDocumentId || (result.sourceVersion ?? undefined) !== scope.sourceVersion
        || typeof result.title !== 'string' || typeof result.content !== 'string') {
        throw new Error('문서가 일치하는 초안을 받지 못했어요. 다시 시도해 주세요.');
      }
      draft = result;
      draftTitle.value = result.title;
      after.value = result.content;
      element('proposal-draft').hidden = false; updateSubmit();
      announce('초안을 확인하고 필요하면 편집해 주세요.');
      requestAnimationFrame(() => {
        if (draft !== result || !panel.getClientRects().length) return;
        draftTitle.focus({ preventScroll: true });
        element('proposal-draft').scrollIntoView({ block: 'start' });
      });
    });
  });
  submit.addEventListener('click', () => {
    if (kind.value !== 'new' || draft?.kind !== 'NEW' || !consent.checked) return;
    const content = after.value.trim(); const label = draftTitle.value.trim();
    if (!content || content.length > 4000 || !label || label.length > 120) { report('제목은 1~120자, 내용은 1~4000자로 입력해 주세요.'); return; }
    const { project, folderPath } = draft;
    void run('제안을 제출하고 있어요.', async signal => {
      const proposal = { project, folderPath, title: label, content, consent: true };
      const selected = Array.from(files.files ?? []);
      let body: object = proposal;
      if (selected.length) {
        const inputs = Array.from(descriptions.querySelectorAll('input'));
        if (inputs.some(input => input.getAttribute('aria-required') === 'true' && !input.value.trim())) {
          throw new Error('이미지마다 검색에 사용할 설명을 입력해 주세요.');
        }
        const multipart = new FormData();
        multipart.append('metadata', JSON.stringify({ proposal, descriptions: inputs.map(input => input.value.trim()) }));
        for (const file of selected) multipart.append('files', file);
        body = multipart;
      }
      const saved = await knowledgeRequest<Proposal>(session, proposalPath, signal, body);
      openList('history'); announce(saved.state === 'APPROVED' ? '제안을 검색에 반영했어요.' : '승인 요청을 보냈어요. 내 제안에서 확인할 수 있어요.');
    }, true);
  });
  replacement.addEventListener('change', () => {
    report('');
    const message = replacementFile(replacement.files?.[0], target);
    if (message && replacement.files?.length) { replacement.value = ''; report(message); }
    updateReplacementSubmit();
  });
  replacementConsent.addEventListener('change', updateReplacementSubmit);
  function submitReplacement(): void {
    const file = replacement.files?.[0];
    const message = replacementFile(file, target);
    if (message || !file || !target) { report(message || '교체할 파일을 확인해 주세요.'); return; }
    if (!replacementConsent.checked) { report('파일 교체와 공유에 동의해 주세요.'); replacementConsent.focus(); return; }
    const selected = target;
    void run('파일 교체 제안을 제출하고 있어요.', async signal => {
      const body = new FormData();
      body.append('metadata', JSON.stringify({ ...proposalScope('replace', selectedFolder, selected), title: selected.title.slice(0, 120), consent: true }));
      body.append('file', file);
      const saved = await knowledgeRequest<Proposal>(session, '/api/knowledge/replacements', signal, body);
      if (!saved || saved.kind !== 'REPLACEMENT') throw new Error('교체 결과를 확인하지 못했어요. 내 제안을 확인해 주세요.');
      openList('history'); announce(saved.state === 'APPROVED' ? '수정 파일로 교체하고 이전 버전을 보관했어요.' : '교체 승인 요청을 보냈어요. 승인 전 원본은 유지됩니다.');
    }, true);
  }
  replacementSubmit.addEventListener('click', submitReplacement);
  return { get selectingFile() { return filePicker.active || replacementPicker.active; }, get visible() { return view !== null; }, focus: () => heading.focus(), openProposal, reset };
}
