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

  window.addHop = function (key) {
    var stage = document.getElementById(key);
    var row = document.querySelector('#' + key + '-proto tr').cloneNode(true);
    stage.querySelector('tbody').appendChild(row);
    stage.hidden = false;
    renumberHops();
    syncRow(row);
    row.querySelector('input[type=text]').focus();
  };

  function hideWhenEmpty(stage) {
    if (!stage.querySelector('tbody tr')) stage.hidden = true;
  }

  function renumber(body) {
    Array.prototype.forEach.call(body.rows, function (row, i) {
      renumberRow(row, i);
    });
  }

  // Hop rows are numbered across every stage, since they post as one list.
  function renumberHops() {
    document.querySelectorAll('#hop-sections tbody tr').forEach(renumberRow);
  }

  function renumberRow(row, i) {
    row.querySelectorAll('[name]').forEach(function (input) {
      input.name = input.name.replace(/^([A-Za-z]+)\.\d+\./, '$1.' + i + '.');
    });
  }

  window.removeRow = function (button) {
    var row = button.closest('tr');
    var body = row.parentNode;
    var stage = row.closest('.hop-stage');
    row.remove();
    if (stage) {
      renumberHops();
      hideWhenEmpty(stage);
    } else {
      renumber(body);
    }
    recalc();
  };

  var boil = form.querySelector('input[name="boilTimeMin"]');

  function boilLimit() {
    if (!boil || boil.value === '') return NaN;
    return Number(boil.value);
  }

  function capAtBoil(input, capped) {
    var limit = capped ? boilLimit() : NaN;
    if (isNaN(limit)) {
      input.removeAttribute('max');
      return;
    }
    input.max = limit;
    if (input.value !== '' && Number(input.value) > limit) input.value = limit;
  }

  function syncHopRow(row, stage) {
    var time = row.querySelector('input[name$=".boilTimeMin"]');
    if (time && !time.hidden) capAtBoil(time, stage.dataset.inBoil === '1');
  }

  function syncRow(row) {
    var stage = row.closest('.hop-stage');
    if (stage) return syncHopRow(row, stage);

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
    capAtBoil(time, selected.dataset.inBoil === '1');
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
  document.querySelectorAll('#ferm-proto tr, #extra-proto tr').forEach(function (row) { syncRow(row); });
})();
