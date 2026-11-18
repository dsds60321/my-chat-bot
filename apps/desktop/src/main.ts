import './style.css';
import { applyBranding } from './branding';
import petUrl from '../../../pet.png';
import dragPetUrl from '../../../assets/pet/pet-drag.png';
import thinkingPetUrl from '../../../assets/pet/pet-thinking.png';
import { PetMotion } from './pet-motion';
import { streamChat, type Source } from './chat';
import { LoginSession, AuthenticationError } from './session';
import { safeSources, renderSources as showSources, renderDownloadFiles, sourceFile, type SourceFile } from './sources';
import { openAnswer, closeAnswer, listenForSourceDownloads } from './popup';
import { downloadSource, previewSource, readSourceImage } from './download';
import { scrollingIndicator, scrollToEnd } from './scroll';
import { isDrag, mockAnswer, shouldSend, fitPreviewSize, fitToWorkArea, previewArea, resizeFromEdge, type Rect, type ResizeDirection } from './logic';
import { initializeWindow, native, resizeWindow, showAfterFailure, startDragging, startResizing, watchPetDrag } from './native';
import { proposalIntent } from './knowledge';
import { setupKnowledgePanel } from './knowledge-panel';
import { setupDocumentChoices } from './document-choices';
import { showFilePreview } from './file-preview';

