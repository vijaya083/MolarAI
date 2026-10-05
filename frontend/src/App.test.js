import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { createServer } from 'vite';

let vite;
let App;

before(async () => {
  vite = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.js', import.meta.url)),
    server: { middlewareMode: true },
    appType: 'custom',
  });
  ({ default: App } = await vite.ssrLoadModule('/src/App.jsx'));
});

after(async () => {
  await vite?.close();
});

test('renders the safe unavailable message after a KNOWLEDGE_UNAVAILABLE response', async () => {
  const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/',
  });
  const originalFetch = globalThis.fetch;
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  const originalNavigator = globalThis.navigator;
  const originalHTMLElement = globalThis.HTMLElement;
  const originalCrypto = globalThis.crypto;
  const originalActEnvironment = globalThis.IS_REACT_ACT_ENVIRONMENT;
  Object.defineProperties(globalThis, {
    window: { configurable: true, value: dom.window },
    document: { configurable: true, value: dom.window.document },
    navigator: { configurable: true, value: dom.window.navigator },
    HTMLElement: { configurable: true, value: dom.window.HTMLElement },
    crypto: { configurable: true, value: originalCrypto },
    IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
  });
  dom.window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {} });
  dom.window.HTMLElement.prototype.scrollIntoView = () => {};
  globalThis.fetch = async () => ({
    ok: false,
    status: 503,
    json: async () => ({
      code: 'KNOWLEDGE_UNAVAILABLE',
      error: 'Knowledge-based answers are temporarily unavailable.',
      trace: 'private backend diagnostic',
    }),
  });

  const root = createRoot(document.getElementById('root'));
  try {
    await act(async () => root.render(React.createElement(App)));
    const question = [...document.querySelectorAll('button')]
      .find((button) => button.textContent.includes('What services do you offer?'));
    assert.ok(question, 'expected a clinic-question suggestion');

    await act(async () => {
      question.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    const alert = document.querySelector('[role="alert"]');
    assert.ok(alert);
    assert.match(alert.textContent, /Knowledge-based answers are temporarily unavailable\./);
    assert.doesNotMatch(alert.textContent, /couldn’t reach MolarAI|private backend diagnostic|KnowledgeUnavailableException|stack trace/i);
    assert.match(document.body.textContent, /Book an appointment/);
    assert.equal(document.querySelector('#chat-input').disabled, false);
    assert.equal(document.body.textContent.includes('private backend diagnostic'), false);
  } finally {
    await act(async () => root.unmount());
    globalThis.fetch = originalFetch;
    Object.defineProperties(globalThis, {
      window: { configurable: true, value: originalWindow },
      document: { configurable: true, value: originalDocument },
      navigator: { configurable: true, value: originalNavigator },
      HTMLElement: { configurable: true, value: originalHTMLElement },
      crypto: { configurable: true, value: originalCrypto },
      IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: originalActEnvironment },
    });
    dom.window.close();
  }
});
