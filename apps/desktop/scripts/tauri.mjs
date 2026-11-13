import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { loadEnv } from 'vite';
import { buildTarget, cleanBuildOutputs } from './clean-build.mjs';

const args = process.argv.slice(2);
const require = createRequire(import.meta.url);
const cli = require.resolve('@tauri-apps/cli/tauri.js');
const run = cliArgs => {
  const result = spawnSync(process.execPath, [cli, ...cliArgs], { stdio: 'inherit', env: process.env });
  if (result.error) throw result.error;
  return result.status ?? 1;
};

const [command] = args;
if (['build', 'bundle', 'dev'].includes(command) && !args.includes('--help') && !args.includes('-h')) {
  const branding = JSON.parse(readFileSync(new URL('../../../branding.json', import.meta.url), 'utf8'));
  const name = branding.appTitle ?? branding.name;
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 40
      || /[\x00-\x1f<>:"/\\|?*]/u.test(name) || /[. ]$/u.test(name)) {
    throw new Error('branding.json appTitle은 1~40자이며 파일명에 사용할 수 있어야 합니다.');
  }
  const desktop = fileURLToPath(new URL('../', import.meta.url));
  const mode = command === 'dev' ? 'development' : 'production';
  const { VITE_API_BASE_URL } = loadEnv(mode, desktop, 'VITE_API_BASE_URL');
  const address = VITE_API_BASE_URL?.trim() || 'http://127.0.0.1:9001';
  const url = URL.parse(address);
  if (!url || !['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.search || url.hash || /[;,'"]/u.test(url.origin)) {
    throw new Error('VITE_API_BASE_URL에 HTTP 또는 HTTPS 서버 주소를 입력해 주세요.');
  }
  process.env.VITE_API_BASE_URL = url.href.replace(/\/+$/, '');
  const { app } = JSON.parse(readFileSync(new URL('../src-tauri/tauri.conf.json', import.meta.url), 'utf8'));
  const csp = app.security.csp.replace(/connect-src[^;]*/, `connect-src ipc: http://ipc.localhost ${url.origin}`);

  const target = buildTarget(args);
  const windows = target ? target.includes('-windows-') : process.platform === 'win32';
  const mac = target ? target.endsWith('-apple-darwin') : process.platform === 'darwin';
  const bundle = { publisher: name.trim() };
  if (command !== 'dev' && !args.includes('--no-bundle') && (windows || mac)) {
    bundle.active = true;
    bundle.targets = [windows ? 'nsis' : 'app'];
    bundle.icon = [
      '../../../assets/pet/app-icon.png',
      windows ? 'target/generated-icons/icon.ico' : 'target/generated-icons/icon.icns',
    ];
    const status = run(['icon', '../../assets/pet/app-icon.png', '--output', 'src-tauri/target/generated-icons']);
    if (status !== 0) process.exit(status);
  }
  args.push('--config', JSON.stringify({
    productName: name.trim(),
    bundle,
    app: { security: { csp } },
  }));
}
const output = cleanBuildOutputs(args, fileURLToPath(new URL('../src-tauri/target/', import.meta.url)));
if (output) console.log(`이전 배포 산출물 정리 완료: ${output}`);
process.exit(run(args));
