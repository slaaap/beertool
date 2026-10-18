(function () {
  var note = document.getElementById('saved-note');
  if (note) setTimeout(function () { note.classList.add('faded'); }, 1600);
})();

// Rows post as one indexed list, so their names are renumbered whenever a row comes or goes.
window.renumberRows = function (rows) {
  Array.prototype.forEach.call(rows, function (row, i) {
    row.querySelectorAll('[name]').forEach(function (input) {
      input.name = input.name.replace(/^([A-Za-z]+)\.\d+\./, '$1.' + i + '.');
    });
  });
};
