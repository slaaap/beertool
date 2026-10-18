(function () {
  var f = document.getElementById('settings-form');
  if (!f) return;
  function num(name) { var el = f.elements[name]; return el ? parseFloat(el.value) : NaN; }
  function set(id, v) { var el = document.getElementById(id); if (el) el.textContent = isFinite(v) ? v.toFixed(1) : '-'; }
  function update() {
    var post = num('preBoilVolumeL') - num('boilOffL');
    set('d-post', post);
    set('d-ferm', post - num('kettleRetentionL'));
  }
  f.addEventListener('input', update);
  update();
})();

(function () {
  window.rowAdd = function (key) {
    var body = document.getElementById(key + '-body');
    body.appendChild(document.querySelector('#' + key + '-proto tr').cloneNode(true));
    renumberRows(body.rows);
  };
  window.rowRemove = function (btn) {
    var body = btn.closest('tbody');
    btn.closest('tr').remove();
    renumberRows(body.rows);
  };
})();
