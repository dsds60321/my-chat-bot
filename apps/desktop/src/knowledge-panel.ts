import { AuthenticationError, type LoginSession } from './session';
import { documentTargets, knowledgeRequest, proposalIntent, type DocumentTarget, type Proposal, type ProposalDraft } from './knowledge';
import { appendAttachments, safeAttachments, sourceName } from './sources';
import { downloadSource, previewSource } from './download';
import { scrollingIndicator } from './scroll';
import { trackFilePicker } from './file-picker';
import { folderCatalog, folderCreation, folderRows, type FolderCatalog } from './folders';

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
  const query = element<HTMLInputElement>('proposal-query');
  const project = element<HTMLInputElement>('proposal-project');
  const back = element<HTMLButtonElement>('close-knowledge');
  const instruction = element<HTMLTextAreaElement>('proposal-instruction');
  const before = element('proposal-before');
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
  let parentFolder = '';
  let creationTrigger: HTMLButtonElement | null = null;
  const expanded = new Set<string>();
  const files = element<HTMLInputElement>('proposal-files');
  const descriptions = element('proposal-file-descriptions');
  const filePicker = trackFilePicker(files, window);
  let view: View | null = null;
  let operation: AbortController | null = null;
  let target: DocumentTarget | null = null;
  let draft: ProposalDraft | null = null;
  scrollingIndicator(panel);

  function report(message: string): void { error.textContent = message; error.hidden = !message; }
  function updateSubmit(): void { submit.disabled = !draft || !consent.checked || !after.value.trim() || !draftTitle.value.trim(); }
  function clearDraft(): void {
    draft = null; before.textContent = ''; draftTitle.value = ''; after.value = ''; consent.checked = false;
    element('proposal-draft').hidden = true; updateSubmit();
  }
  function clearTargets(): void {
    target = null; element('proposal-options').replaceChildren(); element('proposal-targets').hidden = true;
    clearDraft();
  }
  function showKind(): void {
    const isNew = kind.value === 'new';
    element('proposal-target').hidden = isNew; folderPanel.hidden = !isNew;
    element('folder-breadcrumb').hidden = !isNew;
    clearDraft(); report('');
  }
  function setView(next: View | null): void {
    operation?.abort(); operation = null;
    form.inert = false; history.inert = false; folderPanel.inert = false; tabs.inert = false; back.disabled = false; panel.removeAttribute('aria-busy');
    view = next; panel.hidden = !next;
    form.hidden = next !== 'proposal'; history.hidden = next !== 'history' && next !== 'review' && next !== 'folders';
    folderPanel.hidden = next !== 'proposal' || kind.value !== 'new';
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
    files.value = ''; descriptions.replaceChildren();
    setView(null); folderCreate.hidden = true; form.reset(); clearTargets(); project.value = '';
    selectedFolder = ''; parentFolder = ''; folderSearch.value = ''; expanded.clear(); tree.replaceChildren();
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
  function searchTargets(): void {
    const value = query.value.trim();
    if (!value) { report('대상 문서 이름이나 검색어를 입력해 주세요.'); query.focus(); return; }
    void run('대상 문서를 찾고 있어요.', async signal => {
      clearTargets();
      const items = documentTargets(await knowledgeRequest<unknown>(session, `/api/search?q=${encodeURIComponent(value)}`, signal));
      const options = element('proposal-options');
      for (const item of items) {
        const label = document.createElement('label');
        const radio = document.createElement('input'); radio.type = 'radio'; radio.name = 'proposal-document';
        const name = document.createElement('span'); name.textContent = sourceName(item);
        const detail = document.createElement('small'); detail.textContent = `${item.path} · ${item.project}`;
        name.append(detail); label.append(radio, name); options.append(label);
        radio.addEventListener('change', () => { target = item; clearDraft(); report(''); });
      }
      element('proposal-targets').hidden = !items.length;
      announce(items.length ? `문서 ${items.length}개를 찾았어요. 수정할 문서를 선택해 주세요.` : '일치하는 문서가 없어요. 다른 검색어를 입력해 주세요.');
    });
  }
  function selectFolder(path: string): void {
    selectedFolder = path;
    project.value = path.split('/')[0] ?? '';
    element('folder-breadcrumb').textContent = path ? path.split('/').join(' / ') : '왼쪽에서 문서를 등록할 폴더를 선택해 주세요.';
    clearDraft(); report('');
  }
  function renderFolders(): void {
    tree.replaceChildren();
    const rows = folderRows(catalog, expanded, folderSearch.value);
    if (!rows.length) tree.append(text('p', folderSearch.value ? '일치하는 폴더가 없어요.' : '접근 가능한 폴더가 없어요.', 'status'));
    for (const row of rows) {
      const item = document.createElement('li');
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
        choose.title = row.path; choose.dataset.path = row.path; choose.setAttribute('aria-label', `${row.path}에 문서 등록`);
        if (row.path === selectedFolder) choose.setAttribute('aria-current', 'location');
        choose.append(icon, row.name);
        choose.addEventListener('click', () => {
          selectFolder(row.path); folderCreate.hidden = true; renderFolders();
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
    catalog = folderCatalog(await knowledgeRequest<unknown>(session, '/api/knowledge/folders', signal));
    selectFolder(catalog.folders.includes(selected) ? selected : catalog.folders[0] ?? '');
    for (const path of selectedFolder.split('/').slice(0, -1).map((_, index, parts) => parts.slice(0, index + 1).join('/'))) expanded.add(path);
    rootAdd.hidden = catalog.canCreateDirectly && !catalog.canCreateProject;
    renderFolders();
    announce(catalog.folders.length ? '' : '이 계정으로 작성할 수 있는 폴더가 없어요. 새 프로젝트 생성을 요청할 수 있어요.');
  }
  function refreshProjects(): void {
    clearDraft();
    void run('폴더 목록을 불러오고 있어요.', signal => loadFolders(signal));
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
    kind.value = intent?.kind === 'AMENDMENT' ? 'update' : 'new';
    instruction.value = intent?.instruction ?? '';
    query.value = intent?.query ?? '';
    showKind(); heading.focus();
    if (kind.value === 'new') refreshProjects();
    else if (query.value) searchTargets();
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
      item.append(text('h3', '제안 내용'), text('p', record.content, 'proposal-text'));
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
      } else if (next === 'history' && ['PENDING_APPROVAL', 'APPROVED'].includes(record.state)) {
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
  element('proposal-search').addEventListener('click', searchTargets);
  query.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); searchTargets(); }
  });
  query.addEventListener('input', clearTargets);
  kind.addEventListener('change', () => {
    showKind();
    if (kind.value === 'new') refreshProjects();
  });
  for (const input of [project, instruction]) input.addEventListener('input', clearDraft);
  for (const input of [after, draftTitle, consent]) input.addEventListener('input', updateSubmit);
  form.addEventListener('submit', event => {
    event.preventDefault();
    const isNew = kind.value === 'new';
    if (isNew ? !project.value : !target) { report(isNew ? '문서를 저장할 프로젝트를 선택해 주세요.' : '찾기에서 수정할 문서를 선택해 주세요.'); return; }
    const value = instruction.value.trim();
    if (!value) { report('바꾸거나 추가할 내용을 입력해 주세요.'); instruction.focus(); return; }
    const scope = isNew ? { project: project.value, folderPath: selectedFolder } : { project: target!.project, sourceDocumentId: target!.documentId, sourceVersion: target!.version };
    void run('검토할 초안을 만들고 있어요.', async signal => {
      clearDraft();
      const result = await knowledgeRequest<ProposalDraft>(session, `${proposalPath}/draft`, signal, { ...scope, instruction: value });
      if (!result || result.project !== scope.project || result.kind !== (isNew ? 'NEW' : 'AMENDMENT')
        || (isNew && result.folderPath !== scope.folderPath)
        || (result.sourceDocumentId ?? undefined) !== scope.sourceDocumentId || (result.sourceVersion ?? undefined) !== scope.sourceVersion
        || typeof result.title !== 'string' || typeof result.content !== 'string' || (!isNew && typeof result.before !== 'string')) {
        throw new Error('문서가 일치하는 초안을 받지 못했어요. 다시 시도해 주세요.');
      }
      draft = result;
      before.textContent = result.before ?? '';
      element('proposal-before-block').hidden = isNew;
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
    if (!draft || !consent.checked) return;
    const content = after.value.trim(); const label = draftTitle.value.trim();
    if (!content || content.length > 4000 || !label || label.length > 120) { report('제목은 1~120자, 내용은 1~4000자로 입력해 주세요.'); return; }
    const { project, sourceDocumentId, sourceVersion, folderPath } = draft;
    void run('제안을 제출하고 있어요.', async signal => {
      const proposal = { project, sourceDocumentId, sourceVersion, folderPath, title: label, content, consent: true };
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
  return { get selectingFile() { return filePicker.active; }, get visible() { return view !== null; }, focus: () => heading.focus(), openProposal, reset };
}
