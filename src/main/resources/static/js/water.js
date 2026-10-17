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
    button.hidden = true;
    changed(editor);
  };

  window.waterUnmix = function (button) {
    var editor = editorOf(button);
    editor.querySelectorAll('.water-mix').forEach(function (mix) { mix.hidden = true; enable(mix, false); });
    editor.querySelector('[data-mix]').hidden = false;
    changed(editor);
  };

  window.waterAdd = function (field) {
    var editor = document.querySelector('[data-water-editor]');
    show(editor, field).focus();
  };

  window.waterDrop = function (button) {
    var editor = editorOf(button);
    var row = button.closest('[data-add]');
    row.querySelector('input').value = '';
    row.hidden = true;
    editor.querySelectorAll('[data-adds="' + row.dataset.add + '"]').forEach(function (b) { b.hidden = false; });
    changed(editor);
  };

  window.waterUseLactic = function (ml) {
    var editor = document.querySelector('[data-water-editor]');
    show(editor, 'lacticMl').value = ml;
    changed(editor);
  };
})();
