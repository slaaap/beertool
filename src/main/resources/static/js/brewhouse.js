(function () {
  var root = document.getElementById('bh');
  if (!root) return;

  var base = root.getAttribute('data-base');
  var batchId = parseInt(root.getAttribute('data-batch'), 10);
  var PHASES = {
    idle: 'Idle', strike: 'Heating strike water', dough_in: 'Strike water ready: add the grain',
    mash: 'Mashing', mashed: 'Mash done: lauter or lift the bag', boil_heat: 'Heating to the boil',
    boil: 'Boiling', paused: 'Paused', manual: 'Under manual control', done: 'Done', fault: 'Stopped by a fault'
  };

  function $(id) { return document.getElementById(id); }
  function num(v, places) { return v == null ? '-' : v.toFixed(places); }
  function show(el, on) { if (el) el.hidden = !on; }

  var status = null, unreachable = false, firstNotice = true, busy = false, lastReadingAt = null, readings = [], fetching = false, lastPhase = null, lastActive = null;
  var INTERRUPTS = { paused: true, manual: true, fault: true };
  var STAGE_TAB = { strike: 'water', dough_in: 'mash', mash: 'mash', mashed: 'mash', boil_heat: 'boil', boil: 'boil' };
  var BEFORE_MASH = { strike: true, dough_in: true };
  var BEFORE_BOIL = { strike: true, dough_in: true, mash: true, mashed: true, boil_heat: true };
  var AFTER_MASH = { mashed: true, boil_heat: true, boil: true, done: true };
  var boiledKey = 'bh-boiled:' + batchId;

  function callsForYou(s) {
    if (s.phase === 'dough_in') return { title: 'Strike water is ready', lines: ['Add the grain, then press Grain is in.'] };
    if (s.phase === 'mashed') return { title: 'Mash done', lines: ['Lauter or lift the bag, then press Start boil.'] };
    if (s.phase === 'fault') return { title: 'Heating stopped', lines: [(s.fault || 'The brewhouse reported a fault') + '.', 'Fix it, then press Resume or Stop.'] };
    return null;
  }

  function request(method, path, body) {
    var opts = { method: method, credentials: 'same-origin', headers: { 'Accept': 'application/json' } };
    if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(base + path, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) throw new Error(j.error || 'beertool answered ' + r.status + '.');
        return j;
      });
    });
  }

  function mySession() {
    var s = status && status.report && status.report.session;
    return s && s.batchId === batchId ? s : null;
  }

  function renderConnection() {
    var state = $('bh-state'), report = status && status.report;
    var busy = report && report.session && report.session.phase !== 'idle' && report.session.phase !== 'done';
    var why = status && status.online && report && !report.ready && !busy && report.notReady;
    $('bh-why').textContent = why || '';
    show($('bh-why'), !!why);
    if (!status || unreachable) { state.textContent = 'Brewhouse: can’t reach beertool'; return; }
    if (status.online) {
      state.textContent = 'Brewhouse online';
    } else {
      state.textContent = 'Brewhouse offline' + (status.lastSeenAt ? ', last seen ' + new Date(status.lastSeenAt).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' }) : '');
    }
    root.classList.toggle('online', !!status.online);
  }

  function renderStart() {
    var other = status && status.report && status.report.session;
    var runningElsewhere = other && other.batchId !== batchId && other.phase !== 'idle' && other.phase !== 'done';
    var session = mySession();
    var startable = !session || session.phase === 'idle';
    show($('bh-start'), startable);
    var btn = $('bh-start-btn');
    if (!btn) return;
    var ready = !!(status && status.online && status.report && status.report.ready);
    btn.disabled = !ready || busy;
    btn.textContent = ready ? 'Start on brewhouse' : runningElsewhere ? 'Brewhouse busy with another brew' : status && status.online ? 'Brewhouse not ready' : 'Start on brewhouse';
    if (!ready) { show($('bh-preview'), false); btn.hidden = false; }
  }

  function renderLive() {
    var s = mySession(), live = !!s && s.phase !== 'idle';
    show($('bh-live'), live);
    if (!live) return;
    $('bh-phase').textContent = (PHASES[s.phase] || s.phase) + (s.stage ? ' · ' + s.stage : '');
    $('bh-temp').textContent = num(s.tempC, 1);
    $('bh-target').textContent = s.targetC == null ? '' : '→ ' + num(s.targetC, 1) + ' °C';
    tile('bh-heat', s.heatPct == null ? null : String(s.heatPct));
    tile('bh-step-left', s.stepLeftMin == null ? null : num(s.stepLeftMin, 0));
    tile('bh-eta', s.etaMin == null ? null : clockTime(Date.now() + s.etaMin * 60000));
    tile('bh-boil-left', s.boilLeftMin == null ? null : num(s.boilLeftMin, 0));
    [].forEach.call(root.querySelectorAll('[data-command]'), function (b) {
      b.hidden = b.getAttribute('data-phases').split(' ').indexOf(s.phase) < 0;
      b.disabled = busy || unreachable || !status.online;
    });
  }

  // A figure that does not apply to the phase hides its tile rather than showing a dash.
  function tile(id, value) {
    var el = $(id);
    el.textContent = value == null ? '-' : value;
    el.closest('.stat').hidden = value == null;
  }

  function renderBanners() {
    var s = mySession();
    var fault = s && s.phase === 'fault';
    show($('bh-fault'), fault);
    if (fault) $('bh-fault').textContent = 'Heating stopped: ' + (s.fault || 'the brewhouse reported a fault') + '. Fix it, then press Resume to carry on or Stop to end the brew.';
    var manual = s && s.phase === 'manual';
    show($('bh-manual'), manual);
    if (manual) $('bh-note').textContent = (s.note || 'Someone took over the heat by hand') + '. Timers keep running; press Resume to hand the heat back.';
  }

  // Both clocks are on beertool's time, so neither the phone's clock nor the controller's skews the age.
  function reportAgeS() {
    if (!status || !status.serverNow || !status.lastSeenAt) return 0;
    return Math.max(0, (Date.parse(status.serverNow) - Date.parse(status.lastSeenAt)) / 1000);
  }

  // Paused, manual and a fault interrupt a stage that can be resumed, so the clocks stay on the stage that was running.
  function stageOf(s) {
    if (!INTERRUPTS[s.phase]) return s.phase;
    return lastActive || (s.boilLeftMin != null ? 'boil' : s.step != null ? 'mash' : null);
  }

  function boiled() { try { return localStorage.getItem(boiledKey) === '1'; } catch (e) { return false; } }

  // From the start until it is done, the brewhouse owns both clocks, so their own buttons are hidden rather than out of step.
  function driveClocks() {
    var s = mySession(), stage = s && stageOf(s), ticking = !!s && s.phase !== 'paused' && s.phase !== 'fault';
    function seconds(min) { return min == null ? null : min * 60 - (ticking ? reportAgeS() : 0); }
    if (stage === 'boil') { try { localStorage.setItem(boiledKey, '1'); } catch (e) {} }
    if (window.boilClock) {
      if (stage === 'boil' && s.boilLeftMin != null) window.boilClock.follow(seconds(s.boilLeftMin), ticking);
      else if (BEFORE_BOIL[stage]) window.boilClock.wait();
      else if (stage === 'done' && boiled()) window.boilClock.finish();
      else window.boilClock.release();
    }
    if (window.mashClock) {
      if (stage === 'mash' && s.step != null) window.mashClock.follow(s.step, seconds(s.stepLeftMin), ticking);
      else if (BEFORE_MASH[stage]) window.mashClock.wait();
      else if (AFTER_MASH[stage]) window.mashClock.follow(Infinity, 0, false);
      else window.mashClock.release();
    }
  }

  // A page opened while the brewhouse waits on the brewer asks too; only a real move to another stage changes the tab, not a pause and resume.
  function notice() {
    var s = mySession(), phase = s ? s.phase : null;
    if (phase !== lastPhase || firstNotice) {
      if (lastPhase) alerts.dismiss('bh-' + lastPhase);
      var call = s && callsForYou(s);
      if (call) { call.id = 'bh-' + phase; alerts.show(call); }
      if (lastPhase && STAGE_TAB[phase] && STAGE_TAB[phase] !== STAGE_TAB[lastActive] && window.brewStage) window.brewStage(STAGE_TAB[phase]);
      if (phase === 'strike') {
        try { localStorage.removeItem(boiledKey); } catch (e) {}
        if (window.boilClock) window.boilClock.newRun();
      }
    }
    if (phase && !INTERRUPTS[phase]) lastActive = phase;
    lastPhase = phase; firstNotice = false;
  }

  function renderSound() {
    var s = mySession();
    show($('bh-sound'), !!s && s.phase !== 'idle' && s.phase !== 'done' && !sound.ready());
  }

  // The widget is the controller's own drawing of its devices; beertool only frames it and hands it the report.
  var frame = $('bh-widget'), widgetAt = null, frameReady = false;
  var TOKENS = ['--ground', '--surface', '--ink', '--muted', '--hairline', '--accent', '--accent-soft', '--danger', '--danger-soft', '--good',
    '--font-display', '--font-body', '--font-mono'];

  function theme() {
    var html = document.documentElement, css = getComputedStyle(html), tokens = {}, set = html.getAttribute('data-theme');
    TOKENS.forEach(function (t) { tokens[t] = css.getPropertyValue(t).trim(); });
    return { dark: set ? set === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches, tokens: tokens };
  }

  function renderWidget() {
    var at = status && status.widgetAt;
    if (!at) { if (!status) postWidget(); else { show(frame, false); widgetAt = null; frame.removeAttribute('src'); } return; }
    if (at !== widgetAt) {
      widgetAt = at; frameReady = false;
      frame.style.height = ''; frame.style.aspectRatio = '4 / 3';
      frame.src = base + '/widget?v=' + encodeURIComponent(at);
      show(frame, true);
    }
    postWidget();
  }

  // Its origin is opaque, so '*' is the only target that reaches it; nothing in the message is secret from the brewhouse that sent it.
  function postWidget() {
    if (!frameReady || !frame.contentWindow) return;
    var report = status && status.report;
    frame.contentWindow.postMessage({
      type: 'brewhouse', data: (report && report.widget) || null, session: (report && report.session) || null,
      online: !!(status && status.online), theme: theme()
    }, '*');
  }

  frame.addEventListener('load', function () { if (frame.getAttribute('src')) { frameReady = true; postWidget(); } });
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (e.source !== frame.contentWindow || !d || d.type !== 'brewhouse-size' || typeof d.height !== 'number' || !isFinite(d.height)) return;
    frame.style.aspectRatio = '';
    frame.style.height = Math.min(1200, Math.max(120, Math.round(d.height))) + 'px';
  });

  function render() {
    renderWidget();
    renderConnection();
    renderStart();
    renderBanners();
    renderLive();
    renderSound();
    driveClocks();
  }

  function error(text) {
    var el = $('bh-error');
    el.textContent = text || '';
    show(el, !!text);
  }

  // A command is only queued here; the brewhouse decides, so its answer is fetched until it arrives or the command expires.
  function send(command) {
    busy = true; error(null); render();
    request('POST', '/commands', command).then(function (c) {
      var deadline = Date.now() + 70000;
      (function check() {
        request('GET', '/commands/' + encodeURIComponent(c.id)).then(function (now) {
          if (now.status === 'pending' && Date.now() < deadline) { setTimeout(check, 1000); return; }
          busy = false;
          if (now.status === 'refused') error('The brewhouse refused: ' + (now.error || 'no reason given') + '.');
          else if (now.status === 'expired' || now.status === 'pending') error('The brewhouse didn’t pick that up in time. Check its connection.');
          poll();
        }).catch(function (e) { busy = false; error(e.message); render(); });
      })();
    }).catch(function (e) { busy = false; error(e.message); render(); });
  }

  [].forEach.call(root.querySelectorAll('[data-command]'), function (b) {
    var command = { type: b.getAttribute('data-command') };
    if (command.type === 'stop') confirmTap(b, function () { send(command); });
    else b.addEventListener('click', function () { sound.unlock(); send(command); });
  });
  $('bh-sound').addEventListener('click', function () { sound.unlock(); setTimeout(renderSound, 300); });
  if ($('bh-start-btn')) {
    $('bh-start-btn').addEventListener('click', function () {
      copy('strike-water', 'bh-pv-water'); copy('strike-temp', 'bh-pv-strike');
      show($('bh-preview'), true); $('bh-start-btn').hidden = true;
    });
    $('bh-cancel').addEventListener('click', function () { show($('bh-preview'), false); $('bh-start-btn').hidden = false; });
    $('bh-confirm').addEventListener('click', function () {
      sound.unlock();
      show($('bh-preview'), false); $('bh-start-btn').hidden = false;
      send({ type: 'start', batchId: batchId, grainTempC: input('mw-grain-temp'), mashThicknessLPerKg: input('mw-ratio') });
    });
  }

  // The preview and the start follow what the brewer adjusted in the mash-water guide, as the schedule is worked out from the same inputs.
  function copy(from, to) {
    var a = $(from), b = $(to);
    if (a && b && a.textContent !== '-') b.textContent = a.textContent;
  }
  function input(id) {
    var v = $(id) ? parseFloat($(id).value) : NaN;
    return isFinite(v) ? v : null;
  }

  function drawChart() {
    var box = $('bh-chart');
    if (readings.length < 2) { show(box, false); return; }
    var t0 = Date.parse(readings[0].at), t1 = Date.parse(readings[readings.length - 1].at);
    var temps = [];
    readings.forEach(function (r) { if (r.tempC != null) temps.push(r.tempC); if (r.targetC != null) temps.push(r.targetC); });
    if (!temps.length) { show(box, false); return; }
    var lo = Math.floor(Math.min.apply(null, temps) - 1), hi = Math.ceil(Math.max.apply(null, temps) + 1);
    var W = 300, H = 120;
    function x(r) { return ((Date.parse(r.at) - t0) / Math.max(1, t1 - t0) * W).toFixed(1); }
    function y(v) { return (H - (v - lo) / (hi - lo) * H).toFixed(1); }
    function line(field) {
      var d = '', pen = 'M';
      readings.forEach(function (r) {
        if (r[field] == null) { pen = 'M'; return; }
        d += pen + x(r) + ' ' + y(r[field]) + ' '; pen = 'L';
      });
      return d;
    }
    box.innerHTML =
      '<div class="bh-chart-head"><span class="bh-key temp">Temperature</span><span class="bh-key target">Target</span></div>' +
      '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" role="img" aria-label="Temperature against target over the session">' +
      '<path class="target" d="' + line('targetC') + '"/><path class="temp" d="' + line('tempC') + '"/></svg>' +
      '<div class="bh-chart-axis"><span>' + clockTime(t0) + '</span><span>' + lo + '-' + hi + ' °C</span><span>' + clockTime(t1) + '</span></div>';
    show(box, true);
  }

  // One fetch at a time, so two overlapping requests never append the same readings twice.
  function fetchReadings() {
    if (fetching) return;
    fetching = true;
    var q = lastReadingAt ? '?since=' + encodeURIComponent(lastReadingAt) : '';
    request('GET', '/batches/' + batchId + '/readings' + q).then(function (more) {
      if (!more.length) return;
      readings = readings.concat(more);
      lastReadingAt = more[more.length - 1].at;
      drawChart();
    }).catch(function () {}).then(function () { fetching = false; });
  }

  var polls = 0;
  // A failed poll keeps the last report, so the clocks run on by themselves instead of being handed back over a network blip.
  function poll() {
    return request('GET', '/status').then(function (s) { status = s; unreachable = false; }).catch(function () { unreachable = true; }).then(function () {
      if (unreachable && status) { renderConnection(); renderLive(); return; }
      notice();
      render();
      if (mySession() && polls++ % 5 === 0) fetchReadings();
    });
  }

  fetchReadings();
  poll();
  setInterval(function () { if (!document.hidden) poll(); }, 2000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });
})();
