(function () {
  var form = document.getElementById('recipe-form');
  if (!form) return;

  var debounce;

  window.recalc = function () {
    clearTimeout(debounce);
    debounce = setTimeout(function () {
      fetch('/recipes/calculate', { method: 'POST', body: new FormData(form) })
        .then(function (r) { return r.text(); })
        .then(function (html) { document.getElementById('stats').innerHTML = html; });
    }, 400);
  };

  window.addRow = function (bodyId, protoSel) {
    var row = document.querySelector(protoSel).cloneNode(true);
    var body = document.getElementById(bodyId);
    body.appendChild(row);
    renumber(body);
    syncRow(row);
  };

  function renumber(body) {
    Array.prototype.forEach.call(body.rows, function (row, i) {
      Array.prototype.forEach.call(row.querySelectorAll('[name]'), function (input) {
        input.name = input.name.replace(/^([A-Za-z]+)\.\d+\./, '$1.' + i + '.');
      });
    });
  }

  window.removeRow = function (button) {
    var row = button.closest('tr');
    var body = row.parentNode;
    row.remove();
    renumber(body);
    recalc();
  };

  var boil = form.querySelector('input[name="boilTimeMin"]');

  function syncRow(row) {
    var usage = row.querySelector('select[name$=".usage"]');
    if (!usage) return;

    var type = row.querySelector('select[name$=".type"]');
    if (type) {
      var chosen = null;
      Array.prototype.forEach.call(usage.options, function (o) {
        var allowed = !o.dataset.types || o.dataset.types.split(' ').indexOf(type.value) >= 0;
        o.hidden = !allowed;
        o.disabled = !allowed;
        if (allowed && !chosen) chosen = o;
      });
      if (usage.selectedOptions[0] && usage.selectedOptions[0].disabled && chosen) usage.value = chosen.value;
    }

    var time = row.querySelector('input[name$=".boilTimeMin"]');
    if (!time) return;

    var selected = usage.selectedOptions[0];
    var takesTime = selected && selected.dataset.time === '1';
    time.disabled = !takesTime;
    if (!takesTime) {
      time.value = '';
      time.removeAttribute('max');
      return;
    }

    var capped = selected.dataset.inBoil === '1' && boil && boil.value !== '';
    var limit = capped ? Number(boil.value) : NaN;
    if (isNaN(limit)) {
      time.removeAttribute('max');
      return;
    }
    time.max = limit;
    if (time.value !== '' && Number(time.value) > limit) time.value = limit;
  }

  function syncAllRows() {
    form.querySelectorAll('tbody tr').forEach(function (row) { syncRow(row); });
  }

  form.addEventListener('input', recalc);
  form.addEventListener('change', function (e) {
    if (e.target.tagName === 'SELECT') syncRow(e.target.closest('tr'));
  });
  if (boil) boil.addEventListener('input', syncAllRows);
  syncAllRows();
  document.querySelectorAll('#ferm-proto tr, #hop-proto tr, #extra-proto tr').forEach(function (row) { syncRow(row); });
})();
