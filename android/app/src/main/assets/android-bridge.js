/* Pont Android : les fichiers générés par l'outil (PDF d'offre, journal…) sont des URL blob:
   qu'une WebView ne sait pas télécharger. On les intercepte et on les transmet à l'application,
   qui les enregistre dans « Téléchargements » et permet de les ouvrir ou de les partager. */
(function () {
  if (window.__lrAndroid) return;
  window.__lrAndroid = true;
  var B = window.LrAndroid;
  if (!B) return;
  var noms = {};
  function estFichier(u) { return typeof u === 'string' && /^(blob|data):/i.test(u); }
  function transmettre(url, nom, action) {
    nom = nom || noms[url] || 'document.pdf';
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      return new Promise(function (ok, ko) {
        var fr = new FileReader();
        fr.onload = function () { ok([b.type, String(fr.result).split(',')[1] || '']); };
        fr.onerror = function () { ko(fr.error); };
        fr.readAsDataURL(b);
      });
    }).then(function (x) {
      B.recevoir(nom, x[0] || 'application/octet-stream', x[1], action);
    }).catch(function (e) { B.erreur(String(e && e.message || e)); });
  }
  var clicOrig = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (estFichier(this.href)) {
      if (this.download) noms[this.href] = this.download;
      transmettre(this.href, this.download, 'enregistrer');
      return;
    }
    return clicOrig.apply(this, arguments);
  };
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
    if (a && estFichier(a.href)) {
      ev.preventDefault();
      if (a.download) noms[a.href] = a.download;
      transmettre(a.href, a.download, a.download ? 'enregistrer' : 'ouvrir');
    }
  }, true);
  var openOrig = window.open;
  window.open = function (url) {
    if (estFichier(url)) {
      transmettre(url, null, 'ouvrir');
      return { closed: false, close: function () {}, focus: function () {} };
    }
    return openOrig.apply(window, arguments);
  };
})();
