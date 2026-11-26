var sound = (function () {
  var audio = null, ringing = null;

  function beep() {
    try {
      if (audio) {
        var o = audio.createOscillator(), g = audio.createGain();
        o.connect(g); g.connect(audio.destination);
        o.type = 'sine'; o.frequency.value = 880; g.gain.value = 0.25;
        o.start(); o.stop(audio.currentTime + 0.18);
      }
    } catch (e) {}
    if (navigator.vibrate) navigator.vibrate([200, 90, 200]);
  }
  function hush() { clearInterval(ringing); ringing = null; }

  document.addEventListener('pointerdown', hush);
  return {
    unlock: function () {
      try {
        if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
        if (audio && audio.resume) audio.resume();
      } catch (e) {}
    },
    // One blip is easy to miss over a roaring burner, so an alert repeats until the brewer touches the screen.
    alarm: function () {
      var stopAt = Date.now() + 3 * 60 * 1000;
      hush(); beep();
      ringing = setInterval(function () { if (Date.now() > stopAt) hush(); else beep(); }, 2500);
    }
  };
})();

function clockTime(ms) { return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }

// These buttons sit beside Start, so a stray wet tap only arms them and a second tap within three seconds acts.
function confirmTap(btn, action) {
  var label = btn.textContent, armed = null;
  function disarm() { clearTimeout(armed); armed = null; btn.textContent = label; btn.classList.remove('armed'); }
  btn.addEventListener('click', function () {
    if (armed) { disarm(); action(); return; }
    btn.textContent = 'Tap again'; btn.classList.add('armed');
    armed = setTimeout(disarm, 3000);
  });
}

// A running timer keeps ticking on its tab, so it stays in sight from any other stage.
function tabClock(stage, text) {
  var el = document.querySelector('[data-tab="' + stage + '"] .tab-clock');
  if (el) el.textContent = text;
}

