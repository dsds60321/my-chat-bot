import { authorization, AuthenticationError, type LoginSession } from './session.ts';
import { apiBaseUrl } from './api.ts';
import type { Attachment } from './sources.ts';

export type ProposalKind = 'NEW' | 'AMENDMENT' | 'REPLACEMENT';
export type ProposalState = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
export interface DocumentTarget { documentId: string; version: string; project: string; title: string; path: string }
interface ProposalScope { project: string; sourceDocumentId?: string; sourceVersion?: string; folderPath?: string }
export interface ProposalDraft extends ProposalScope {
  kind: ProposalKind; title: string; content: string; sourceTitle?: string; before?: string;
}
export interface Proposal extends ProposalDraft {
  id: string; author: string; state: ProposalState; createdAt: string; updatedAt: string; attachments?: Attachment[];
}

export function proposalIntent(question: string): { kind: 'NEW' | 'REPLACEMENT'; instruction: string; query: string } | null {
  const input = question.trim();
  const command = /^(\/수정|\/새문서)(?=\s|$)/.exec(input);
  const instruction = command ? input.slice(command[0].length).trim() : input;
  const filename = /[^\s"'<>]*\.(?:md|txt|pdf|docx|xlsx|pptx|csv|sql)\b/i.exec(instruction)?.[0] ?? '';
  if (command) return { kind: command[1] === '/수정' ? 'REPLACEMENT' : 'NEW', instruction, query: filename };
  const create = /(?:작성(?:\s*해)?|만들어|써)\s*(?:줘요?|주세요|주십시오|부탁해요)[.!。！]?$/;
  if (create.test(input) && /(?:새\s*문서|신규\s*문서|새로운\s*문서)/.test(input)) {
    return { kind: 'NEW', instruction, query: '' };
  }
  const subject = /^(.*?(?:절차|지침|정책|업무\s*사항|업무\s*항목|문서|매뉴얼))(?:에서|에|의|을|를|\s)/.exec(input)?.[1] ?? '';
  const edit = /(?:수정|개정|변경|갱신|추가|삭제|반영|보완)(?:\s*해)?\s*(?:줘요?|주세요|주십시오|부탁해요)[.!。！]?$|(?:고쳐|바꿔)\s*(?:줘요?|주세요|주십시오)[.!。！]?$/;
  if (edit.test(input) && (filename || subject)) {
    return { kind: 'REPLACEMENT', instruction, query: filename || (subject === '문서' ? '' : subject) };
  }
  return null;
}

export async function knowledgeRequest<T>(session: LoginSession, path: string, signal: AbortSignal, body?: object): Promise<T> {
  const account = session.auth();
  const headers: Record<string, string> = { Accept: 'application/json', Authorization: authorization(account.username, account.password) };
  const multipart = body instanceof FormData;
  if (body && !multipart) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: body ? 'POST' : 'GET', body: multipart ? body : body ? JSON.stringify(body) : undefined, headers, signal,
    credentials: 'omit', cache: 'no-store', redirect: 'error',
  });
  signal.throwIfAborted();
  if (session.auth() !== account || response.status === 401) throw new AuthenticationError('다시 로그인해 주세요.');
  if (!response.ok) {
    const errors: Record<number, string> = {
      400: '입력한 내용과 프로젝트를 확인해 주세요. 비밀정보가 있으면 제거해 주세요.',
      403: '이 작업을 할 권한이 없어요.',
      404: '대상 문서나 제안을 찾을 수 없어요. 목록을 다시 불러와 주세요.',
      413: '파일당 8MB까지 올릴 수 있어요.',
      409: '문서가 변경되었거나 제안이 이미 처리됐어요. 다시 불러와 주세요.',
      429: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
    };
    throw new Error(errors[response.status] ?? '서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.');
  }
  let data: T;
  try { data = await response.json() as T; }
  catch { throw new Error('서버 응답을 읽지 못했어요.'); }
  signal.throwIfAborted();
  if (session.auth() !== account) throw new AuthenticationError('다시 로그인해 주세요.');
  return data;
}
