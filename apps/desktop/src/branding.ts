import { invoke, isTauri } from '@tauri-apps/api/core';
import projectBranding from '../../../branding.json' with { type: 'json' };

type Theme = 'gold' | 'blue' | 'green' | 'mono';

export function parseBranding(value: unknown, defaults: { name: string; badge: string; theme: Theme } = { name: 'MyChatBot', badge: 'CHAT', theme: 'gold' }) {
  const config = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  const name = typeof config.name === 'string' ? config.name.trim() : '';
  const badge = typeof config.badge === 'string' ? config.badge.trim() : null;
  const theme = config.theme;
  return {
    name: name && name.length <= 40 ? name : defaults.name,
    badge: badge !== null && badge.length <= 20 ? badge : defaults.badge,
    theme: theme === 'gold' || theme === 'blue' || theme === 'green' || theme === 'mono' ? theme : defaults.theme,
  };
}

export async function applyBranding(): Promise<void> {
  const bundled = parseBranding(projectBranding);
  const defaults = {
    ...bundled,
    name: import.meta.env.VITE_CHATBOT_NAME?.trim() || bundled.name,
    badge: import.meta.env.VITE_CHATBOT_BADGE?.trim() || bundled.badge,
  };
  let config: unknown;
  if (isTauri()) {
    try {
      const content = await invoke<string | null>('read_branding');
      if (content !== null) config = JSON.parse(content);
    } catch { /* Use defaults when the local configuration is unreadable. */ }
  }
  const { name, badge, theme } = parseBranding(config, defaults);
  document.documentElement.dataset.theme = theme;
  for (const element of document.querySelectorAll('[data-chatbot-name]')) element.textContent = name;
  for (const element of document.querySelectorAll<HTMLElement>('[data-chatbot-badge]')) {
    element.textContent = badge;
    element.hidden = badge === '';
  }
  const pet = document.getElementById('pet');
  if (pet) pet.setAttribute('aria-label', `${name} 열기. 드래그하면 이동합니다`);
}
