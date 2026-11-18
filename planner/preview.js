/*
 * Air Pilot Planner: fly-through preview.
 * Replays a plan in a tilted 3D map (satellite imagery + simple 3D buildings) from the drone's camera,
 * or from above, at 1x to 10x speed. No keys needed.
 */
(function () {
  'use strict';
  var V = '4.7.1';
  var JS = ['https://cdnjs.cloudflare.com/ajax/libs/maplibre-gl/' + V + '/maplibre-gl.min.js',
    'https://cdn.jsdelivr.net/npm/maplibre-gl@' + V + '/dist/maplibre-gl.js'];
  var CSS = ['https://cdnjs.cloudflare.com/ajax/libs/maplibre-gl/' + V + '/maplibre-gl.min.css',
    'https://cdn.jsdelivr.net/npm/maplibre-gl@' + V + '/dist/maplibre-gl.css'];
  var ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
  var FT = 0.3048, R = 6378137, D2R = Math.PI / 180;

  var loading = null;
  function loadOne(urls, make) {
    return new Promise(function (ok, fail) {
      var i = 0;
      (function next() {
        if (i >= urls.length) return fail(new Error('Could not load the 3D map (check the internet connection).'));
        var el = make(urls[i++]);
        el.onload = function () { ok(); };
        el.onerror = function () { el.remove(); next(); };
        document.head.appendChild(el);
      })();
    });
  }
  function load() {
    if (window.maplibregl) return Promise.resolve();
    if (!loading) {
      loading = Promise.all([
        loadOne(CSS, function (u) { var l = document.createElement('link'); l.rel = 'stylesheet'; l.href = u; return l; }),
        loadOne(JS, function (u) { var s = document.createElement('script'); s.src = u; return s; })
      ]).catch(function (e) { loading = null; throw e; });
    }
    return loading;
  }

  // ---------- geometry helpers
  function offset(lat, lng, bearing, dist) {
    var b = bearing * D2R;
    return { lat: lat + (dist * Math.cos(b)) / R / D2R, lng: lng + (dist * Math.sin(b)) / (R * Math.cos(lat * D2R)) / D2R };
  }
  function ground(a, b) {
    var dx = (b.lng - a.lng) * D2R * R * Math.cos(a.lat * D2R), dy = (b.lat - a.lat) * D2R * R;
    return Math.sqrt(dx * dx + dy * dy);
  }
  function bearing(a, b) {
    var dx = (b.lng - a.lng) * Math.cos(a.lat * D2R), dy = b.lat - a.lat;
    return (Math.atan2(dx, dy) / D2R + 360) % 360;
  }
  function angLerp(a, b, f) { var d = ((b - a + 540) % 360) - 180; return (a + d * f + 360) % 360; }

  /** Turn the plan into keyframes {t, lat, lng, alt, heading, pitch, wp, label}. */
  function timeline(plan) {
    var w = plan._preview.wps, keys = [], photos = [], t = 0;
    function key(p, label, wp) { keys.push({ t: t, lat: p.lat, lng: p.lng, alt: p.alt, heading: p.heading, pitch: p.pitch, label: label, wp: wp }); }
    var p0 = w[0];
    // take off at the first point
    key({ lat: p0.lat, lng: p0.lng, alt: 0, heading: p0.heading, pitch: 0 }, 'Taking off', 0);
    t += Math.max(3, p0.alt / 3);
    for (var i = 0; i < w.length; i++) {
      var q = w[i], ex = q.extra || {};
      if (i > 0) {
        var a = w[i - 1], sp = (a.extra && a.extra.speed) || plan.speed || 2.5;
        var d = Math.sqrt(Math.pow(ground(a, q), 2) + Math.pow(q.alt - a.alt, 2));
        t += Math.max(1, d / sp);
      }
      var label = 'Point ' + (i + 1);
      key(q, label, i);
      if (q.photo) { photos.push(t + 0.4); t += 2; key(q, label + ' · 📷 photo', i); }
      if (ex.rec === 'start') { t += 1; key(q, label + ' · ⏺ recording', i); }
      if (ex.rec === 'stop') { t += 1; key(q, label + ' · ⏹ video stopped', i); }
      if (ex.stay) { t += ex.stay; key(q, label + ' · hovering ' + ex.stay + 's', i); }
    }
    var last = w[w.length - 1];
    if (plan.finish === 'home') {
      var hb = bearing(last, p0), d2 = ground(last, p0), cruise = Math.max(last.alt, 20);
      t += 2; key({ lat: last.lat, lng: last.lng, alt: last.alt, heading: hb, pitch: 0 }, 'Returning home', w.length - 1);
      t += Math.abs(cruise - last.alt) / 3; key({ lat: last.lat, lng: last.lng, alt: cruise, heading: hb, pitch: 0 }, 'Returning home', w.length - 1);
      t += Math.max(2, d2 / 8); key({ lat: p0.lat, lng: p0.lng, alt: cruise, heading: hb, pitch: 0 }, 'Landing', w.length - 1);
      t += cruise / 2.5; key({ lat: p0.lat, lng: p0.lng, alt: 0, heading: hb, pitch: -90 }, 'Landed', w.length - 1);
    } else {
      t += 3; key(last, 'Hovering at the end', w.length - 1);
    }
    return { keys: keys, photos: photos, total: t };
  }
  function at(tl, t) {
    var k = tl.keys, i = 1;
    if (t <= 0) return k[0];
    while (i < k.length && k[i].t < t) i++;
    if (i >= k.length) return k[k.length - 1];
    var a = k[i - 1], b = k[i], f = b.t > a.t ? (t - a.t) / (b.t - a.t) : 1;
    f = f * f * (3 - 2 * f); // ease in/out like a real drone
    return { lat: a.lat + (b.lat - a.lat) * f, lng: a.lng + (b.lng - a.lng) * f, alt: a.alt + (b.alt - a.alt) * f,
      heading: angLerp(a.heading, b.heading, f), pitch: a.pitch + (b.pitch - a.pitch) * f, label: b.label === a.label ? a.label : (f < 0.5 ? a.label : b.label), wp: a.wp };
  }

  function fmt(s) { s = Math.max(0, Math.round(s)); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); }

  var STYLE = document.createElement('style');
  STYLE.textContent = '\
#pv{position:absolute;inset:0;z-index:1100;background:#05080b}\
#pv .pvmap{position:absolute;inset:0}\
#pv .pvhud{position:absolute;left:14px;top:14px;display:flex;gap:8px;flex-wrap:wrap;pointer-events:none}\
#pv .chip{background:rgba(11,15,20,.78);backdrop-filter:blur(8px);border:1px solid #ffffff1a;border-radius:999px;padding:6px 12px;font:600 13px system-ui;color:#e6edf3;font-variant-numeric:tabular-nums}\
#pv .chip.lbl{color:#3da9fc}\
#pv .rec{color:#f87171}\
#pv .pvclose{position:absolute;right:14px;top:14px}\
#pv .pvbar{position:absolute;left:50%;transform:translateX(-50%);bottom:18px;width:min(720px,calc(100% - 28px));background:rgba(11,15,20,.84);backdrop-filter:blur(10px);border:1px solid #ffffff1a;border-radius:16px;padding:10px 12px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;box-shadow:0 12px 40px #000a}\
#pv .pvbar input[type=range]{flex:1;min-width:140px;width:auto;accent-color:#3da9fc}\
#pv .seg{display:flex;background:#0b0f14;border:1px solid #232c38;border-radius:10px;padding:2px;gap:2px}\
#pv .seg button{border:0;background:transparent;color:#8b98a5;font:600 12px system-ui;padding:6px 9px;border-radius:8px;cursor:pointer}\
#pv .seg button.on{background:#1b2330;color:#e6edf3}\
#pv .play{width:40px;height:40px;border-radius:50%;border:0;background:#3da9fc;color:#04121f;font-size:16px;cursor:pointer;flex:none}\
#pv .time{font:600 12px system-ui;color:#8b98a5;font-variant-numeric:tabular-nums;min-width:86px;text-align:center}\
#pv .flash{position:absolute;inset:0;background:#fff;opacity:0;pointer-events:none;transition:opacity .35s}\
#pv .frame{position:absolute;inset:0;pointer-events:none;box-shadow:inset 0 0 0 1px #ffffff14, inset 0 0 120px #0007}\
#pv .frame:before,#pv .frame:after{content:"";position:absolute;left:50%;top:50%;background:#ffffff55}\
#pv .frame:before{width:26px;height:1px;transform:translate(-50%,-50%)}\
#pv .frame:after{width:1px;height:26px;transform:translate(-50%,-50%)}\
#pv .note{position:absolute;right:14px;bottom:96px;max-width:260px;font:12px system-ui;color:#8b98a5;background:rgba(11,15,20,.7);border-radius:10px;padding:6px 10px}\
#pv .err{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#e6edf3;font:14px system-ui;padding:20px;text-align:center}\
#pv .drone{width:30px;height:30px}';
  document.head.appendChild(STYLE);

  var cur = null;

  function open(plan, host) {
    close();
    host.hidden = false;
    host.innerHTML = '<div class="err">Loading 3D view…</div>';
    var state = { host: host, plan: plan, tl: timeline(plan), t: 0, rate: 5, playing: true, view: 'drone', buildings: true, raf: 0, last: 0, nextPhoto: 0 };
    cur = state;
    load().then(function () { if (cur === state) build(state); })
      .catch(function (e) { host.innerHTML = '<div class="err">' + e.message + '</div>' + closeBtn(); host.querySelector('.pvclose').onclick = close; });
  }
  function closeBtn() { return '<button class="b pvclose" title="Close (Esc)">✕ Close</button>'; }

  function build(s) {
    var h = s.host, w = s.plan._preview.wps;
    h.innerHTML = '<div class="pvmap"></div><div class="frame"></div><div class="flash"></div>' +
      '<div class="pvhud"><span class="chip lbl" data-k="label"></span><span class="chip" data-k="alt"></span><span class="chip" data-k="cam"></span><span class="chip rec" data-k="rec" hidden>● REC</span></div>' +
      closeBtn() +
      '<div class="note">Satellite photos are flat, and buildings are rough shapes. Trees, wires and poles aren\'t shown, so check those yourself.</div>' +
      '<div class="pvbar"><button class="play" title="Play / pause (space)">❚❚</button>' +
      '<input type="range" min="0" max="1000" value="0"><span class="time"></span>' +
      '<div class="seg" data-g="rate"><button data-v="1">1×</button><button data-v="2">2×</button><button data-v="5" class="on">5×</button><button data-v="10">10×</button></div>' +
      '<div class="seg" data-g="view"><button data-v="drone" class="on">Drone camera</button><button data-v="chase">Overview</button></div>' +
      '<div class="seg" data-g="bld"><button data-v="1" class="on" title="Show 3D buildings">3D</button><button data-v="0">Flat</button></div></div>';
    var el = function (sel) { return h.querySelector(sel); };
    var line = { type: 'Feature', geometry: { type: 'LineString', coordinates: w.map(function (q) { return [q.lng, q.lat]; }) } };
    var dots = { type: 'FeatureCollection', features: w.map(function (q, i) {
      return { type: 'Feature', properties: { n: i + 1, photo: q.photo ? 1 : 0 }, geometry: { type: 'Point', coordinates: [q.lng, q.lat] } }; }) };
    var map = new maplibregl.Map({
      container: el('.pvmap'), interactive: false, attributionControl: { compact: true }, maxPitch: 85, maxZoom: 24,
      center: [w[0].lng, w[0].lat], zoom: 19, pitch: 60, fadeDuration: 0,
      style: {
        version: 8,
        sources: {
          sat: { type: 'raster', tiles: [ESRI], tileSize: 256, maxzoom: 19, attribution: 'Imagery © Esri' },
          omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet', attribution: '© OpenStreetMap' },
          route: { type: 'geojson', data: line },
          dots: { type: 'geojson', data: dots }
        },
        sky: { 'sky-color': '#6fa8dc', 'horizon-color': '#cfe2f3', 'fog-color': '#cfe2f3', 'sky-horizon-blend': 0.5, 'horizon-fog-blend': 0.7, 'fog-ground-blend': 0.85 },
        layers: [
          { id: 'bg', type: 'background', paint: { 'background-color': '#9db6c9' } },
          { id: 'sat', type: 'raster', source: 'sat' },
          { id: 'route', type: 'line', source: 'route', paint: { 'line-color': '#fbbf24', 'line-width': 3, 'line-opacity': 0.8, 'line-dasharray': [2, 1.5] } },
          { id: 'dots', type: 'circle', source: 'dots', paint: { 'circle-radius': 5, 'circle-color': ['case', ['==', ['get', 'photo'], 1], '#3da9fc', '#fbbf24'], 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 } },
          { id: 'bld', type: 'fill-extrusion', source: 'omt', 'source-layer': 'building', minzoom: 14,
            paint: { 'fill-extrusion-color': '#c9d3dc', 'fill-extrusion-opacity': 0.55,
              'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 6], 'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0] } }
        ]
      }
    });
    map.on('error', function () { /* missing tiles are fine */ });
    var de = document.createElement('div');
    de.innerHTML = '<svg class="drone" viewBox="0 0 30 30"><g fill="none" stroke="#fff" stroke-width="2"><circle cx="7" cy="7" r="4.5"/><circle cx="23" cy="7" r="4.5"/><circle cx="7" cy="23" r="4.5"/><circle cx="23" cy="23" r="4.5"/><path d="M10 10l10 10M20 10L10 20"/></g><path d="M15 3l5 8h-10z" fill="#3da9fc"/></svg>';
    var marker = new maplibregl.Marker({ element: de, rotationAlignment: 'map', pitchAlignment: 'map' }).setLngLat([w[0].lng, w[0].lat]).addTo(map);
    s.map = map; s.marker = marker; s.el = el;

    el('.pvclose').onclick = close;
    el('.play').onclick = function () { toggle(s); };
    var range = el('input[type=range]');
    range.oninput = function () { s.t = s.tl.total * range.value / 1000; s.nextPhoto = s.tl.photos.findIndex(function (p) { return p > s.t; }); draw(s, true); };
    h.querySelectorAll('.seg').forEach(function (g) {
      g.querySelectorAll('button').forEach(function (b) {
        b.onclick = function () {
          g.querySelectorAll('button').forEach(function (x) { x.classList.toggle('on', x === b); });
          var k = g.getAttribute('data-g'), v = b.getAttribute('data-v');
          if (k === 'rate') s.rate = +v;
          if (k === 'view') s.view = v;
          if (k === 'bld') map.setLayoutProperty('bld', 'visibility', v === '1' ? 'visible' : 'none');
          draw(s, true);
        };
      });
    });
    map.once('load', function () { draw(s, true); });
    s.last = performance.now();
    s.raf = requestAnimationFrame(function loop(now) {
      if (cur !== s) return;
      var dt = Math.min(0.1, (now - s.last) / 1000); s.last = now;
      if (s.playing) {
        s.t += dt * s.rate;
        if (s.t >= s.tl.total) { s.t = s.tl.total; s.playing = false; el('.play').textContent = '▶'; }
        while (s.nextPhoto >= 0 && s.nextPhoto < s.tl.photos.length && s.tl.photos[s.nextPhoto] <= s.t) { flash(s); s.nextPhoto++; }
        draw(s);
      }
      s.raf = requestAnimationFrame(loop);
    });
  }

  function toggle(s) {
    if (!s.playing && s.t >= s.tl.total) { s.t = 0; s.nextPhoto = 0; }
    s.playing = !s.playing;
    s.el('.play').textContent = s.playing ? '❚❚' : '▶';
  }
  function flash(s) {
    var f = s.el('.flash'); f.style.transition = 'none'; f.style.opacity = 0.55;
    requestAnimationFrame(function () { f.style.transition = 'opacity .35s'; f.style.opacity = 0; });
  }

  function draw(s, force) {
    var p = at(s.tl, s.t), map = s.map;
    var ft = Math.round(p.alt / FT);
    s.el('[data-k=label]').textContent = p.label;
    s.el('[data-k=alt]').textContent = '↑ ' + ft + ' ft';
    s.el('[data-k=cam]').textContent = 'Camera ' + Math.round(p.pitch) + '° · ' + Math.round(p.heading) + '°';
    var recOn = false, k = s.tl.keys;
    for (var i = 0; i < k.length && k[i].t <= s.t; i++) { if (/recording/.test(k[i].label)) recOn = true; if (/video stopped/.test(k[i].label)) recOn = false; }
    s.el('[data-k=rec]').hidden = !recOn;
    s.el('.time').textContent = fmt(s.t) + ' / ' + fmt(s.tl.total);
    s.el('input[type=range]').value = Math.round(1000 * s.t / s.tl.total);
    s.el('.frame').style.display = s.view === 'drone' ? '' : 'none';
    s.marker.setLngLat([p.lng, p.lat]).setRotation(p.heading);
    s.marker.getElement().style.display = s.view === 'drone' ? 'none' : '';
    if (s.view === 'drone') {
      var alt = Math.max(1.5, p.alt), down = -p.pitch; // degrees below the horizon
      if (down > 88) {
        var c = map.calculateCameraOptionsFromTo(new maplibregl.LngLat(p.lng, p.lat), alt, new maplibregl.LngLat(p.lng, p.lat + 1e-6), 0);
        map.jumpTo({ center: [p.lng, p.lat], zoom: c.zoom, pitch: 0, bearing: p.heading });
      } else {
        var dist, toAlt;
        if (down < 4) { dist = 300; toAlt = alt - dist * Math.tan(Math.max(down, 0.5) * D2R); }
        else { dist = Math.min(alt / Math.tan(down * D2R), 600); toAlt = alt - dist * Math.tan(down * D2R); }
        var to = offset(p.lat, p.lng, p.heading, dist);
        var o = map.calculateCameraOptionsFromTo(new maplibregl.LngLat(p.lng, p.lat), alt, new maplibregl.LngLat(to.lng, to.lat), toAlt);
        map.jumpTo(o);
      }
    } else {
      map.jumpTo({ center: [p.lng, p.lat], zoom: 19.2, pitch: 55, bearing: p.heading });
    }
  }

  function close() {
    if (!cur) return;
    var s = cur; cur = null;
    cancelAnimationFrame(s.raf);
    if (s.map) s.map.remove();
    s.host.innerHTML = ''; s.host.hidden = true;
  }

  document.addEventListener('keydown', function (e) {
    if (!cur || !cur.map) return;
    if (e.key === 'Escape') { close(); e.preventDefault(); }
    if (e.key === ' ' && !/INPUT|SELECT|TEXTAREA|BUTTON/.test(document.activeElement.tagName)) { toggle(cur); e.preventDefault(); }
  });

  window.AirPreview = { open: open, close: close, timeline: timeline, at: at };
})();
