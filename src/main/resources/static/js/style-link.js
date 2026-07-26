(function () {
    var input = document.querySelector('input[name="style"]');
    var link = document.getElementById('style-bjcp');
    var data = document.getElementById('bjcp-map');
    if (!input || !link || !data) return;

    var map = JSON.parse(data.textContent);

    function update() {
        var url = map[input.value.trim()];
        if (url) {
            link.href = url;
            link.hidden = false;
        } else {
            link.hidden = true;
        }
    }

    input.addEventListener('input', update);
    update();
})();
