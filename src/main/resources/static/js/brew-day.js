var sound = (function () {
  var audio = null;
  return {
    unlock: function () {
      try {
        if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
        if (audio && audio.resume) audio.resume();
      } catch (e) {}
    },
    beep: function () {
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
  };
})();

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
  var rows = [].slice.call(document.querySelectorAll('#boil-adds [data-remaining]'));
  var left = total, running = false, endAt = 0, timer = null, fired = {}, late = {};

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    try { localStorage.setItem(key, JSON.stringify({ running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }
  function clearSaved() { try { localStorage.removeItem(key); } catch (e) {} }

  function render() {
    if (left < 0) left = 0;
    clock.textContent = Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('boil', running ? clock.textContent : '');
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
    if (due) sound.beep();
  }

  function tick() {
    if (!running) return;
    left = Math.round((endAt - Date.now()) / 1000);
    if (left <= 0) left = 0;
    fireDue();
    render();
    if (left === 0) { running = false; clearInterval(timer); timer = null; btn.textContent = 'Start'; save(); }
  }
  function start() {
    sound.unlock();
    endAt = Date.now() + left * 1000;
    running = true; save();
    timer = setInterval(tick, 500);
    btn.textContent = 'Pause';
  }
  function pause() {
    clearInterval(timer); timer = null;
    running = false; save();
    btn.textContent = 'Start';
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

  btn.onclick = function () { running ? pause() : start(); };
  document.getElementById('boil-reset').onclick = function () {
    clearInterval(timer); timer = null; running = false;
    left = total; fired = {}; late = {}; clearSaved();
    btn.textContent = 'Start'; render();
  };
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
  var rows = [].slice.call(document.querySelectorAll('table.mash-plan tr[data-min]'));
  var steps = rows.map(function (r) { return { temp: r.getAttribute('data-temp'), min: parseInt(r.getAttribute('data-min'), 10) }; });
  var step = 0, left = steps[0].min * 60, running = false, endAt = 0, timer = null;

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    try { localStorage.setItem(key, JSON.stringify({ step: step, running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }

  function render() {
    var done = step >= steps.length, fresh = !done && left === steps[step].min * 60;
    rows.forEach(function (r, i) {
      r.classList.toggle('now', i === step);
      r.classList.toggle('done', i < step);
    });
    btn.disabled = done;
    document.getElementById('mash-next').disabled = done;
    btn.textContent = running ? 'Pause' : fresh || done ? 'Start' : 'Resume';
    clock.textContent = done ? 'Done' : Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('mash', running ? clock.textContent : '');
    status.textContent =
      done ? 'Mash done. Time to sparge.' :
      running ? 'Step ' + (step + 1) + ' · holding ' + steps[step].temp + ' °C' :
      !fresh ? 'Paused at step ' + (step + 1) + '.' :
      step > 0 ? 'Step ' + step + ' done. Raise the mash to ' + steps[step].temp + ' °C, then start.' :
      'Bring the mash to ' + steps[0].temp + ' °C, then start.';
  }

  // Each rest waits for the brewer to reach its temperature, so a finished step never starts the next one.
  function finishStep() {
    clearInterval(timer); timer = null; running = false;
    step++;
    left = step < steps.length ? steps[step].min * 60 : 0;
    save(); render();
  }

  function tick() {
    if (!running) return;
    left = Math.round((endAt - Date.now()) / 1000);
    if (left <= 0) { sound.beep(); finishStep(); return; }
    render();
  }

  try {
    var s = JSON.parse(localStorage.getItem(key));
    if (s && s.step != null) {
      step = Math.min(s.step, steps.length);
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

  btn.onclick = function () {
    if (running) {
      clearInterval(timer); timer = null; running = false;
    } else {
      sound.unlock();
      endAt = Date.now() + left * 1000; running = true;
      timer = setInterval(tick, 500);
    }
    save(); render();
  };
  document.getElementById('mash-next').onclick = finishStep;
  document.getElementById('mash-reset').onclick = function () {
    clearInterval(timer); timer = null; running = false;
    step = 0; left = steps[0].min * 60;
    try { localStorage.removeItem(key); } catch (e) {}
    render();
  };
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
  [gt, ratio, ret].forEach(function (e) { e.addEventListener('input', update); });
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
    tabs.forEach(function (t, j) { t.classList.toggle('active', j === i); });
    prev.hidden = i === 0;
    next.hidden = i === keys.length - 1;
    if (i > 0) prev.textContent = '◀ ' + labels[i - 1];
    if (i < keys.length - 1) next.textContent = labels[i + 1] + ' ▶';
    try { localStorage.setItem(store, keys[i]); } catch (e) {}
  }
  function go(i) { show(i); window.scrollTo(0, 0); history.replaceState(null, '', '#' + keys[i]); }

  // A stage named in the link wins, so the water form can send the brewer back to #water.
  var wanted = location.hash.slice(1), saved = null;
  try { saved = localStorage.getItem(store); } catch (e) {}
  var start = [wanted, saved, root.getAttribute('data-start')].map(function (k) { return keys.indexOf(k); }).filter(function (i) { return i >= 0; })[0];
  root.classList.add('staged');
  show(start || 0);

  tabs.forEach(function (t, j) { t.addEventListener('click', function (e) { e.preventDefault(); go(j); }); });
  prev.onclick = function () { go(at - 1); };
  next.onclick = function () { go(at + 1); };
})();

(function () {
  var btn = document.getElementById('wake');
  if (!btn || !('wakeLock' in navigator)) return;

  var store = 'wake', lock = null, want = true;
  try { want = localStorage.getItem(store) !== 'off'; } catch (e) {}

  function render() {
    btn.hidden = false;
    btn.classList.toggle('on', !!lock);
    btn.textContent = lock ? 'Screen on' : want ? 'Tap to keep screen on' : 'Screen may sleep';
  }
  function acquire() {
    if (!want || lock || document.hidden) return;
    navigator.wakeLock.request('screen').then(function (l) {
      lock = l;
      l.addEventListener('release', function () { lock = null; render(); });
      render();
    }).catch(render);
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
