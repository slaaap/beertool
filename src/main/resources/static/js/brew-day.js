(function () {
  var clock = document.getElementById('boil-clock');
  if (!clock) return;

  var total = parseInt(clock.getAttribute('data-boil'), 10) * 60;
  var key = 'boil:' + clock.getAttribute('data-key');
  var btn = document.getElementById('boil-start');
  var rows = [].slice.call(document.querySelectorAll('#boil-adds [data-remaining]'));
  var left = total, running = false, endAt = 0, timer = null, audio = null, fired = {};

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    try { localStorage.setItem(key, JSON.stringify({ running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }
  function clearSaved() { try { localStorage.removeItem(key); } catch (e) {} }

  function ensureAudio() {
    try {
      if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
      if (audio && audio.resume) audio.resume();
    } catch (e) {}
  }
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

  function render() {
    if (left < 0) left = 0;
    clock.textContent = Math.floor(left / 60) + ':' + two(left % 60);
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = parseInt(r.getAttribute('data-remaining'), 10);
      r.classList.toggle('now', at === 0 ? left === 0 : (m === at && left > 0));
      r.classList.toggle('done', at === 0 ? false : m < at);
    });
  }

  function fireDue() {
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = parseInt(r.getAttribute('data-remaining'), 10);
      var due = at === 0 ? left === 0 : (m === at && left > 0);
      if (due && !fired[at]) { fired[at] = true; beep(); }
    });
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
    ensureAudio();
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
    left = total; fired = {}; clearSaved();
    btn.textContent = 'Start'; render();
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

  function show(el, v) { el.textContent = isFinite(v) ? v.toFixed(1) : '—'; }
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
