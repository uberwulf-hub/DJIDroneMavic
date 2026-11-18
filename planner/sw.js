// Minimal service worker so the planner can be installed as an app (Edge/Chrome "Install").
const CACHE = 'airplan-v5';
const FILES = ['./', './index.html', './plan.js', './preview.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return; // map tiles and libraries go straight to the network
  e.respondWith(fetch(e.request).then(r => {
    const copy = r.clone();
    caches.open(CACHE).then(c => c.put(e.request, copy));
    return r;
  }).catch(() => caches.match(e.request)));
});