(function () {
  var clock = document.getElementById('boil-clock');
  if (!clock) return;

  var total = parseInt(clock.getAttribute('data-boil'), 10) * 60;
  var key = 'boil:' + clock.getAttribute('data-key');
  var btn = document.getElementById('boil-start');
  var status = document.getElementById('boil-status');
  var rows = [].slice.call(document.querySelectorAll('#boil-adds [data-remaining]'));
  var reset = document.getElementById('boil-reset');
  var left = total, running = false, endAt = 0, timer = null, fired = {}, late = {}, external = false;

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    try { localStorage.setItem(key, JSON.stringify({ running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }
  function clearSaved() { try { localStorage.removeItem(key); } catch (e) {} }

  function render() {
    if (left < 0) left = 0;
    clock.textContent = Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('boil', running ? clock.textContent : '');
    status.textContent = (external && left > 0 ? 'Timed by the brewhouse. ' : '') +
      (running ? 'Flame-out at ' + clockTime(endAt) : left === 0 ? 'Flame-out. Time to chill.' : left < total ? 'Paused.' : '');
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = parseInt(r.getAttribute('data-remaining'), 10);
      r.classList.toggle('now', at === 0 ? left === 0 : (m === at && left > 0));
      r.classList.toggle('done', at === 0 ? false : m < at);
      r.classList.toggle('late', !!late[at]);
    });
  }

  // A throttled or locked page can skip an addition's minute, so anything now due or past that hasn't sounded sounds late.
  function fireDue() {
    var m = Math.ceil(left / 60), due = false;
    rows.forEach(function (r) {
      var at = parseInt(r.getAttribute('data-remaining'), 10);
      if (fired[at] || (at === 0 ? left > 0 : at < m)) return;
      fired[at] = true; due = true;
      if (at > m) late[at] = true;
    });
    if (due) sound.alarm();
  }

  function tick() {
    if (!running) return;
    left = Math.round((endAt - Date.now()) / 1000);
    if (left <= 0) left = 0;
    fireDue();
    render();
    if (left === 0) { running = false; clearInterval(timer); timer = null; btn.textContent = 'Start'; save(); render(); }
  }
  function start() {
    sound.unlock();
    endAt = Date.now() + left * 1000;
    running = true; save();
    timer = setInterval(tick, 500);
    btn.textContent = 'Pause';
    render();
  }
  function pause() {
    clearInterval(timer); timer = null;
    running = false; save();
    btn.textContent = 'Start';
    render();
  }

  try {
    var s = JSON.parse(localStorage.getItem(key));
    if (s) {
      if (s.running) { endAt = s.endAt; running = true; }
      else if (s.remaining != null) { left = s.remaining; }
    }
  } catch (e) {}
  if (running) {
    left = Math.round((endAt - Date.now()) / 1000);
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = parseInt(r.getAttribute('data-remaining'), 10);
      if (left <= 0 || at > m) fired[at] = true;
    });
    if (left <= 0) { left = 0; running = false; save(); }
    else { timer = setInterval(tick, 500); btn.textContent = 'Pause'; }
  }
  render();

  // While the brewhouse boils, its boil time drives the clock so additions sound on the controller's timing.
  window.boilClock = {
    follow: function (seconds, ticking) {
      var fresh = !external;
      if (fresh) {
        external = true; btn.hidden = true; reset.hidden = true;
        var m = Math.ceil(seconds / 60);
        rows.forEach(function (r) {
          var at = parseInt(r.getAttribute('data-remaining'), 10);
          if (at > m) fired[at] = true;
        });
      }
      var want = Math.max(0, Math.round(seconds));
      var shown = running ? Math.round((endAt - Date.now()) / 1000) : left;
      // A report is a second or two old by the time it lands, so only a real gap moves the clock.
      if (fresh || running !== (ticking && want > 0) || Math.abs(want - shown) > 2) {
        left = want;
        running = ticking && left > 0;
        endAt = Date.now() + left * 1000;
      }
      if (running && !timer) { sound.unlock(); timer = setInterval(tick, 500); }
      if (!running && timer) { clearInterval(timer); timer = null; }
      fireDue(); render();
    },
    // finished: the controller ended the boil, so a clock a second or two behind still reaches flame-out and its addition.
    release: function (finished) {
      if (!external) return;
      external = false; btn.hidden = false; reset.hidden = false;
      clearInterval(timer); timer = null; running = false;
      if (finished) { left = 0; fireDue(); }
      btn.textContent = 'Start'; save(); render();
    }
  };

  btn.onclick = function () { running ? pause() : start(); };
  confirmTap(reset, function () {
    clearInterval(timer); timer = null; running = false;
    left = total; fired = {}; late = {}; clearSaved();
    btn.textContent = 'Start'; render();
  });
  rows.forEach(function (r) {
    r.addEventListener('click', function () {
      delete late[parseInt(r.getAttribute('data-remaining'), 10)]; render();
    });
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden && running) tick(); });
})();