const element = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const pet = element<HTMLButtonElement>('pet');
const panel = element<HTMLElement>('panel');
const prompt = element<HTMLTextAreaElement>('prompt');
const status = element<HTMLElement>('status');
const form = element<HTMLFormElement>('question-form');
let open = true;
let resizing = false;
let answer = '';
let pointer: { x: number; y: number; id: number } | null = null;
let dragged = false;
let dragActive = false;
let dragGeneration = 0;
let stopNativeDrag: (() => void) | null = null;
let composing = false;
let compositionEndedAt = 0;
let request: AbortController | null = null;
let lastQuestion = '';
let lastSelection: SourceFile | undefined;
let documentChoices: Source[] = [];
const session = new LoginSession();
let sources: Source[] = [];
let imagePreview = new AbortController();
let completed = false;
let loginRequest: AbortController | null = null;
const apiUser = element<HTMLInputElement>('api-user');
const apiPassword = element<HTMLInputElement>('api-password');
const petImage = element<HTMLImageElement>('pet-image');
const poseImages = { idle: petUrl, dragging: dragPetUrl, thinking: thinkingPetUrl };
for (const src of Object.values(poseImages)) { const image = new Image(); image.src = src; }
const motion = new PetMotion(pose => {
  pet.dataset.motion = pose.motion;
  if (petImage.getAttribute('src') !== poseImages[pose.motion]) petImage.src = poseImages[pose.motion];
  pet.style.setProperty('--pet-x', `${pose.x}px`);
  pet.style.setProperty('--pet-y', `${pose.y}px`);
  pet.style.setProperty('--pet-angle', `${pose.angle}deg`);
  pet.style.setProperty('--pet-pitch', `${pose.pitch}deg`);
  pet.style.setProperty('--pet-stretch', String(pose.stretch));
});
const knowledge = setupKnowledgePanel(session, showSession, announce, message => { logout(); announce(message); });
function cancelRequest(): void { request?.abort(); }
function busy(value: boolean): void {
  motion.setThinking(value);
  element<HTMLButtonElement>('send').disabled = value;
  prompt.disabled = value;
  element<HTMLButtonElement>('logout').disabled = value;
  for (const id of ['open-proposal', 'open-history', 'open-review']) element<HTMLButtonElement>(id).disabled = value;
  element<HTMLButtonElement>('read-answer').disabled = value || !answer.trim() || documentChoices.length > 0;
  element('document-choices').inert = value;
  element('cancel').hidden = !value;
  form.setAttribute('aria-busy', String(value));
}
const documentAnswers = setupDocumentChoices(element('document-choices'), {
  async load(question, file, controller, onEvent) {
    if (!session.active || session.local) throw new AuthenticationError('다시 로그인해 주세요.');
    request = controller; busy(true); announce('선택한 문서를 확인하고 있어요.');
    try {
      const account = session.auth();
      await streamChat(question, account.username, account.password, controller.signal, onEvent, file);
      announce('문서 제목을 눌러 닫거나 다른 문서를 펼쳐 확인하세요.');
    } catch (error) {
      if (error instanceof AuthenticationError) { logout(); announce(error.message); }
      throw error;
    } finally {
      if (request === controller) { request = null; busy(false); }
    }
  },
  download(file) { void saveSource(file); },
  preview(file, image) {
    void previewSource(file, session, image).catch(error => announce(error instanceof Error ? error.message : '이미지를 열지 못했어요.'));
  },
  read: openAnswer,
  previewFile,
});
function renderDocumentChoices(items: unknown): void {
  documentChoices = safeSources(items).filter(item => sourceFile(item));
  documentAnswers.show(lastQuestion, documentChoices);
  renderDownloadFiles(element('answer-files'), documentChoices, file => { void saveSource(file); }, previewFile);
  element('document-choices').inert = Boolean(request);
}
async function previewFile(file: import('./sources').Attachment, area: HTMLElement): Promise<void> {
  const signal = imagePreview.signal;
  await showFilePreview(area, file, session, signal).catch(error => {
    if (!signal.aborted) area.textContent = error instanceof Error ? error.message : '미리보기를 불러오지 못했어요.';
    throw error;
  });
}
function renderSources(items: unknown): void {
  imagePreview.abort();
  imagePreview = new AbortController();
  const signal = imagePreview.signal;
  sources = safeSources(items);
  renderDownloadFiles(element('answer-files'), sources.length ? sources : documentChoices, file => { void saveSource(file); }, previewFile);
  showSources(element<HTMLUListElement>('sources'), sources, file => { void saveSource(file); }, (file, image) => {
    if (request) { announce('답변이 끝난 뒤 이미지를 열어 주세요.'); return; }
    void previewSource(file, session, image, undefined, signal).catch(error => {
      if (!signal.aborted) announce(error instanceof Error ? error.message : '이미지를 열지 못했어요.');
    });
  }, false, false);
}
async function saveSource(source: SourceFile): Promise<string> {
  announce('파일 사본을 다운로드하고 있어요.');
  const message = await downloadSource(source, session).catch(error =>
    error instanceof Error && !(error instanceof TypeError) ? error.message : typeof error === 'string' ? error : '출처 파일을 다운로드하지 못했어요.');
  announce(message);
  return message;
}
listenForSourceDownloads(saveSource, async file => {
  if (request) throw new Error('답변이 끝난 뒤 이미지를 열어 주세요.');
  return readSourceImage(file, session);
});
function showSession(): void {
  element('login-form').hidden = session.active;
  element('chat-tools').hidden = !session.active || knowledge.visible;
  for (const id of ['conversation', 'question-form']) element(id).hidden = !session.active || knowledge.visible;
  for (const id of ['open-proposal', 'open-history']) element(id).hidden = session.local;
  element('open-review').hidden = session.local || session.user?.admin !== true;
  element('logout').textContent = session.local ? '계정 접속' : '로그아웃';
  element('panel-title').textContent = session.active ? '무엇을 도와드릴까요?' : '계정으로 접속해 주세요';
}
function focusInput(): void {
  if (knowledge.visible) knowledge.focus();
  else if (session.active) prompt.focus();
  else apiUser.focus();
}
function logout(): void {
  motion.setThinking(false);
  imagePreview.abort();
  loginRequest?.abort(); loginRequest = null;
  element('login-error').hidden = true;
  cancelRequest(); renderDocumentChoices([]); lastSelection = undefined; session.logout(); apiPassword.value = ''; answer = ''; sources = []; completed = false; lastQuestion = '';
  knowledge.reset();
  element('question').hidden = true; element('answer-block').hidden = true; element('welcome').hidden = false;
  element('answer').textContent = ''; renderSources([]); prompt.value = ''; element('count').textContent = '0/4000';
  element('retry').hidden = true; element<HTMLButtonElement>('read-answer').disabled = true;
  void closeAnswer().catch(() => announce('읽기 창을 닫지 못했어요. 직접 닫아 주세요.'));
  showSession(); if (open) focusInput();
}
element('logout').addEventListener('click', () => { logout(); announce('로그아웃했어요. 다른 계정으로 접속할 수 있어요.'); });
element('login-form').addEventListener('submit', async event => {
  event.preventDefault(); if (loginRequest) return;
  const active = new AbortController(); loginRequest = active;
  const timeout = setTimeout(() => active.abort(), 10000);
  element<HTMLButtonElement>('login').disabled = true;
  element('login-error').hidden = true;
  announce('서버에서 계정을 확인하고 있어요.');
  try {
    await session.login(apiUser.value.trim(), apiPassword.value, active.signal);
    apiPassword.value = '';
    showSession(); if (open) focusInput();
    announce(session.user?.projects.length ? '접속했어요. 질문과 권한이 있는 문서가 AI에 전달될 수 있어요.' : '접속했어요. 현재 계정에는 업무 문서 검색 권한이 없어요.');
  } catch (error) {
    const message = active.signal.aborted ? '서버 응답이 늦어요. 서버 실행 상태를 확인하고 다시 접속해 주세요.' : error instanceof TypeError ? '서버에 연결하지 못했어요. 서버가 실행 중인지 확인해 주세요.' : error instanceof Error ? error.message : '접속하지 못했어요.';
    element('login-error').textContent = message; element('login-error').hidden = false;
    if (open) apiPassword.focus();
    announce('접속하지 못했어요. 입력과 서버 상태를 확인해 주세요.');
  } finally {
    clearTimeout(timeout);
    loginRequest = null;
    element<HTMLButtonElement>('login').disabled = false;
  }
});
scrollingIndicator(element('conversation'));
scrollingIndicator(element('login-form'));
showSession();
panel.hidden = false; pet.setAttribute('aria-expanded', 'true'); document.body.dataset.state = 'question';
element('cancel').addEventListener('click', cancelRequest);
element('retry').addEventListener('click', () => { if (lastQuestion) void executeQuestion(lastQuestion, lastSelection); });

