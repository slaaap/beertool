// Brew Day alerts as phone notifications, for when the page cannot sound: the screen is locked or another app is open.
(function () {
  var btn = document.getElementById('push-enable');
  if (!btn || !('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return;

  var key = btn.getAttribute('data-key'), registration = null;

  function keyBytes(text) {
    var b64 = (text + '==='.slice((text.length + 3) % 4)).replace(/-/g, '+').replace(/_/g, '/');
    return Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
  }
  function save(subscription) {
    return fetch('/push/subscriptions', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(subscription)
    }).then(function (r) { if (!r.ok) throw new Error('beertool answered ' + r.status); });
  }
  function offer(on) { btn.hidden = !on || Notification.permission === 'denied'; }

  // A subscription the browser already has is sent again, so beertool keeps it even if it once dropped it.
  navigator.serviceWorker.register('/sw.js').then(function (r) {
    registration = r;
    return r.pushManager.getSubscription();
  }).then(function (existing) {
    if (existing) return save(existing).then(function () { offer(false); });
    offer(true);
  }).catch(function () { offer(false); });

  // iOS only asks for permission straight from a tap, so the subscribe call is the first thing the tap does.
  btn.addEventListener('click', function () {
    if (!registration) return;
    btn.disabled = true;
    registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) })
      .then(save)
      .then(function () { btn.textContent = 'Phone alerts are on'; setTimeout(function () { offer(false); }, 2000); })
      .catch(function () { btn.textContent = Notification.permission === 'denied' ? 'Notifications are off in Settings' : 'Couldn’t turn on phone alerts, tap to retry'; })
      .then(function () { btn.disabled = false; });
  });
})();
