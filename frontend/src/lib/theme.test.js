import assert from 'node:assert/strict';
import test from 'node:test';
import { persistTheme, readSavedTheme, resolveTheme, THEME_STORAGE_KEY } from './theme.js';

test('the first visit follows the system color scheme', () => {
  assert.equal(resolveTheme(null, true), 'dark');
  assert.equal(resolveTheme(null, false), 'light');
  assert.equal(resolveTheme('unknown', true), 'dark');
});

test('a saved theme wins over the system preference', () => {
  assert.equal(resolveTheme('dark', false), 'dark');
  assert.equal(resolveTheme('light', true), 'light');
});

test('only the theme key is stored', () => {
  const saved = new Map();
  const storage = {
    getItem: (key) => saved.get(key) ?? null,
    setItem: (key, value) => saved.set(key, value),
  };
  persistTheme('dark', storage);
  assert.equal(readSavedTheme(storage), 'dark');
  assert.deepEqual([...saved.keys()], [THEME_STORAGE_KEY]);
  persistTheme('light', storage);
  assert.equal(readSavedTheme(storage), 'light');
});
