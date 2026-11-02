// Перехват скачивания blob:/data: ссылок внутри приложения ARGEDA AI.
(function(){
  if (window.__argedaHook) return; window.__argedaHook = true;
  function send(blob, name){
    var r = new FileReader();
    r.onload = function(){ var s = r.result; var i = s.indexOf(','); ArgedaApp.saveFile(s.slice(i+1), blob.type || 'application/octet-stream', name || 'fayl'); };
    r.readAsDataURL(blob);
  }
  function grab(href, name){
    fetch(href).then(function(x){ return x.blob(); }).then(function(b){ send(b, name); }).catch(function(e){ ArgedaApp.saveFailed(String(e)); });
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    var h = this.getAttribute('href') || '';
    if (this.hasAttribute('download') && (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0)) { grab(h, this.getAttribute('download')); return; }
    return origClick.apply(this, arguments);
  };
  function own(h){ return h.indexOf('/') === 0 && h.indexOf('//') !== 0 || h.indexOf(location.origin + '/') === 0; }
  document.addEventListener('click', function(ev){
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var h = a.getAttribute('href') || '';
    if (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0) { ev.preventDefault(); grab(h, a.getAttribute('download')); }
    else if (own(h)) { ev.preventDefault(); ArgedaApp.skachat(new URL(h, location.href).href, a.getAttribute('download') || ''); }
  }, true);
  HTMLAnchorElement.prototype.click = (function(prev){ return function(){
    var h = this.getAttribute('href') || '';
    if (this.hasAttribute('download') && own(h)) { ArgedaApp.skachat(new URL(h, location.href).href, this.getAttribute('download') || ''); return; }
    return prev.apply(this, arguments);
  }; })(HTMLAnchorElement.prototype.click);
})();

// Плеер сайта играет сам (звук не трогаем); приложение держит его живым в фоне и даёт кнопки в шторке и на экране блокировки.
(function(){
  function isGlobal(a){ return a && a.tagName === 'AUDIO' && a.hasAttribute('hidden') && !a.controls; }
  function title(){
    var au = document.querySelector('audio[hidden]'); if (au && au.dataset && au.dataset.nazvanie) return au.dataset.nazvanie;
    var el = document.querySelector('.gp-nazvanie, .gp-trek, [class*="gp-"] [class*="nazv"]');
    var t = el && el.textContent ? el.textContent.trim() : '';
    return t || (document.title || 'ARGEDA AI').replace(/\s*[—|].*$/, '');
  }
  document.addEventListener('play', function(e){
    var a = e.target; if (!isGlobal(a)) return;
    ArgedaApp.pleerPlay(a.currentSrc || a.src || '', title(), Math.floor((a.currentTime || 0) * 1000), Math.floor((isFinite(a.duration) ? a.duration : 0) * 1000));
  }, true);
  document.addEventListener('pause', function(e){
    var a = e.target; if (!isGlobal(a)) return;
    ArgedaApp.pleerPause(Math.floor((a.currentTime || 0) * 1000));
  }, true);
  document.addEventListener('seeked', function(e){
    var a = e.target; if (!isGlobal(a)) return;
    ArgedaApp.pleerSeek(Math.floor((a.currentTime || 0) * 1000));
  }, true);
  window.__argedaPleerMirror = function(what, ms){
    var a = document.querySelector('audio[hidden]'); if (!a) return;
    if (what === 'pause') a.pause();
    else if (what === 'play') a.play().catch(function(){});
    else if (what === 'seek') a.currentTime = ms / 1000;
  };
  // «Поделиться» и «Сохранить» через приложение
  navigator.share = function(d){
    d = d || {};
    if (d.files && d.files.length) {
      var f = d.files[0];
      return new Promise(function(res, rej){
        var r = new FileReader();
        r.onload = function(){ var s = r.result; ArgedaApp.shareFile(s.slice(s.indexOf(',') + 1), f.type || '', f.name || 'fayl', (d.text || d.title || '')); res(); };
        r.onerror = function(){ rej(new Error('read')); };
        r.readAsDataURL(f);
      });
    }
    ArgedaApp.share(d.title || '', d.text || '', d.url || ''); return Promise.resolve();
  };
  navigator.canShare = function(){ return true; };
  window.print = function(){ ArgedaApp.print(); };
})();
