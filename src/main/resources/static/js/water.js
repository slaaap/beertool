(function () {
  function editorOf(el) { return el.closest('[data-water-editor]'); }

  // Disabled fields are not posted, which is how a closed panel or an unused mix stays out of the recipe.
  function enable(scope, on) {
    scope.querySelectorAll('input, select').forEach(function (field) { field.disabled = !on; });
  }

  function changed(editor) {
    var form = editor.closest('form');
    if (form) form.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function show(editor, field) {
    var row = editor.querySelector('[data-add="' + field + '"]');
    var folded = editor.closest('details.water-change');
    if (folded) folded.open = true;
    row.hidden = false;
    editor.querySelectorAll('[data-adds="' + field + '"]').forEach(function (b) { b.hidden = true; });
    var menu = editor.querySelector('details[open]');
    if (menu) menu.open = false;
    return row.querySelector('input');
  }

  window.waterOpen = function (button) {
    var editor = editorOf(button);
    var panel = editor.querySelector('.water-panel');
    editor.querySelector('.water-add').hidden = true;
    panel.hidden = false;
    enable(panel, true);
    panel.querySelectorAll('.water-mix[hidden]').forEach(function (mix) { enable(mix, false); });
    changed(editor);
  };

  window.waterClose = function (button) {
    var editor = editorOf(button);
    var panel = editor.querySelector('.water-panel');
    panel.hidden = true;
    enable(panel, false);
    editor.querySelector('.water-add').hidden = false;
    var brewDay = editor.closest('form.brew-water');
    if (brewDay) brewDay.submit();
    else changed(editor);
  };

  window.waterMix = function (button) {
    var editor = editorOf(button);
    editor.querySelectorAll('.water-mix').forEach(function (mix) { mix.hidden = false; enable(mix, true); });
    editor.querySelector('.water-row').classList.remove('solo');
    button.hidden = true;
    changed(editor);
  };

  window.waterUnmix = function (button) {
    var editor = editorOf(button);
    editor.querySelectorAll('.water-mix').forEach(function (mix) { mix.hidden = true; enable(mix, false); });
    editor.querySelector('.water-row').classList.add('solo');
    editor.querySelector('[data-mix]').hidden = false;
    changed(editor);
  };

  window.waterAdd = function (field) {
    var editor = document.querySelector('[data-water-editor]');
    show(editor, field).focus();
  };

  function hide(editor, row) {
    row.querySelector('input').value = '';
    row.hidden = true;
    editor.querySelectorAll('[data-adds="' + row.dataset.add + '"]').forEach(function (b) { b.hidden = false; });
  }

  window.waterDrop = function (button) {
    var editor = editorOf(button);
    hide(editor, button.closest('[data-add]'));
    changed(editor);
  };

  window.waterUseSalts = function (gypsum, chloride) {
    var editor = document.querySelector('[data-water-editor]');
    [['gypsumG', gypsum], ['calciumChlorideG', chloride]].forEach(function (pair) {
      if (parseFloat(pair[1]) > 0) show(editor, pair[0]).value = pair[1];
      else hide(editor, editor.querySelector('[data-add="' + pair[0] + '"]'));
    });
    changed(editor);
  };

  window.waterUseAcid = function (ml) {
    var editor = document.querySelector('[data-water-editor]');
    show(editor, 'acidMl').value = ml;
    changed(editor);
  };
})();
