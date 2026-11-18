import { sourceFile } from './sources.ts';
import type { DocumentTarget } from './knowledge.ts';
import { authorization, AuthenticationError, type LoginSession } from './session.ts';
import { apiBaseUrl } from './api.ts';

export interface BrowsableDocument extends DocumentTarget {
  type: string; bytes: number; modified: number; replaceable?: boolean; attachments?: BrowsableDocument[];
}
export interface DocumentVersion { version: string; filename: string; createdAt: string }
export type DocumentPreview = { kind: 'text'; text: string; message?: string }
  | { kind: 'sheets'; sheets: { name: string; rows: string[][] }[]; message?: string }
  | { kind: 'pdf' | 'unavailable'; message?: string };

export function browsableDocuments(value: unknown): BrowsableDocument[] {
  const invalid = () => new Error('파일 목록을 읽지 못했어요.');
  if (!Array.isArray(value) || value.length > 10000) throw invalid();
  const parse = (item: unknown, child = false): BrowsableDocument => {
    if (!item || typeof item !== 'object') throw invalid();
    const file = item as BrowsableDocument;
    if (!sourceFile(file) || typeof file.path !== 'string' || !file.path || file.path.length > 2000
      || typeof file.title !== 'string' || file.title.length > 1000 || typeof file.project !== 'string' || typeof file.type !== 'string'
      || !Number.isSafeInteger(file.bytes) || file.bytes < 0 || !Number.isSafeInteger(file.modified) || file.modified < 0
      || (file.replaceable !== undefined && typeof file.replaceable !== 'boolean')
      || (file.attachments !== undefined && (!Array.isArray(file.attachments) || file.attachments.length > (child ? 0 : 3)))) throw invalid();
    const attachments = file.attachments?.map(attachment => parse(attachment, true));
    return { documentId: file.documentId, version: file.version, path: file.path, title: file.title,
      project: file.project, type: file.type, bytes: file.bytes, modified: file.modified,
      ...(file.replaceable === undefined ? {} : { replaceable: file.replaceable }),
      ...(attachments?.length ? { attachments } : {}) };
  };
  return value.map(item => parse(item));
}
export function documentPreview(value: unknown): DocumentPreview {
  if (!value || typeof value !== 'object' || !('kind' in value)
    || ('message' in value && value.message !== null && typeof value.message !== 'string')) throw new Error('미리보기를 읽지 못했어요.');
  const data = value as DocumentPreview;
  if (data.kind === 'text' && typeof data.text === 'string' && data.text.length <= 200000) return data;
  if (data.kind === 'sheets' && Array.isArray(data.sheets) && data.sheets.length <= 100 && data.sheets.every(sheet =>
    sheet && typeof sheet.name === 'string' && Array.isArray(sheet.rows) && sheet.rows.length <= 1000
    && sheet.rows.every(row => Array.isArray(row) && row.length <= 200 && row.every(cell => typeof cell === 'string' && cell.length <= 100000)))) return data;
  if (data.kind === 'pdf' || data.kind === 'unavailable') return data;
  throw new Error('미리보기를 읽지 못했어요.');
}
export function documentVersions(value: unknown): DocumentVersion[] {
  if (!Array.isArray(value) || value.length > 10000 || !value.every(item => item && /^[a-f0-9]{64}$/.test(item.version)
    && typeof item.filename === 'string' && typeof item.createdAt === 'string')) throw new Error('이전 버전 목록을 읽지 못했어요.');
  return value;
}
export function replacementFile(file: Pick<File, 'name' | 'size'> | undefined, target: DocumentTarget | null): string | null {
  if (!target) return '왼쪽 목록에서 교체할 파일을 선택해 주세요.';
  if ('replaceable' in target && target.replaceable === false) return '이 파일은 다운로드와 미리보기만 지원해요.';
  if (!file || file.size < 1 || file.size > 8 * 1024 * 1024) return '수정 파일은 1개, 8MB 이하로 올려 주세요.';
  const extension = (name: string) => /\.([^.]+)$/.exec(name)?.[1].toLowerCase();
  if (!extension(target.path) || extension(file.name) !== extension(target.path)) return '원본과 같은 확장자의 수정 파일을 올려 주세요.';
  return null;
}
export async function previewPdf(session: LoginSession, file: Pick<DocumentTarget, 'documentId' | 'version'>, signal: AbortSignal): Promise<Blob> {
  const account = session.auth();
  const response = await fetch(`${apiBaseUrl}/api/knowledge/documents/${file.documentId}/preview/pdf?version=${file.version}`, {
    signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
    headers: { Authorization: authorization(account.username, account.password), Accept: 'application/pdf' },
  });
  signal.throwIfAborted();
  if (session.auth() !== account || response.status === 401) throw new AuthenticationError('다시 로그인해 주세요.');
  if (!response.ok || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/pdf') throw new Error('PDF 미리보기를 만들 수 없어요. 원본을 다운로드해 주세요.');
  if (Number(response.headers.get('Content-Length')) > 20 * 1024 * 1024) throw new Error('미리보기 파일이 너무 커요. 원본을 다운로드해 주세요.');
  const blob = await response.blob();
  signal.throwIfAborted();
  if (session.auth() !== account) throw new AuthenticationError('다시 로그인해 주세요.');
  if (blob.size > 20 * 1024 * 1024) throw new Error('미리보기 파일이 너무 커요. 원본을 다운로드해 주세요.');
  if (!(await blob.slice(0, 5).text()).startsWith('%PDF-')) throw new Error('PDF 미리보기를 읽지 못했어요. 원본을 다운로드해 주세요.');
  signal.throwIfAborted();
  if (session.auth() !== account) throw new AuthenticationError('다시 로그인해 주세요.');
  return new Blob([blob], { type: 'application/pdf' });
}

export function proposalScope(mode: string, folder: string, target: DocumentTarget | null): {
  project: string; folderPath: string; sourceDocumentId?: string; sourceVersion?: string;
} {
  if (mode === 'new') {
    if (!folder) throw new Error('문서를 저장할 폴더를 선택해 주세요.');
    return { project: folder.split('/')[0], folderPath: folder };
  }
  if (mode !== 'replace') throw new Error('새 문서 작성 또는 수정 파일로 교체를 선택해 주세요.');
  if (!target) throw new Error('왼쪽 목록에서 대상 파일을 선택해 주세요.');
  return { project: target.project, folderPath: target.path.substring(0, target.path.lastIndexOf('/')),
    sourceDocumentId: target.documentId, sourceVersion: target.version };
}