document.body.classList.toggle('preview', !native);

function announce(message: string): void { status.textContent = message; }

async function setOpen(value: boolean, focus = true): Promise<void> {
  if (resizing) return;
  endPetDrag();
  if (!value) cancelRequest();
  resizing = true;
  try {
    const saved = await resizeWindow(value);
    if (!saved) announce('창 위치를 저장하지 못했어요. 이번 세션에서는 계속 사용할 수 있어요.');
    open = value;
    panel.hidden = !open;
    fitPreviewToViewport();
    pet.setAttribute('aria-expanded', String(open));
    document.body.dataset.state = open ? (answer ? 'answer' : 'question') : 'idle';
    if (focus) { if (open) focusInput(); else pet.focus(); }
  } catch {
    announce('창 위치를 조정하지 못했어요. 트레이에서 다시 표시해 주세요.');
  } finally { resizing = false; }
}

function closeOnOutsideClick(): void {
  if (open && !resizing && !dragActive && !knowledge.selectingFile) void setOpen(false, false);
}
document.addEventListener('pointerdown', event => {
  const target = event.target;
  if (target instanceof Node && !panel.contains(target) && !pet.contains(target)) closeOnOutsideClick();
});
window.addEventListener('blur', () => {
  if (!native && !(document.activeElement instanceof HTMLIFrameElement && document.hasFocus())) closeOnOutsideClick();
});

