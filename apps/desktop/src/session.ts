import { apiBaseUrl } from './api.ts';

export class AuthenticationError extends Error {}
export interface Session { username: string; projects: string[]; admin?: boolean }

export function authorization(username: string, password: string): string {
  if (!username.trim() || !password) throw new AuthenticationError('사용자와 비밀번호를 입력해 주세요.');
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  return `Basic ${btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))}`;
}

// Credentials live only in this app process; closing the pet does not log out.
export class LoginSession {
  private credentials: { username: string; password: string } | null = null;
  user: Session | null = null;
  local = false;
  get active(): boolean { return this.local || this.credentials !== null; }
  async login(username: string, password: string, signal?: AbortSignal): Promise<void> {
    const response = await fetch(`${apiBaseUrl}/api/session`, {
      signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      headers: { Authorization: authorization(username, password), Accept: 'application/json' },
    });
    if (response.status === 401) throw new AuthenticationError('사용자 또는 비밀번호가 맞지 않아요. 다시 입력해 주세요.');
    if (response.status === 404) throw new Error('서버를 최신 버전으로 다시 실행해 주세요. 로그인 기능을 찾지 못했어요.');
    if (!response.ok) throw new Error('로그인할 수 없어요. 서버 상태와 계정 권한을 확인해 주세요.');
    let data: unknown;
    try { data = await response.json(); } catch { throw new Error('로그인 응답이 올바르지 않아요.'); }
    if (!data || typeof data !== 'object' || !('username' in data) || typeof data.username !== 'string'
      || !('projects' in data) || !Array.isArray(data.projects) || !data.projects.every(p => typeof p === 'string')) throw new Error('로그인 응답이 올바르지 않아요.');
    signal?.throwIfAborted();
    this.credentials = { username, password };
    this.user = { username: data.username, projects: data.projects, ...('admin' in data ? { admin: data.admin === true } : {}) };
    this.local = false;
  }
  useMock(): void { this.logout(); this.local = true; }
  logout(): void { this.credentials = null; this.user = null; this.local = false; }
  auth(): { username: string; password: string } {
    if (!this.credentials) throw new AuthenticationError('다시 로그인해 주세요.');
    return this.credentials;
  }
}
