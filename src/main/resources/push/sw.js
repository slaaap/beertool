// Safari revokes the permission for a push that shows nothing, so every message becomes a notification.
self.addEventListener('push', function (event) {
  var m = {};
  try { m = event.data ? event.data.json() : {}; } catch (e) {}
  event.waitUntil(self.registration.showNotification(m.title || 'beertool', {
    body: m.body || '', tag: m.tag, renotify: !!m.tag, icon: '/static/icons/app-192.png', data: { url: m.url || '/brew' }
  }));
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var url = new URL(event.notification.data && event.notification.data.url || '/brew', self.location.origin).href;
  event.waitUntil(clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (open) {
    for (var i = 0; i < open.length; i++) {
      if (open[i].url === url && 'focus' in open[i]) return open[i].focus();
    }
    return clients.openWindow(url);
  }));
});