function endPetDrag(): void {
  dragGeneration++;
  dragActive = false; pointer = null;
  const stop = stopNativeDrag; stopNativeDrag = null;
  stop?.(); motion.endDrag();
}
async function beginPetDrag(dx: number, dy: number): Promise<void> {
  dragActive = true; motion.beginDrag(dx, dy);
  if (!native) return;
  const generation = ++dragGeneration;
  try {
    const stop = await watchPetDrag((x, y) => {
      if (generation !== dragGeneration) return;
      if (dragActive) motion.move(x, y);
      else { dragActive = true; motion.beginDrag(x, y); }
    }, () => { if (generation === dragGeneration) endPetDrag(); }, () => {
      if (generation === dragGeneration) { dragActive = false; motion.endDrag(); }
    });
    if (generation !== dragGeneration) { stop(); return; }
    stopNativeDrag = stop;
    await startDragging();
  } catch {
    if (generation === dragGeneration) { endPetDrag(); announce('펫을 이동하지 못했어요. 다시 드래그해 주세요.'); }
  }
}
pet.addEventListener('pointerdown', (event) => {
  if (event.button !== 0) return;
  endPetDrag();
  pointer = { x: event.clientX, y: event.clientY, id: event.pointerId };
  dragged = false;
  pet.setPointerCapture(event.pointerId);
});
pet.addEventListener('pointermove', (event) => {
  if (!pointer || pointer.id !== event.pointerId) return;
  if (dragged && event.buttons === 0) { endPetDrag(); return; }
  const current = { x: event.clientX, y: event.clientY };
  if (!dragged && !isDrag(pointer, current)) return;
  const dx = current.x - pointer.x; const dy = current.y - pointer.y;
  pointer = { ...current, id: event.pointerId };
  if (!dragged) { dragged = true; void beginPetDrag(dx, dy); }
  else if (!native) motion.move(dx, dy);
});
pet.addEventListener('pointerup', event => {
  if (pointer?.id !== event.pointerId) return;
  const clicked = !dragged;
  endPetDrag();
  if (clicked) void setOpen(!open);
});
pet.addEventListener('pointercancel', () => { if (!native || !dragged) endPetDrag(); });
window.addEventListener('blur', () => { if (!native || !dragged) endPetDrag(); });
document.addEventListener('visibilitychange', () => { if (document.hidden) endPetDrag(); });
pet.addEventListener('click', (event) => { if (event.detail === 0) void setOpen(!open); });
element('close').addEventListener('click', () => { void setOpen(false); });
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && open && !event.isComposing && !composing) { event.preventDefault(); void setOpen(false); }
});
prompt.addEventListener('compositionstart', () => { composing = true; });
prompt.addEventListener('compositionend', () => { composing = false; compositionEndedAt = performance.now(); });
prompt.addEventListener('input', () => { element('count').textContent = `${prompt.value.length}/4000`; });
prompt.addEventListener('keydown', (event) => {
  if (shouldSend(event) && !composing && performance.now() - compositionEndedAt > 50) {
    event.preventDefault();
    form.requestSubmit();
  }
});
form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (!session.active || request || knowledge.visible) return;
  const selection = documentChoices.length ? /^(\d+)\s*(?:번)?$/.exec(prompt.value.trim()) : null;
  if (selection) {
    if (!documentAnswers.toggle(Number(selection[1]) - 1)) {
      announce('표시된 문서 번호를 선택해 주세요.');
      return;
    }
    prompt.value = ''; element('count').textContent = '0/4000';
    return;
  }
  const intent = !session.local && proposalIntent(prompt.value);
  if (intent) {
    knowledge.openProposal(intent);
    prompt.value = ''; element('count').textContent = '0/4000';
    return;
  }
  void executeQuestion(prompt.value.trim());
});
async function executeQuestion(question: string, selected?: SourceFile): Promise<void> {
  if (request || !session.active) return;
  if (!question || question.length > 4000) { announce('1~4000자 질문을 입력해 주세요.'); prompt.focus(); return; }
  lastQuestion = question;
  lastSelection = selected;
  renderDocumentChoices([]);
  const active = new AbortController();
  request = active;
  busy(true);
  element('retry').hidden = true;
  answer = ''; completed = false;
  element<HTMLButtonElement>('read-answer').disabled = true;
  element('question').textContent = question;
  element('question').hidden = false;
  element('answer').textContent = '';
  element('answer-block').hidden = false;
  element('welcome').hidden = true;
  renderSources([]);
  document.body.dataset.state = 'answer';
  announce('답변을 준비하고 있어요.');
  try {
    if (session.local) {
      answer = mockAnswer(question);
      element('answer-provider').textContent = '로컬 Mock 응답';
      element('answer').textContent = answer;
    } else {
      element('answer-provider').textContent = '서버 스트리밍';
      const credentials = session.auth();
      await streamChat(question, credentials.username, credentials.password, active.signal, event => {
        if (event.event === 'delta' && typeof event.data.text === 'string') {
          const conversation = element('conversation');
          const atBottom = conversation.scrollHeight - conversation.scrollTop - conversation.clientHeight < 40;
          answer += event.data.text;
          element('answer').textContent = answer;
          if (atBottom) scrollToEnd(conversation);
        }
        if (event.event === 'candidates') renderDocumentChoices(event.data.items);
        if (event.event === 'sources') renderSources(event.data.items);
        if (event.event === 'done') element('answer-provider').textContent = event.data.provider === 'selection' ? '문서 선택' : event.data.provider === 'mock' ? '서버 Mock 응답' : '서버 답변';
      }, selected);
    }
    completed = Boolean(answer.trim()) && documentChoices.length === 0;
    prompt.value = '';
    element('count').textContent = '0/4000';
    announce(documentChoices.length ? '문서 버튼을 누르거나 번호를 입력해 주세요.' : session.local ? '로컬 Mock 답변 완료 · 외부 전송 없음' : '서버 답변을 받았어요.');
  } catch (error) {
    if (error instanceof AuthenticationError) { logout(); announce(error.message); }
    else if (active.signal.aborted) announce('응답을 취소했어요.');
    else {
      announce(error instanceof TypeError ? '서버에 연결하지 못했어요. 서버 실행과 인증 설정을 확인해 주세요.' : error instanceof Error ? error.message : '답변을 받지 못했어요.');
      element('retry').hidden = false;
    }
  } finally {
    if (!completed && answer.trim() && !documentChoices.length) element('answer-provider').textContent = '부분 답변';
    request = null;
    busy(false);
    if (open) focusInput();
  }
}
element('copy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(answer); announce('답변을 복사했어요.'); }
  catch { announce('복사 권한이 없어요. 답변 텍스트를 선택해 직접 복사해 주세요.'); }
});

