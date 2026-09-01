import { readMoJingStorage, writeMoJingStorage } from './utils/mojingStorage';

/** 与 styles.css 中 [data-theme="…"] 一一对应，由设置页统一管理。 */
export const THEME_KEY = 'mojing_theme';

export const THEME_IDS = ['dark', 'light', 'paper', 'ink', 'forest', 'studio', 'midnight'] as const;
export type ThemeId = (typeof THEME_IDS)[number];

export const THEME_LABELS: Record<ThemeId, string> = {
  dark: '深青暗色',
  light: '暖纸浅色',
  paper: '奶油报刊',
  ink: '水墨夜读',
  forest: '苔原森色',
  studio: '琥珀暗房',
  midnight: '星夜紫（经典）',
};

export function isValidTheme(id: string | null | undefined): id is ThemeId {
  return id != null && (THEME_IDS as readonly string[]).includes(id);
}

export function applyTheme(id: string) {
  const next = isValidTheme(id) ? id : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  writeMoJingStorage(THEME_KEY, next);
  window.dispatchEvent(new CustomEvent('mojing-theme-changed', { detail: next }));
}

export function currentThemeId(): ThemeId {
  const raw = document.documentElement.getAttribute('data-theme') || 'dark';
  return isValidTheme(raw) ? raw : 'dark';
}

/** 聊天区行距/气泡密度，与 data-theme 解耦（调研 §2.6） */
export const CHAT_DENSITY_KEY = 'mojing_chat_density';

export const CHAT_DENSITY_IDS = ['comfortable', 'compact', 'reader'] as const;
export type ChatDensityId = (typeof CHAT_DENSITY_IDS)[number];

export const CHAT_DENSITY_LABELS: Record<ChatDensityId, string> = {
  comfortable: '舒适',
  compact: '紧凑',
  reader: '阅读',
};

export function isValidChatDensity(id: string | null | undefined): id is ChatDensityId {
  return id != null && (CHAT_DENSITY_IDS as readonly string[]).includes(id);
}

export function applyChatDensity(id: string) {
  const next = isValidChatDensity(id) ? id : 'comfortable';
  document.documentElement.setAttribute('data-chat-density', next);
  writeMoJingStorage(CHAT_DENSITY_KEY, next);
  window.dispatchEvent(new CustomEvent('mojing-chat-density-changed', { detail: next }));
}

export function currentChatDensityId(): ChatDensityId {
  const raw = document.documentElement.getAttribute('data-chat-density') || 'comfortable';
  return isValidChatDensity(raw) ? raw : 'comfortable';
}

export function readSavedThemeId(): string | null {
  return readMoJingStorage(THEME_KEY);
}

export function readSavedChatDensityId(): string | null {
  return readMoJingStorage(CHAT_DENSITY_KEY);
}
