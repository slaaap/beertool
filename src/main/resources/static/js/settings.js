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
  function renumber(body) {
    Array.prototype.forEach.call(body.rows, function (row, i) {
      row.querySelectorAll('[name]').forEach(function (inp) {
        inp.name = inp.name.replace(/^([A-Za-z]+)\.\d+\./, '$1.' + i + '.');
      });
    });
  }
  window.rowAdd = function (key) {
    var body = document.getElementById(key + '-body');
    body.appendChild(document.querySelector('#' + key + '-proto tr').cloneNode(true));
    renumber(body);
  };
  window.rowRemove = function (btn) {
    var body = btn.closest('tbody');
    btn.closest('tr').remove();
    renumber(body);
  };
})();
