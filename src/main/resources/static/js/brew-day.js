var sound = (function () {
  var audio = null, ringing = null, stopAt = 0;

  function tone(at, freq) {
    var o = audio.createOscillator(), g = audio.createGain();
    o.type = 'square'; o.frequency.value = freq;
    g.gain.setValueAtTime(0.35, at); g.gain.exponentialRampToValueAtTime(0.001, at + 0.22);
    o.connect(g); g.connect(audio.destination);
    o.start(at); o.stop(at + 0.25);
  }
  function beep() {
    try {
      if (audio && audio.state === 'running') { var t = audio.currentTime; tone(t, 988); tone(t + 0.3, 988); tone(t + 0.6, 1319); }
    } catch (e) {}
    if (navigator.vibrate) navigator.vibrate([300, 100, 300, 100, 300]);
  }
  // Half a second of silence as an 8 kHz, 8-bit WAV.
  function silentWav() {
    var n = 4000, b = new Uint8Array(44 + n), v = new DataView(b.buffer);
    function text(at, t) { for (var i = 0; i < t.length; i++) b[at + i] = t.charCodeAt(i); }
    text(0, 'RIFF'); v.setUint32(4, 36 + n, true); text(8, 'WAVEfmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, 8000, true); v.setUint32(28, 8000, true); v.setUint16(32, 1, true); v.setUint16(34, 8, true);
    text(36, 'data'); v.setUint32(40, n, true); b.fill(128, 44);
    return URL.createObjectURL(new Blob([b], { type: 'audio/wav' }));
  }
  // Some iOS versions ignore the audio session type, but a playing media element still switches the phone to
  // playback, which the silent switch does not mute. It also pauses the brewer's music, so the silent loop only
  // plays while something could ring: a running timer, a brewhouse session or an alarm.
  var keepAlive = null, holders = {}, letGo = null;
  function wanted() { return Object.keys(holders).some(function (k) { return holders[k]; }); }
  function holdPlayback() {
    if (!wanted()) return;
    try {
      if (!keepAlive) { keepAlive = new Audio(silentWav()); keepAlive.loop = true; keepAlive.setAttribute('playsinline', ''); }
      if (keepAlive.paused) keepAlive.play().catch(function () {});
    } catch (e) {}
  }
  // Letting go waits a moment, so a timer handing over to its own alarm never drops out of playback in between.
  function hold(name, on) {
    holders[name] = !!on;
    clearTimeout(letGo);
    if (wanted()) holdPlayback();
    else if (keepAlive) letGo = setTimeout(function () { if (!wanted()) keepAlive.pause(); }, 3000);
  }
  function unlock() {
    holdPlayback();
    try {
      if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
      if (audio && audio.resume) audio.resume();
    } catch (e) {}
  }

  // iOS treats Web Audio as ambient sound, which the silent switch mutes; an alarm has to be heard regardless.
  try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) {}
  // Browsers only allow sound after a tap, and on a touch screen only the end of the touch counts as one.
  document.addEventListener('touchend', unlock);
  document.addEventListener('click', unlock);
  // A locked screen suspends the sound; a page the brewer already tapped may wake it again without a new tap.
  document.addEventListener('visibilitychange', function () { if (!document.hidden && audio && audio.resume) audio.resume().catch(function () {}); });
  return {
    unlock: unlock,
    ready: function () { return !!audio && audio.state === 'running'; },
    hold: hold,
    // A burner roars, so an alarm repeats until the brewer confirms it, giving up after ten minutes.
    ring: function () {
      stopAt = Date.now() + 10 * 60 * 1000;
      hold('alarm', true);
      if (ringing) return;
      beep();
      ringing = setInterval(function () { if (Date.now() > stopAt) sound.hush(); else beep(); }, 2000);
    },
    hush: function () { clearInterval(ringing); ringing = null; hold('alarm', false); }
  };
})();