void applyBranding().then(() => initializeWindow(async () => { await resizeWindow(open); if (open) focusInput(); else pet.focus(); }, true, closeOnOutsideClick)).then(focusInput).catch(async () => {
  announce('창 상태 복원에 실패했어요. 위치를 확인해 주세요.');
  await showAfterFailure();
});

element('read-answer').addEventListener('click', async () => {
  if (!answer.trim() || request) return;
  try { await openAnswer({ answer, sources, partial: !completed }); announce('답변을 새창에 열었어요.'); }
  catch (error) { announce(error instanceof Error ? error.message : '읽기 창을 열지 못했어요.'); }
});
function previewRect(): Rect {
  const bounds = panel.getBoundingClientRect();
  return { x: bounds.left, y: bounds.top, width: bounds.width, height: bounds.height };
}
function applyPreviewRect({ x, y, width, height }: Rect): void {
  Object.assign(panel.style, { left: `${x}px`, top: `${y}px`, width: `${width}px`, height: `${height}px`, right: 'auto', bottom: 'auto' });
}
async function adjustSize(amount: number): Promise<void> {
  if (resizing) return;
  endPetDrag();
  resizing = true;
  try {
    if (native) await resizeWindow(true, amount);
    else {
      const current = previewRect();
      const { width, height } = fitPreviewSize(current.width + amount, current.height + amount, innerWidth, innerHeight);
      applyPreviewRect(fitToWorkArea(current, width, height, previewArea(innerWidth, innerHeight)));
    }
  } catch { announce('창 크기를 바꾸지 못했어요. 다시 시도해 주세요.'); }
  finally { resizing = false; }
}
element('larger').addEventListener('click', () => { void adjustSize(80); });
element('smaller').addEventListener('click', () => { void adjustSize(-80); });
function fitPreviewToViewport(): void {
  if (native || !open || !panel.style.left) return;
  const current = previewRect();
  applyPreviewRect(fitToWorkArea(current, current.width, current.height, previewArea(innerWidth, innerHeight)));
}
window.addEventListener('resize', fitPreviewToViewport);

element('resize-edges').addEventListener('pointerdown', event => {
  const edge = event.target as HTMLElement;
  const direction = edge.dataset.resize as ResizeDirection | undefined;
  if (!direction || event.button !== 0 || resizing) return;
  endPetDrag();
  event.preventDefault();
  const id = event.pointerId;
  edge.setPointerCapture(id);
  resizing = true;
  const start = previewRect();
  const origin = { x: event.screenX, y: event.screenY };
  const previewResize = (dx: number, dy: number) => applyPreviewRect(resizeFromEdge(start, direction, dx, dy, previewArea(innerWidth, innerHeight)));
  const apply = native ? startResizing(direction) : Promise.resolve(previewResize);
  let pending: { x: number; y: number } | null = null;
  let running = false;
  let ended = false;
  let draggedEdge = false;

  async function drain(): Promise<void> {
    if (running) return;
    running = true;
    try {
      const resize = await apply;
      while (pending) {
        const delta = pending;
        pending = null;
        await resize(delta.x, delta.y);
      }
    } catch {
      pending = null;
      announce('창 크기를 조절하지 못했어요. 위의 크기 버튼을 사용해 주세요.');
      finish();
    } finally {
      running = false;
      if (ended) resizing = false;
    }
  }
  function move(e: PointerEvent): void {
    if (e.pointerId !== id) return;
    draggedEdge = true;
    pending = { x: e.screenX - origin.x, y: e.screenY - origin.y };
    void drain();
  }
  function end(e: PointerEvent): void {
    if (e.pointerId !== id) return;
    if (e.type === 'pointerup' && (e.screenX !== origin.x || e.screenY !== origin.y || draggedEdge)) move(e);
    finish();
  }
  function finish(): void {
    ended = true;
    edge.removeEventListener('pointermove', move);
    edge.removeEventListener('pointerup', end);
    edge.removeEventListener('pointercancel', end);
    edge.removeEventListener('lostpointercapture', end);
    if (edge.hasPointerCapture(id)) edge.releasePointerCapture(id);
    if (!running) resizing = false;
  }
  edge.addEventListener('pointermove', move);
  edge.addEventListener('pointerup', end);
  edge.addEventListener('pointercancel', end);
  edge.addEventListener('lostpointercapture', end);
  void drain();
});
