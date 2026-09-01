const CACHE_NAME = 'mojing-install-shell-v1';
const OWNED_CACHE_PREFIXES = ['mojing-install-shell-', 'dsp-cache-'];
const INSTALL_ASSETS = [
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(INSTALL_ASSETS)),
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys
        .filter((key) => key !== CACHE_NAME && OWNED_CACHE_PREFIXES.some((prefix) => key.startsWith(prefix)))
        .map((key) => caches.delete(key)),
    )),
  );
  clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || !INSTALL_ASSETS.includes(url.pathname)) return;

  // 仅安装清单和品牌图标允许缓存。API、用户媒体、HTML 和哈希资源
  // 都保持真实网络状态，避免离线时展示过期记录或旧页面。
  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request)),
  );
});