// Something the brewer must do now: it covers the page and rings until they confirm it.
var alerts = (function () {
  var box = null, queue = [], returnTo = null, open = false;

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  // The rest of the page is inert while an alert is up, so focus and taps cannot slip behind it.
  function setInert(on) {
    [].forEach.call(document.body.children, function (c) { if (c !== box) c.inert = on; });
  }
  function close() {
    if (open) {
      open = false; box.hidden = true; setInert(false);
      if (returnTo && returnTo.focus) returnTo.focus();
    }
    sound.hush();
  }
  function render() {
    if (!queue.length) { close(); return; }
    if (!box) {
      box = el('div', 'bd-alert');
      box.setAttribute('role', 'alertdialog'); box.setAttribute('aria-modal', 'true');
      box.setAttribute('aria-labelledby', 'bd-alert-title'); box.setAttribute('aria-describedby', 'bd-alert-text');
      // Escape only puts an alert aside, so it never sends a command the brewer didn't tap.
      box.addEventListener('keydown', function (e) { if (e.key === 'Escape') box.querySelector('.bd-alert-later, .bd-alert-ok').click(); });
      document.body.appendChild(box);
    }
    if (!open) returnTo = document.activeElement;
    var a = queue[0], card = el('div', 'bd-alert-card'), text = el('div');
    var title = el('h2', null, a.title);
    title.id = 'bd-alert-title'; text.id = 'bd-alert-text';
    card.appendChild(title);
    a.lines.forEach(function (l) { text.appendChild(el('p', null, l)); });
    card.appendChild(text);
    var ok = el('button', 'btn primary bd-alert-ok', a.button || 'OK');
    ok.type = 'button';
    ok.onclick = function () { queue.shift(); if (a.done) a.done(); render(); };
    card.appendChild(ok);
    // An alert that acts gets a way to put it aside, as the brewer may not be ready to act yet.
    if (a.later) {
      var later = el('button', 'btn bd-alert-later', a.later);
      later.type = 'button';
      later.onclick = function () { queue.shift(); render(); };
      card.appendChild(later);
    }
    if (queue.length > 1) card.appendChild(el('p', 'muted', (queue.length - 1) + ' more after this'));
    box.innerHTML = ''; box.appendChild(card); box.hidden = false; setInert(true); open = true;
    ok.focus();
  }
  return {
    show: function (a) {
      if (a.id && queue.some(function (q) { return q.id === a.id; })) return;
      queue.push(a); sound.ring(); render();
    },
    dismiss: function (id) {
      var before = queue.length;
      queue = queue.filter(function (q) { return q.id !== id; });
      if (queue.length !== before) render();
    }
  };
})();

// A brewhouse report is a few seconds old and its time left is rounded, so only a gap beyond that moves a page clock.
var SNAP_S = 5;

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

// Without a tap since the page opened, alarms can only vibrate, so a running timer asks for one.
function soundPrompt(before) {
  var b = document.createElement('button');
  b.type = 'button'; b.className = 'notice sound-prompt'; b.hidden = true; b.textContent = 'Tap here to turn on alarm sounds';
  b.onclick = function () { sound.unlock(); setTimeout(function () { b.hidden = sound.ready(); }, 300); };
  before.parentNode.insertBefore(b, before);
  return function (needed) { b.hidden = !needed || sound.ready(); };
}

