export const THEME_STORAGE_KEY = 'molarai-theme';

export function resolveTheme(saved, prefersDark) {
  if (saved === 'light' || saved === 'dark') return saved;
  return prefersDark ? 'dark' : 'light';
}

export function readSavedTheme(storage = globalThis.localStorage) {
  try {
    return storage?.getItem(THEME_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

export function persistTheme(theme, storage = globalThis.localStorage) {
  storage.setItem(THEME_STORAGE_KEY, theme);
}

export function applyTheme(theme, root = document.documentElement) {
  root.setAttribute('data-theme', theme);
  root.style.colorScheme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'dark' ? '#1a232b' : '#f5f9f8');
}
