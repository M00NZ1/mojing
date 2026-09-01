import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../public/sw.js', import.meta.url), 'utf8');
const listeners = new Map();
const addedAssets = [];
const deletedCaches = [];

const cachedResponse = { source: 'install-cache' };
const cachesMock = {
  open: async () => ({
    addAll: async (assets) => { addedAssets.push([...assets]); },
  }),
  keys: async () => [
    'dsp-cache-v2',
    'mojing-install-shell-v0',
    'mojing-install-shell-v1',
    'another-app-cache',
  ],
  delete: async (name) => {
    deletedCaches.push(name);
    return true;
  },
  match: async () => cachedResponse,
};

vm.runInNewContext(source, {
  URL,
  caches: cachesMock,
  clients: { claim: () => undefined },
  fetch: async () => ({ source: 'network' }),
  self: {
    location: { origin: 'http://127.0.0.1:4173' },
    addEventListener: (name, listener) => listeners.set(name, listener),
    skipWaiting: () => undefined,
  },
});

const installListener = listeners.get('install');
const activateListener = listeners.get('activate');
const fetchListener = listeners.get('fetch');
assert.ok(installListener && activateListener && fetchListener, 'service worker listeners must be registered');

let installWork;
installListener({ waitUntil: (work) => { installWork = work; } });
await installWork;
assert.deepEqual(addedAssets, [[
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg',
]]);

let activateWork;
activateListener({ waitUntil: (work) => { activateWork = work; } });
await activateWork;
assert.deepEqual(deletedCaches.sort(), ['dsp-cache-v2', 'mojing-install-shell-v0']);

function intercepted(path, origin = 'http://127.0.0.1:4173') {
  let response;
  fetchListener({
    request: { method: 'GET', url: `${origin}${path}` },
    respondWith: (value) => { response = value; },
  });
  return response;
}

assert.equal(intercepted('/api/sessions'), undefined, 'API responses must never be served from the install cache');
assert.equal(intercepted('/storage/conversations/1/image.png'), undefined, 'user media must bypass the install cache');
assert.equal(intercepted('/assets/index-hash.js'), undefined, 'versioned app assets must use their real network state');
assert.equal(intercepted('/chat/1'), undefined, 'HTML navigation must not fall back to an old cached page');
assert.equal(intercepted('/manifest.json', 'https://example.com'), undefined, 'cross-origin assets must not be intercepted');
assert.equal(await intercepted('/manifest.json'), cachedResponse);
assert.equal(await intercepted('/icons/icon-192.png'), cachedResponse);
