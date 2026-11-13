import { AuthenticationError, type LoginSession } from './session';
import { documentTargets, knowledgeRequest, proposalIntent, type DocumentTarget, type Proposal, type ProposalDraft } from './knowledge';
import { sourceName } from './sources';
import { scrollingIndicator } from './scroll';

const proposalPath = '/api/knowledge/proposals';
const reviewPath = '/api/knowledge/admin/proposals';
const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
type View = 'proposal' | 'history' | 'review';
const titles: Record<View, string> = { proposal: '문서 수정 제안', history: '내 제안', review: '제안 검토' };
const states: Record<Proposal['state'], string> = { PENDING_APPROVAL: '승인 대기', APPROVED: '승인됨', REJECTED: '반려됨', WITHDRAWN: '철회됨' };

export function setupKnowledgePanel(session: LoginSession, changed: () => void, announce: (message: string) => void, expire: (message: string) => void) {
  const panel = element('knowledge-panel');
  const heading = element('knowledge-title');
  const error = element('knowledge-error');
  const form = element<HTMLFormElement>('proposal-form');
  const kind = element<HTMLSelectElement>('proposal-kind');
  const query = element<HTMLInputElement>('proposal-query');
  const project = element<HTMLSelectElement>('proposal-project');
  const back = element<HTMLButtonElement>('close-knowledge');
  const instruction = element<HTMLTextAreaElement>('proposal-instruction');
  const before = element('proposal-before');
  const draftTitle = element<HTMLInputElement>('proposal-draft-title');
  const after = element<HTMLTextAreaElement>('proposal-after');
  const consent = element<HTMLInputElement>('proposal-consent');
  const submit = element<HTMLButtonElement>('proposal-submit');
  const history = element('knowledge-history');
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
    element('proposal-target').hidden = isNew; element('proposal-new').hidden = !isNew;
    clearDraft(); report('');
  }
  function setView(next: View | null): void {
    operation?.abort(); operation = null;
    form.inert = false; history.inert = false; back.disabled = false; panel.removeAttribute('aria-busy');
    view = next; panel.hidden = !next;
    form.hidden = next !== 'proposal'; history.hidden = next !== 'history' && next !== 'review';
    history.replaceChildren(); report('');
    if (next) { heading.textContent = titles[next]; panel.scrollTop = 0; }
    changed();
  }
  function reset(): void {
    setView(null); form.reset(); clearTargets(); project.replaceChildren();
  }
  async function run(message: string, work: (signal: AbortSignal) => Promise<void>, mutating = false): Promise<void> {
    if (operation || !view) return;
    const active = new AbortController();
    operation = active; report(''); announce(message);
    const previousFocus = document.activeElement;
    form.inert = true; history.inert = true; back.disabled = mutating; panel.setAttribute('aria-busy', 'true');
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
        operation = null; form.inert = false; history.inert = false; back.disabled = false; panel.removeAttribute('aria-busy');
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
  function openProposal(intent?: NonNullable<ReturnType<typeof proposalIntent>>): void {
    if (!session.active || session.local) return;
    reset(); setView('proposal');
    kind.value = intent?.kind === 'NEW' ? 'new' : 'update';
    instruction.value = intent?.instruction ?? '';
    query.value = intent?.query ?? '';
    for (const name of session.user?.projects ?? []) {
      const option = document.createElement('option'); option.value = name; option.textContent = name; project.append(option);
    }
    showKind(); heading.focus();
    if (query.value) searchTargets();
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
      item.append(text('p', [record.project, record.sourceTitle, next === 'review' ? record.author : '', record.createdAt?.slice(0, 10)].filter(Boolean).join(' · '), 'status'));
      if (next === 'review' && record.sourceVersion) item.append(text('p', `원본 버전 ${record.sourceVersion.slice(0, 12)}`, 'status'));
      item.append(text('h3', '제안 내용'), text('p', record.content, 'proposal-text'));
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
  function openList(next: 'history' | 'review'): void {
    if (!session.active || session.local || (next === 'review' && session.user?.admin !== true)) return;
    reset(); setView(next); heading.focus();
    void run('제안을 불러오고 있어요.', async signal => { await loadHistory(next, signal); announce(''); });
  }

  element('open-proposal').addEventListener('click', () => openProposal());
  element('open-history').addEventListener('click', () => openList('history'));
  element('open-review').addEventListener('click', () => openList('review'));
  back.addEventListener('click', () => { reset(); announce(''); element('prompt').focus(); });
  element('proposal-search').addEventListener('click', searchTargets);
  query.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); searchTargets(); }
  });
  query.addEventListener('input', clearTargets);
  kind.addEventListener('change', showKind);
  for (const input of [project, instruction]) input.addEventListener('input', clearDraft);
  for (const input of [after, draftTitle, consent]) input.addEventListener('input', updateSubmit);
  form.addEventListener('submit', event => {
    event.preventDefault();
    const isNew = kind.value === 'new';
    if (isNew ? !project.value : !target) { report(isNew ? '문서를 저장할 프로젝트를 선택해 주세요.' : '찾기에서 수정할 문서를 선택해 주세요.'); return; }
    const value = instruction.value.trim();
    if (!value) { report('바꾸거나 추가할 내용을 입력해 주세요.'); instruction.focus(); return; }
    const scope = isNew ? { project: project.value } : { project: target!.project, sourceDocumentId: target!.documentId, sourceVersion: target!.version };
    void run('검토할 초안을 만들고 있어요.', async signal => {
      clearDraft();
      const result = await knowledgeRequest<ProposalDraft>(session, `${proposalPath}/draft`, signal, { ...scope, instruction: value });
      if (!result || result.project !== scope.project || result.kind !== (isNew ? 'NEW' : 'AMENDMENT')
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
    const { project, sourceDocumentId, sourceVersion } = draft;
    void run('제안을 제출하고 있어요.', async signal => {
      const saved = await knowledgeRequest<Proposal>(session, proposalPath, signal, { project, sourceDocumentId, sourceVersion, title: label, content, consent: true });
      openList('history'); announce(saved.state === 'APPROVED' ? '제안을 검색에 반영했어요.' : '승인 요청을 보냈어요. 내 제안에서 확인할 수 있어요.');
    }, true);
  });
  return { get visible() { return view !== null; }, focus: () => heading.focus(), openProposal, reset };
}
