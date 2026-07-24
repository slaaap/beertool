(function () {
  var f = document.getElementById('settings-form');
  if (!f) return;
  function num(name) { var el = f.elements[name]; return el ? parseFloat(el.value) : NaN; }
  function set(id, v) { var el = document.getElementById(id); if (el) el.textContent = isFinite(v) ? v.toFixed(1) : '—'; }
  function update() {
    var post = num('preBoilVolumeL') - num('boilOffL');
    set('d-post', post);
    set('d-ferm', post - num('kettleRetentionL'));
  }
  f.addEventListener('input', update);
  update();
})();

(function () {
  var body = document.getElementById('sm-body');
  if (!body) return;
  function renumber() {
    Array.prototype.forEach.call(body.rows, function (row, i) {
      row.querySelectorAll('[name]').forEach(function (inp) {
        inp.name = inp.name.replace(/^mashSteps\.\d+\./, 'mashSteps.' + i + '.');
      });
    });
  }
  window.smAdd = function () { body.appendChild(document.querySelector('#sm-proto tr').cloneNode(true)); renumber(); };
  window.smRemove = function (btn) { btn.closest('tr').remove(); renumber(); };
})();