// Rows that act on a tap are reachable and usable from a keyboard too.
function tappable(el) {
  el.tabIndex = 0; el.setAttribute('role', 'button');
  el.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); el.click(); } });
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
  var left = total, running = false, endAt = 0, timer = null, fired = {}, late = {}, added = {}, pending = {};
  var external = false, following = false, waiting = false, boiled = false, lastSeconds = null, stopped = false;
  var prompt = soundPrompt(btn.parentNode);

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function minuteOf(r) { return parseInt(r.getAttribute('data-remaining'), 10); }
  function save() {
    try {
      localStorage.setItem(key, JSON.stringify({ running: running, endAt: endAt, remaining: left, added: Object.keys(added), due: Object.keys(pending), savedAt: Date.now() }));
    } catch (e) {}
  }
  function clearSaved() { try { localStorage.removeItem(key); } catch (e) {} }

  function render() {
    if (left < 0) left = 0;
    clock.textContent = Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('boil', running ? clock.textContent : '');
    sound.hold('boil', running);
    status.textContent = waiting ? 'Starts when the brewhouse reports a boil.' : stopped ? 'The brewhouse stopped the boil.' :
      (external && left > 0 ? 'Timed by the brewhouse. ' : '') +
      (running ? 'Flame-out at ' + clockTime(endAt) : left === 0 ? 'Flame-out. Time to chill.' : left < total ? 'Paused.' : '');
    prompt(running && !external);
    btn.hidden = external || (left === 0 && !running);
    reset.hidden = external;
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = minuteOf(r);
      r.setAttribute('aria-pressed', added[at] ? 'true' : 'false');
      r.classList.toggle('added', !!added[at]);
      r.classList.toggle('now', !added[at] && (at === 0 ? left === 0 : (m === at && left > 0)));
      r.classList.toggle('done', !added[at] && at !== 0 && m < at);
      r.classList.toggle('late', !added[at] && !!late[at]);
    });
  }

  function confirmAdded(at) { added[at] = true; delete late[at]; delete pending[at]; alerts.dismiss('boil-' + at); save(); render(); }

  // A throttled or locked page can skip an addition's minute, so anything now due or past that hasn't sounded sounds late.
  function fireDue() {
    var m = Math.ceil(left / 60), any = false;
    rows.forEach(function (r) {
      var at = minuteOf(r);
      if (fired[at] || added[at] || (at === 0 ? left > 0 : at < m)) return;
      fired[at] = true;
      if (at > m) late[at] = true;
      announce(at); any = true;
    });
    if (any) save();
  }
  function announce(at) {
    at = Number(at);
    pending[at] = true;
    var what = rows.filter(function (x) { return minuteOf(x) === at; }).map(function (x) { return x.querySelector('.what').textContent; });
    alerts.show({
      id: 'boil-' + at, title: at === 0 ? 'Flame-out: add now' : (late[at] ? 'Late: ' : '') + 'Add at ' + at + ' min',
      lines: what, button: 'Added', done: function () { confirmAdded(at); }
    });
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
      (s.added || []).forEach(function (at) { added[at] = true; });
      // An alarm still unconfirmed when the page reloaded rings again as late, unless it is from another day.
      if (Date.now() - (s.savedAt || 0) < 6 * 3600 * 1000) (s.due || []).forEach(function (at) { pending[at] = fired[at] = late[at] = true; });
    }
  } catch (e) {}
  if (running) {
    left = Math.round((endAt - Date.now()) / 1000);
    var m = Math.ceil(left / 60);
    rows.forEach(function (r) {
      var at = minuteOf(r);
      if (left <= 0 || at > m) fired[at] = true;
    });
    if (left <= 0) { left = 0; running = false; save(); }
    else { timer = setInterval(tick, 500); btn.textContent = 'Pause'; }
  }
  Object.keys(pending).forEach(announce);
  render();

  function takeOver() {
    if (external) return;
    external = true;
    clearInterval(timer); timer = null; running = false;
  }
  function silencePast(seconds) {
    var m = Math.ceil(seconds / 60);
    rows.forEach(function (r) { var at = minuteOf(r); if (at > m || seconds <= 0) fired[at] = true; });
  }

  // While the brewhouse runs a brew, it owns this clock: it waits for the boil, then the controller's boil time drives it.
  window.boilClock = {
    wait: function () {
      takeOver();
      following = false; waiting = true; running = false; left = total;
      clearInterval(timer); timer = null;
      render();
    },
    follow: function (seconds, ticking) {
      var fresh = !following;
      takeOver();
      following = true; waiting = false; boiled = true; lastSeconds = seconds;
      if (fresh) silencePast(seconds);
      var want = Math.max(0, Math.round(seconds));
      var shown = running ? Math.round((endAt - Date.now()) / 1000) : left;
      if (fresh || running !== (ticking && want > 0) || Math.abs(want - shown) > SNAP_S) {
        left = want;
        running = ticking && left > 0;
        endAt = Date.now() + left * 1000;
      }
      if (running && !timer) timer = setInterval(tick, 500);
      if (!running && timer) { clearInterval(timer); timer = null; }
      fireDue(); save(); render();
    },
    // A boil that ran out reaches flame-out and its addition; a boil stopped early, or a page opened afterwards, stays quiet.
    finish: function () {
      takeOver();
      stopped = boiled && lastSeconds > 60;
      if (!boiled || stopped) silencePast(0);
      following = false; waiting = false;
      clearInterval(timer); timer = null; running = false; left = stopped ? Math.round(lastSeconds) : 0;
      fireDue(); save(); render();
    },
    // A new run on the brewhouse starts with no addition ticked, as the hidden Reset can't clear them.
    newRun: function () {
      fired = {}; late = {}; added = {}; pending = {}; boiled = stopped = false; lastSeconds = null;
      rows.forEach(function (r) { alerts.dismiss('boil-' + minuteOf(r)); });
      save(); render();
    },
    release: function () {
      if (!external) return;
      external = following = waiting = stopped = false;
      clearInterval(timer); timer = null; running = false;
      btn.textContent = 'Start'; save(); render();
    }
  };

  btn.onclick = function () { running ? pause() : start(); };
  confirmTap(reset, function () {
    clearInterval(timer); timer = null; running = false;
    left = total; fired = {}; late = {}; added = {}; pending = {}; clearSaved();
    rows.forEach(function (r) { alerts.dismiss('boil-' + minuteOf(r)); });
    btn.textContent = 'Start'; render();
  });
  rows.forEach(function (r) {
    tappable(r);
    r.addEventListener('click', function () {
      var at = minuteOf(r);
      if (added[at]) { delete added[at]; save(); render(); } else if (fired[at]) confirmAdded(at);
    });
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden && running) tick(); });
})();

