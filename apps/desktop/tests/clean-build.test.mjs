import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { cleanBuildOutputs } from '../scripts/clean-build.mjs';

test('build cleanup removes old outputs only for the selected target and profile', () => {
  const root = mkdtempSync(join(tmpdir(), 'mybot-clean-'));
  try {
    const output = join(root, 'x86_64-pc-windows-msvc', 'release');
    for (const file of ['bundle/nsis/Old_setup.exe', 'easytouse-ai-pet.exe', 'my-chat-bot.exe',
      'my_chat_bot.pdb', 'deps/cache', 'debug/keep']) {
      const path = join(output, file);
      mkdirSync(join(path, '..'), { recursive: true }); writeFileSync(path, 'fixture');
    }
    for (const file of ['generated-icons/icon.ico', 'windows-bundle.json', 'debug/bundle/macos/keep.app']) {
      const path = join(root, file);
      mkdirSync(join(path, '..'), { recursive: true }); writeFileSync(path, 'fixture');
    }
    cleanBuildOutputs(['build', '--target=x86_64-pc-windows-msvc'], root, {});
    for (const file of ['bundle', 'easytouse-ai-pet.exe', 'my-chat-bot.exe', 'my_chat_bot.pdb']) {
      assert.equal(existsSync(join(output, file)), false);
    }
    for (const file of ['generated-icons/icon.ico', 'windows-bundle.json', 'debug/bundle/macos/keep.app']) {
      assert.equal(existsSync(join(root, file)), true);
    }
    assert.equal(existsSync(join(output, 'deps/cache')), true);
    assert.equal(cleanBuildOutputs(['dev'], root, {}), null);
    assert.equal(cleanBuildOutputs(['build', '--help'], root, {}), null);
    assert.throws(() => cleanBuildOutputs(['build', '--target=../outside'], root, {}));
  } finally { rmSync(root, { recursive: true, force: true }); }
});