(function () {
  var clock = document.getElementById('mash-clock');
  if (!clock) return;

  var key = 'mash:' + clock.getAttribute('data-key');
  var status = document.getElementById('mash-status');
  var btn = document.getElementById('mash-start');
  var next = document.getElementById('mash-next');
  var reset = document.getElementById('mash-reset');
  var rows = [].slice.call(document.querySelectorAll('table.mash-plan tr[data-min]'));
  var steps = rows.map(function (r) { return { temp: r.getAttribute('data-temp'), min: parseInt(r.getAttribute('data-min'), 10) }; });
  var step = 0, left = steps[0].min * 60, running = false, endAt = 0, timer = null, external = false, heating = false;

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    if (external) return;
    try { localStorage.setItem(key, JSON.stringify({ step: step, running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }

  function render() {
    var done = step >= steps.length, fresh = !done && left === steps[step].min * 60;
    rows.forEach(function (r, i) {
      r.classList.toggle('now', i === step);
      r.classList.toggle('done', i < step);
    });
    btn.disabled = done;
    next.disabled = done;
    btn.textContent = running ? 'Pause' : fresh || done ? 'Start' : 'Resume';
    clock.textContent = done ? 'Done' : Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('mash', running ? clock.textContent : '');
    status.textContent =
      done ? 'Mash done. Time to sparge.' :
      external ? (heating ? 'Step ' + (step + 1) + ' · heating to ' + steps[step].temp + ' °C' :
        running ? 'Step ' + (step + 1) + ' · hold ' + steps[step].temp + ' °C until ' + clockTime(endAt) :
        'Paused at step ' + (step + 1)) + '. Timed by the brewhouse.' :
      running ? 'Step ' + (step + 1) + ' · hold ' + steps[step].temp + ' °C until ' + clockTime(endAt) :
      !fresh ? 'Paused at step ' + (step + 1) + '.' :
      step > 0 ? 'Step ' + step + ' done. Raise the mash to ' + steps[step].temp + ' °C, then start.' :
      'Bring the mash to ' + steps[0].temp + ' °C, then start.';
  }

  function stopTicking() { clearInterval(timer); timer = null; }

  // Each rest waits for the brewer to reach its temperature, so a finished step never starts the next one.
  function finishStep() {
    stopTicking(); running = false;
    step++;
    left = step < steps.length ? steps[step].min * 60 : 0;
    save(); render();
  }

  // Under the brewhouse a step ends when the controller says so, so the clock only runs down to zero.
  function tick() {
    if (!running) return;
    left = Math.round((endAt - Date.now()) / 1000);
    if (left <= 0 && external) { left = 0; stopTicking(); running = false; render(); return; }
    if (left <= 0) { sound.alarm(); finishStep(); return; }
    render();
  }

  function restore() {
    step = 0; left = steps[0].min * 60; running = false;
    try {
      var s = JSON.parse(localStorage.getItem(key));
      if (s && s.step != null) {
        step = Math.min(s.step, steps.length);
        left = step < steps.length ? steps[step].min * 60 : 0;
        if (s.running) { endAt = s.endAt; running = true; }
        else if (s.remaining != null) left = s.remaining;
      }
    } catch (e) {}
    if (running) {
      left = Math.round((endAt - Date.now()) / 1000);
      if (left <= 0) finishStep();
      else timer = setInterval(tick, 500);
    }
    render();
  }
  restore();

  // While the brewhouse mashes, its step and time left drive the clock; seconds is null while it heats to the step.
  window.mashClock = {
    follow: function (index, seconds, ticking) {
      var fresh = !external;
      if (fresh) { external = true; stopTicking(); running = false; btn.hidden = next.hidden = reset.hidden = true; }
      var moved = index !== step;
      step = Math.min(index, steps.length);
      heating = seconds == null && step < steps.length;
      var want = step >= steps.length ? 0 : heating ? steps[step].min * 60 : Math.max(0, Math.round(seconds));
      var go = ticking && !heating && want > 0;
      var shown = running ? Math.round((endAt - Date.now()) / 1000) : left;
      if (fresh || moved || running !== go || Math.abs(want - shown) > 2) {
        left = want; running = go; endAt = Date.now() + left * 1000;
      }
      if (running && !timer) timer = setInterval(tick, 500);
      if (!running) stopTicking();
      render();
    },
    release: function () {
      if (!external) return;
      external = false; heating = false; stopTicking();
      btn.hidden = next.hidden = reset.hidden = false;
      restore();
    }
  };

  btn.onclick = function () {
    if (running) {
      stopTicking(); running = false;
    } else {
      sound.unlock();
      endAt = Date.now() + left * 1000; running = true;
      timer = setInterval(tick, 500);
    }
    save(); render();
  };
  confirmTap(next, finishStep);
  confirmTap(reset, function () {
    stopTicking(); running = false;
    step = 0; left = steps[0].min * 60;
    try { localStorage.removeItem(key); } catch (e) {}
    render();
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden && running) tick(); });
})();

(function () {
  var root = document.getElementById('mash-water');
  if (!root) return;

  var grain = parseFloat(root.dataset.grain);
  var preboil = parseFloat(root.dataset.preboil);
  var rest = parseFloat(root.dataset.rest);
  var heat = parseFloat(root.dataset.heat);

  var gt = document.getElementById('mw-grain-temp');
  var ratio = document.getElementById('mw-ratio');
  var ret = document.getElementById('mw-retention');
  var oStrike = document.getElementById('strike-water');
  var oTemp = document.getElementById('strike-temp');
  var oSparge = document.getElementById('sparge-water');
  var page = document.getElementById('brewday');
  var store = 'mash-water:' + (page ? page.getAttribute('data-key') : '');

  function show(el, v) { el.textContent = isFinite(v) ? v.toFixed(1) : '-'; }
  function update() {
    var r = parseFloat(ratio.value), a = parseFloat(ret.value), g = parseFloat(gt.value);
    var strike = grain * r;
    var sparge = preboil + grain * a - strike;
    if (sparge < 0) sparge = 0;
    show(oStrike, strike);
    show(oSparge, sparge);
    show(oTemp, r > 0 ? rest + (heat / r) * (rest - g) : NaN);
  }
  // A save reloads the page, so the brewer's tweaks are kept per brew rather than reset to the defaults.
  try {
    var kept = JSON.parse(localStorage.getItem(store));
    if (kept) { gt.value = kept.grain; ratio.value = kept.ratio; ret.value = kept.retention; }
  } catch (e) {}
  [gt, ratio, ret].forEach(function (e) {
    e.addEventListener('input', function () {
      update();
      try { localStorage.setItem(store, JSON.stringify({ grain: gt.value, ratio: ratio.value, retention: ret.value })); } catch (e) {}
    });
  });
  update();
})();

(function () {
  var root = document.getElementById('brewday');
  if (!root) return;

  var stages = [].slice.call(root.querySelectorAll('.stage'));
  var keys = stages.map(function (s) { return s.getAttribute('data-stage'); });
  var labels = stages.map(function (s) { return s.querySelector('.stage-title').textContent; });
  var tabs = [].slice.call(root.querySelectorAll('[data-tab]'));
  var prev = document.getElementById('stage-prev'), next = document.getElementById('stage-next');
  var store = 'stage:' + root.getAttribute('data-key');
  var at = 0;

  function show(i) {
    at = i;
    stages.forEach(function (s, j) { s.hidden = j !== i; });
    root.setAttribute('data-stage', keys[i]);
    tabs.forEach(function (t, j) { t.classList.toggle('active', j === i); });
    prev.hidden = i === 0;
    next.hidden = i === keys.length - 1;
    if (i > 0) prev.textContent = '◀ ' + labels[i - 1];
    if (i < keys.length - 1) next.textContent = labels[i + 1] + ' ▶';
    try { localStorage.setItem(store, keys[i]); } catch (e) {}
  }
  function go(i) { show(i); window.scrollTo(0, 0); history.replaceState(null, '', '#' + keys[i]); }

  // A stage named in the link wins, so the water form can send the brewer back to #water. Once the OG is in,
  // the brew opens on Ferment rather than wherever brew day left off.
  var wanted = location.hash.slice(1), saved = null, fallback = root.getAttribute('data-start');
  try { saved = localStorage.getItem(store); } catch (e) {}
  var order = fallback === 'ferment' ? [wanted, fallback] : [wanted, saved, fallback];
  var start = order.map(function (k) { return keys.indexOf(k); }).filter(function (i) { return i >= 0; })[0];
  root.classList.add('staged');
  show(start || 0);

  tabs.forEach(function (t, j) { t.addEventListener('click', function (e) { e.preventDefault(); go(j); }); });
  prev.onclick = function () { go(at - 1); };
  next.onclick = function () { go(at + 1); };
})();

(function () {
  var btn = document.getElementById('wake');
  if (!btn || !('wakeLock' in navigator)) return;

  var store = 'wake', lock = null, asking = false, want = true;
  try { want = localStorage.getItem(store) !== 'off'; } catch (e) {}

  function render() {
    btn.hidden = false;
    btn.classList.toggle('on', !!lock);
    btn.textContent = lock ? 'Screen on' : want ? 'Keep screen on' : 'Screen can sleep';
  }
  function acquire() {
    if (!want || lock || asking || document.hidden) return;
    asking = true;
    navigator.wakeLock.request('screen').then(function (l) {
      asking = false;
      if (!want) { l.release(); return; }
      lock = l;
      l.addEventListener('release', function () { lock = null; render(); });
      render();
    }).catch(function () { asking = false; render(); });
  }

  // Safari only grants the lock from a tap, and drops it whenever the page is hidden, so both re-arm it.
  btn.onclick = function () {
    want = !lock;
    try { localStorage.setItem(store, want ? 'on' : 'off'); } catch (e) {}
    if (lock) lock.release(); else acquire();
    render();
  };
  document.addEventListener('pointerdown', acquire);
  document.addEventListener('visibilitychange', acquire);
  render();
  acquire();
})();