(function () {
  var clock = document.getElementById('whirlpool-clock');
  if (!clock) return;

  var total = parseInt(clock.getAttribute('data-stand'), 10) * 60;
  var temp = clock.getAttribute('data-temp');
  var key = 'whirlpool:' + clock.getAttribute('data-key');
  var btn = document.getElementById('whirlpool-start');
  var reset = document.getElementById('whirlpool-reset');
  var status = document.getElementById('whirlpool-status');
  var left = total, running = false, endAt = 0, timer = null, external = false, waiting = false, lastSeconds = null;

  function two(n) { return (n < 10 ? '0' : '') + n; }
  function save() {
    if (external) return;
    try { localStorage.setItem(key, JSON.stringify({ running: running, endAt: endAt, remaining: left })); } catch (e) {}
  }
  function render() {
    if (left < 0) left = 0;
    clock.textContent = Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('chill', running ? clock.textContent : '');
    sound.hold('whirlpool', running);
    btn.hidden = external || (left === 0 && !running);
    reset.hidden = external;
    btn.textContent = running ? 'Pause' : left < total && left > 0 ? 'Resume' : 'Start';
    status.textContent =
      waiting ? 'Chill to ' + temp + ' °C. The stand starts when the brewhouse reads it.' :
      total === 0 ? 'Chill to ' + temp + ' °C and add these.' :
      left === 0 ? 'Stand done. Chill to pitching temperature.' :
      running ? (external ? 'Timed by the brewhouse. ' : '') + 'Stand ends at ' + clockTime(endAt) :
      left < total ? 'Paused.' : 'Chill to ' + temp + ' °C, add these, then start the stand.';
  }
  function stop() { clearInterval(timer); timer = null; running = false; }
  function done() {
    stop(); left = 0; save(); render();
    alerts.show({ id: 'whirlpool-done', title: 'Whirlpool stand done', lines: ['Chill to pitching temperature.'] });
  }
  function tick() {
    if (!running) return;
    left = Math.round((endAt - Date.now()) / 1000);
    if (left <= 0) { if (external) { left = 0; stop(); render(); } else done(); return; }
    render();
  }

  try {
    var s = JSON.parse(localStorage.getItem(key));
    if (s) {
      if (s.running) { endAt = s.endAt; running = true; left = Math.round((endAt - Date.now()) / 1000); }
      else if (s.remaining != null) left = s.remaining;
    }
  } catch (e) {}
  if (running) { if (left <= 0) { stop(); left = 0; save(); } else timer = setInterval(tick, 500); }
  render();

  function takeOver() {
    if (external) return;
    external = true; stop();
    try { localStorage.removeItem(key); } catch (e) {}
  }

  // While the brewhouse runs a brew with a whirlpool, it owns this clock: it waits through the chill, then times the stand.
  window.whirlpoolClock = {
    wait: function () { takeOver(); waiting = true; stop(); left = total; render(); },
    follow: function (seconds, ticking) {
      var fresh = waiting || lastSeconds == null;
      takeOver(); waiting = false; lastSeconds = seconds;
      var want = Math.max(0, Math.round(seconds));
      var shown = running ? Math.round((endAt - Date.now()) / 1000) : left;
      if (fresh || running !== (ticking && want > 0) || Math.abs(want - shown) > SNAP_S) {
        left = want; running = ticking && want > 0; endAt = Date.now() + left * 1000;
      }
      if (running && !timer) timer = setInterval(tick, 500);
      if (!running && timer) { clearInterval(timer); timer = null; }
      render();
    },
    // A stand that ran out rings; one stopped early, or a page opened afterwards, stays quiet.
    finish: function () {
      takeOver(); waiting = false;
      var ranOut = lastSeconds != null && lastSeconds <= 60;
      lastSeconds = null;
      if (ranOut) done(); else { stop(); left = 0; render(); }
    },
    release: function () {
      if (!external) return;
      external = waiting = false; lastSeconds = null; stop(); left = total; render();
    }
  };

  btn.onclick = function () {
    if (running) { stop(); save(); render(); return; }
    sound.unlock();
    endAt = Date.now() + left * 1000; running = true;
    timer = setInterval(tick, 500);
    save(); render();
  };
  confirmTap(reset, function () {
    stop(); left = total;
    try { localStorage.removeItem(key); } catch (e) {}
    alerts.dismiss('whirlpool-done');
    render();
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
  var step = 0, left = steps[0].min * 60, running = false, endAt = 0, timer = null, external = false, heating = false, waiting = false;
  var prompt = soundPrompt(btn.parentNode);

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
    btn.hidden = next.hidden = external || done;
    reset.hidden = external;
    prompt(running && !external);
    btn.textContent = running ? 'Pause' : fresh || done ? 'Start' : 'Resume';
    clock.textContent = done ? 'Done' : Math.floor(left / 60) + ':' + two(left % 60);
    tabClock('mash', running ? clock.textContent : '');
    sound.hold('mash', running);
    status.textContent =
      done ? 'Mash done. Time to sparge.' :
      waiting ? 'Starts when the grain is in. Timed by the brewhouse.' :
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
    if (left <= 0) {
      var ended = step + 1;
      finishStep();
      alerts.show({
        id: 'mash-step', title: 'Step ' + ended + ' done',
        lines: [step < steps.length ? 'Raise the mash to ' + steps[step].temp + ' °C, then start the next step.' : 'Mash done. Time to sparge.']
      });
      return;
    }
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
  // The brewer's own timing is obsolete once the brewhouse takes over, so it never comes back when the brewhouse lets go.
  function takeOver() {
    if (external) return;
    external = true; stopTicking(); running = false;
    try { localStorage.removeItem(key); } catch (e) {}
  }

  window.mashClock = {
    wait: function () {
      takeOver();
      waiting = true; heating = false; step = 0; left = steps[0].min * 60; running = false; stopTicking();
      render();
    },
    follow: function (index, seconds, ticking) {
      var fresh = !external || waiting;
      takeOver();
      waiting = false;
      var moved = index !== step;
      step = Math.min(index, steps.length);
      heating = seconds == null && step < steps.length;
      var want = step >= steps.length ? 0 : heating ? steps[step].min * 60 : Math.max(0, Math.round(seconds));
      var go = ticking && !heating && want > 0;
      var shown = running ? Math.round((endAt - Date.now()) / 1000) : left;
      if (fresh || moved || running !== go || Math.abs(want - shown) > SNAP_S) {
        left = want; running = go; endAt = Date.now() + left * 1000;
      }
      if (running && !timer) timer = setInterval(tick, 500);
      if (!running) stopTicking();
      // A mash the brewhouse finished stays finished once it lets go, rather than offering a fresh Start.
      if (step >= steps.length) try { localStorage.setItem(key, JSON.stringify({ step: step, running: false, endAt: 0, remaining: 0 })); } catch (e) {}
      render();
    },
    release: function () {
      if (!external) return;
      external = false; heating = false; waiting = false; stopTicking();
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

  // A stage named in the link wins, so the water form can send the brewer back to #water.
  var wanted = location.hash.slice(1), saved = null;
  try { saved = localStorage.getItem(store); } catch (e) {}
  var start = [wanted, saved].map(function (k) { return keys.indexOf(k); }).filter(function (i) { return i >= 0; })[0];
  root.classList.add('staged');
  show(start || 0);

  tabs.forEach(function (t, j) { t.addEventListener('click', function (e) { e.preventDefault(); go(j); }); });

  // The brewhouse moves the brew on by itself, so the page follows it to the stage it reached.
  window.brewStage = function (key) { var i = keys.indexOf(key); if (i >= 0 && i !== at) go(i); };

  // Each stage's readings belong to one form, so a bad value on a hidden stage would block Save without a word.
  var revealing = false;
  root.addEventListener('invalid', function (e) {
    if (revealing) return;
    var i = stages.indexOf(e.target.closest('.stage'));
    if (i < 0 || i === at) return;
    revealing = true; show(i);
    setTimeout(function () { revealing = false; e.target.reportValidity(); }, 0);
  }, true);
  prev.onclick = function () { go(at - 1); };
  next.onclick = function () { go(at + 1); };
})();

// A blank reading means "not measured", so a Save with a stage's fields all blank would quietly keep nothing; offer the plan instead.
(function () {
  var form = document.getElementById('readings');
  if (!form) return;
  var hints = [];

  function plan(i) { return (i.placeholder || '').replace(/^plan\s*/, ''); }
  function hint(row, message, label, fill, submit) {
    var old = row.querySelector('.readings-hint');
    if (old) { old.remove(); hints.splice(hints.indexOf(old), 1); }
    var h = document.createElement('div');
    h.className = 'notice readings-hint';
    var text = document.createElement('p');
    text.textContent = message;
    h.appendChild(text);
    if (label) {
      var use = document.createElement('button');
      use.type = 'button'; use.className = 'btn'; use.textContent = label;
      use.onclick = function () { fill(); form.requestSubmit(submit); };
      h.appendChild(use);
    }
    row.insertBefore(h, submit);
    hints.push(h);
  }
  function fillBlank(inputs) { inputs.forEach(function (i) { if (i.value === '' && plan(i)) i.value = plan(i); }); }

  // Brew day ends with the OG, so finishing without one asks first; a planned OG is only ever saved on purpose,
  // as it would pass for a measured one and the efficiency would just echo the plan.
  form.addEventListener('submit', function (e) {
    if (!e.submitter || e.submitter.id !== 'finish') return;
    var row = e.submitter.closest('.stage-readings');
    var og = row.querySelector('input[name=measuredOg]');
    if (!og || og.value !== '') return;
    e.preventDefault();
    var planned = plan(og);
    if (!planned) { hint(row, 'Enter the OG to finish brew day.', null, null, e.submitter); return; }
    var inputs = [].slice.call(row.querySelectorAll('input[type=number]'));
    hint(row, 'No OG measured.', 'Finish with the planned OG ' + planned, function () { fillBlank(inputs); }, e.submitter);
  });

  // Enter in a field submits through the form's first Save, so the stage is taken from the field the brewer was in.
  form.addEventListener('submit', function (e) {
    var focused = document.activeElement, from = focused && focused.closest && focused.closest('.stage-readings');
    var row = from || (e.submitter && e.submitter.closest('.stage-readings'));
    if (!row) return;
    var submit = row.querySelector('input[type=submit]:not(#finish)');
    var inputs = [].slice.call(row.querySelectorAll('input[type=number]'));
    var volumes = inputs.filter(function (i) { return /VolumeL$/.test(i.name); });
    // Clearing a value saved by mistake is a change worth saving, so only a stage that was blank and still is gets stopped.
    var blank = inputs.every(function (i) { return i.value === '' && i.defaultValue === ''; });
    if (!submit || !volumes.length || !blank) return;
    e.preventDefault();
    // Only volumes: a gravity taken from the plan would pass for a measured OG and skew the ABV.
    hint(row, inputs.length > 1 ? 'Enter values' : 'Enter a value', 'Save the planned volume', function () { fillBlank(volumes); }, submit);
  });
  form.ownerDocument.addEventListener('input', function (e) {
    if (e.target.form === form) hints.forEach(function (h) { h.hidden = true; });
  });
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
