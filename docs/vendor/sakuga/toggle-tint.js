// Recolore o <sakuga-toggle>, que é desenhado com quadros pintados (as variáveis --sk-* não chegam nele).
// Cada pixel é levado da paleta original do desenho para a sua, mantendo a textura da pintura.
// Uso: carregue depois de toggle.js e chame
//   SakugaToggleTint.set({ fill, shade, track, pri, ink, acc });   // cores em #rrggbb
//   SakugaToggleTint.set(null);                                     // volta ao desenho original
(() => {
  // as cores principais do desenho original
  const OLD = { fill: '#f9f9fd', shade: '#a7a9f7', track: '#bcc2d9', pri: '#8f98f8', ink: '#0f1361', acc: '#ef62aa' };
  const KEYS = Object.keys(OLD);
  const rgb = (h) => { const n = parseInt(String(h).replace('#', ''), 16); return [n >> 16, (n >> 8) & 255, n & 255]; };
  const O = KEYS.map((k) => rgb(OLD[k])).concat([rgb('#5a559f')]);   // o último é o meio-tom entre contorno e lilás
  let D = null, cache = new Map();

  function set(colors) {
    if (!colors) D = null;
    else {
      const N = KEYS.map((k) => rgb(colors[k] || OLD[k]));
      const ink = N[KEYS.indexOf('ink')], pri = N[KEYS.indexOf('pri')];
      N.push(ink.map((v, i) => (v + pri[i]) / 2));
      D = N.map((c, i) => c.map((v, j) => v - O[i][j]));
    }
    cache = new Map();
    for (const el of document.querySelectorAll('sakuga-toggle')) if (el.c && typeof el.cur === 'function') el.draw(el.cur());
  }

  function tint(ctx, frame) {
    if (!D) return;
    let img = cache.get(frame);
    if (!img) {
      img = ctx.getImageData(0, 0, ctx.canvas.width, ctx.canvas.height);
      const p = img.data;
      for (let q = 0; q < p.length; q += 4) {
        if (!p[q + 3]) continue;
        const r = p[q], g = p[q + 1], b = p[q + 2];
        let ws = 0, dr = 0, dg = 0, db = 0;
        for (let a = 0; a < O.length; a++) {
          const o = O[a], x = r - o[0], y = g - o[1], z = b - o[2], d = x * x + y * y + z * z + 30, w = 1 / (d * d);
          ws += w; dr += w * D[a][0]; dg += w * D[a][1]; db += w * D[a][2];
        }
        p[q] = r + dr / ws; p[q + 1] = g + dg / ws; p[q + 2] = b + db / ws;
      }
      cache.set(frame, img);
    }
    ctx.putImageData(img, 0, 0);
  }

  const T = customElements.get('sakuga-toggle');
  if (T) {
    const draw = T.prototype.draw;
    T.prototype.draw = function (i) {
      const was = this.shown; this.shown = NaN; draw.call(this, i);
      if (this.shown !== i) { this.shown = was; return; }   // a imagem ainda não carregou
      tint(this.c, i);
    };
  }
  window.SakugaToggleTint = { set };
})();
