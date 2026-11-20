/*
 * Air Pilot Planner: airspace overlay.
 * Draws FAA data on the map (public ArcGIS services, no key):
 *  - UAS Facility Map grid: the max height (ft) LAANC can approve near airports (0 = no automatic approval)
 *  - Class B/C/D/E airspace near airports
 *  - Special use airspace (prohibited, restricted, military areas)
 *  - National security UAS flight restrictions
 * and checks a plan's points against it. The drone also enforces DJI's own GEO zones, which can differ.
 */
(function () {
  'use strict';
  var BASE = 'https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/';
  var LAYERS = [
    { key: 'grid', svc: 'FAA_UAS_FacilityMap_Data', minZoom: 10, label: 'LAANC ceilings near airports' },
    { key: 'class', svc: 'Class_Airspace', minZoom: 8, label: 'Controlled airspace (B, C, D, E)' },
    { key: 'sua', svc: 'Special_Use_Airspace', minZoom: 7, label: 'Restricted / military areas' },
    { key: 'nsr', svc: 'National_Security_UAS_Flight_Restrictions', minZoom: 7, label: 'National security no-drone zones' }
  ];

  // Attribute names vary a little between datasets; look them up case-insensitively.
  function attr(p, names) {
    if (!p) return undefined;
    var keys = Object.keys(p);
    for (var i = 0; i < names.length; i++) {
      for (var k = 0; k < keys.length; k++) {
        if (keys[k].toLowerCase() === names[i].toLowerCase() && p[keys[k]] !== null && p[keys[k]] !== '') return p[keys[k]];
      }
    }
    return undefined;
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function ceilingColor(c) {
    if (c === 0) return '#f87171';
    if (c <= 100) return '#fb923c';
    if (c <= 200) return '#fbbf24';
    if (c <= 300) return '#a3e635';
    return '#4ade80';
  }
  function classColor(cls) {
    cls = String(cls || '').toUpperCase();
    if (cls === 'B') return '#60a5fa';
    if (cls === 'C') return '#e879f9';
    if (cls === 'D') return '#38bdf8';
    return '#c084fc';
  }

  function styleFor(key, p) {
    if (key === 'grid') {
      var c = +attr(p, ['CEILING', 'Ceiling']);
      var col = isNaN(c) ? '#94a3b8' : ceilingColor(c);
      return { color: col, weight: 0.6, opacity: 0.7, fillColor: col, fillOpacity: c === 0 ? 0.28 : 0.16 };
    }
    if (key === 'class') {
      var cl = attr(p, ['CLASS', 'Class']);
      var surface = /surface/i.test(String(attr(p, ['LOCAL_TYPE', 'TYPE_CODE']) || '')) || String(cl).toUpperCase() !== 'E';
      return { color: classColor(cl), weight: 2, opacity: 0.85, dashArray: String(cl).toUpperCase() === 'D' || String(cl).toUpperCase() === 'E' ? '6 5' : null,
        fill: surface, fillColor: classColor(cl), fillOpacity: 0.05 };
    }
    if (key === 'sua') {
      var t = String(attr(p, ['TYPE_CODE', 'TYPE', 'Type']) || '').toUpperCase();
      var hard = t === 'P' || t === 'R' || /PROHIB|RESTRICT/.test(t);
      return { color: hard ? '#ef4444' : '#f59e0b', weight: 1.5, opacity: 0.8, fillColor: hard ? '#ef4444' : '#f59e0b', fillOpacity: hard ? 0.12 : 0.05 };
    }
    return { color: '#dc2626', weight: 2, opacity: 0.9, fillColor: '#dc2626', fillOpacity: 0.3 };
  }

  /** Short description of an area, used in tooltips and plan warnings. */
  function describe(key, p) {
    if (key === 'grid') {
      var c = attr(p, ['CEILING', 'Ceiling']);
      var apt = attr(p, ['APT1_NAME', 'APT1_FAAID', 'ARPT_NAME', 'AIRPORT']);
      if (+c === 0) return 'No automatic LAANC approval here (0 ft)' + (apt ? ' — ' + apt : '');
      return 'LAANC can approve up to ' + c + ' ft' + (apt ? ' — near ' + apt : '');
    }
    if (key === 'class') {
      var cls = attr(p, ['CLASS', 'Class']);
      var name = attr(p, ['NAME', 'IDENT']);
      var lo = attr(p, ['LOWER_VAL', 'LOWER_DESC']), up = attr(p, ['UPPER_VAL', 'UPPER_DESC']);
      return 'Class ' + cls + ' airspace' + (name ? ' — ' + name : '') +
        (lo != null ? ' (from ' + (+lo <= 0 || /SFC/i.test(lo) ? 'the surface' : lo + ' ft') + (up != null ? ' to ' + up + ' ft' : '') + ')' : '');
    }
    if (key === 'sua') {
      var nm = attr(p, ['NAME', 'IDENT']);
      var tc = String(attr(p, ['TYPE_CODE', 'TYPE']) || '');
      var kind = { P: 'Prohibited area', R: 'Restricted area', MOA: 'Military operations area', W: 'Warning area', A: 'Alert area' }[tc.toUpperCase()] || 'Special use airspace';
      var times = attr(p, ['TIMESOFUSE', 'TIMES_OF_USE']);
      return kind + (nm ? ' — ' + nm : '') + (times ? ' (' + times + ')' : '');
    }
    var n2 = attr(p, ['NAME', 'Facility', 'FACILITY', 'Base', 'SITE']);
    return 'National security drone restriction' + (n2 ? ' — ' + n2 : '') + '. No drones without FAA approval.';
  }

  // ---------- point-in-polygon on GeoJSON
  function inRing(lng, lat, ring) {
    var inside = false;
    for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      var xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
      if (((yi > lat) !== (yj > lat)) && (lng < (xj - xi) * (lat - yi) / ((yj - yi) || 1e-12) + xi)) inside = !inside;
    }
    return inside;
  }
  function inPoly(lng, lat, rings) {
    if (!rings.length || !inRing(lng, lat, rings[0])) return false;
    for (var h = 1; h < rings.length; h++) if (inRing(lng, lat, rings[h])) return false;
    return true;
  }
  function contains(geom, lng, lat) {
    if (!geom) return false;
    if (geom.type === 'Polygon') return inPoly(lng, lat, geom.coordinates);
    if (geom.type === 'MultiPolygon') return geom.coordinates.some(function (p) { return inPoly(lng, lat, p); });
    return false;
  }

  // ---------- state
  var map, onChange, on = false, group, layers = {}, cache = {}, timer = null, statusEl, inflight = 0, failed = {};

  function init(m, changed) {
    map = m; onChange = changed || function () {};
    group = L.layerGroup();
    LAYERS.forEach(function (l) { layers[l.key] = L.layerGroup().addTo(group); cache[l.key] = {}; });
    var Ctl = L.Control.extend({
      options: { position: 'topright' },
      onAdd: function () {
        var d = L.DomUtil.create('div', 'air-ctl');
        d.innerHTML = '<button type="button" class="air-btn" title="Show no-fly zones and airspace">✈ Airspace</button>' +
          '<div class="air-legend" hidden>' +
          '<div class="air-status"></div>' +
          '<div><i style="background:#f87171"></i>0 ft: no auto approval</div>' +
          '<div><i style="background:#fb923c"></i>50–100 ft</div><div><i style="background:#fbbf24"></i>200 ft</div>' +
          '<div><i style="background:#4ade80"></i>300–400 ft</div>' +
          '<div><i class="ln" style="border-color:#60a5fa"></i>Class B · <i class="ln" style="border-color:#e879f9"></i>C · <i class="ln dash" style="border-color:#38bdf8"></i>D</div>' +
          '<div><i style="background:#ef4444"></i>Restricted / prohibited</div>' +
          '<div><i style="background:#dc2626"></i>National security</div>' +
          '<div class="air-links">Also check <a target="_blank" rel="noopener" href="https://tfr.faa.gov/tfr3/">TFRs</a> · ' +
          '<a target="_blank" rel="noopener" class="dji-link" href="https://fly-safe.dji.com/nfz/nfz-query">DJI zones</a> · ' +
          '<a target="_blank" rel="noopener" href="https://www.faa.gov/uas/getting_started/b4ufly">B4UFLY</a></div></div>';
        L.DomEvent.disableClickPropagation(d);
        L.DomEvent.disableScrollPropagation(d);
        d.querySelector('.air-btn').onclick = function () { toggle(!on); };
        statusEl = d.querySelector('.air-status');
        return d;
      }
    });
    map.addControl(new Ctl());
    map.on('moveend', function () { syncLinks(); if (on) schedule(); });
    try { if (localStorage.getItem('airplan-airspace') === '1') toggle(true); } catch (e) {}
  }

  function toggle(v) {
    on = v;
    var c = map.getContainer().querySelector('.air-ctl');
    c.querySelector('.air-btn').classList.toggle('on', on);
    c.querySelector('.air-legend').hidden = !on;
    try { localStorage.setItem('airplan-airspace', on ? '1' : '0'); } catch (e) {}
    if (on) { group.addTo(map); schedule(); } else { map.removeLayer(group); }
    onChange();
  }

  function schedule() { clearTimeout(timer); timer = setTimeout(load, 350); }

  // Areas are cached by 0.25° tiles so panning around doesn't refetch.
  var TILE = 0.25;
  function load() {
    var z = map.getZoom(), b = map.getBounds();
    var x0 = Math.floor(b.getWest() / TILE), x1 = Math.floor(b.getEast() / TILE);
    var y0 = Math.floor(b.getSouth() / TILE), y1 = Math.floor(b.getNorth() / TILE);
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) { status('Zoom in to see airspace.'); return; }
    var pending = 0;
    LAYERS.forEach(function (l) {
      if (z < l.minZoom) return;
      for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) {
        var id = x + ',' + y;
        if (cache[l.key][id]) continue;
        cache[l.key][id] = 'loading';
        pending++;
        fetchTile(l, x, y, id);
      }
    });
    if (z < 10) status('Zoom in closer to see the airport height grid.');
    else if (!pending && !inflight) status('');
  }

  function fetchTile(l, x, y, id) {
    inflight++; status('Loading airspace…');
    var env = [x * TILE, y * TILE, (x + 1) * TILE, (y + 1) * TILE].join(',');
    var url = BASE + l.svc + '/FeatureServer/0/query?where=1%3D1&geometry=' + env +
      '&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=*&outSR=4326' +
      '&geometryPrecision=6&resultRecordCount=2000&f=geojson';
    fetch(url).then(function (r) { return r.json(); }).then(function (gj) {
      if (gj.error) throw new Error(gj.error.message || 'error');
      cache[l.key][id] = gj.features || [];
      L.geoJSON(gj, {
        style: function (f) { return styleFor(l.key, f.properties); },
        onEachFeature: function (f, layer) {
          layer.bindTooltip(esc(describe(l.key, f.properties)), { sticky: true });
          // keep the drawing tools working: a click on an airspace shape still adds a point
          layer.on('click', function (e) { L.DomEvent.stop(e); map.fire('click', { latlng: e.latlng }); });
        }
      }).addTo(layers[l.key]);
    }).catch(function () {
      cache[l.key][id] = null; // retry next time
      failed[l.key] = true;
    }).then(function () {
      inflight--;
      if (!inflight) {
        var bad = LAYERS.filter(function (x) { return failed[x.key]; }).map(function (x) { return x.label; });
        failed = {};
        status(bad.length ? 'Couldn’t load: ' + bad.join(', ') + '.' : '');
        onChange();
      }
    });
  }

  function status(t) { if (statusEl) { statusEl.textContent = t; statusEl.style.display = t ? '' : 'none'; } }

  /** Warnings for a plan's points [{lat,lng,alt (m)}], using whatever airspace is loaded. */
  function check(points) {
    if (!on || !points || !points.length) return [];
    var out = [], seen = {};
    function each(key, fn) {
      Object.keys(cache[key]).forEach(function (id) {
        var fs = cache[key][id];
        if (fs && fs.forEach) fs.forEach(fn);
      });
    }
    var maxFt = 0;
    points.forEach(function (q) { maxFt = Math.max(maxFt, q.alt / 0.3048); });
    function hit(key, f) {
      for (var i = 0; i < points.length; i++) if (contains(f.geometry, points[i].lng, points[i].lat)) return i;
      return -1;
    }
    each('nsr', function (f) {
      var i = hit('nsr', f); if (i < 0) return;
      var d = describe('nsr', f.properties); if (seen[d]) return; seen[d] = 1;
      out.push({ level: 'bad', text: '⛔ Point ' + (i + 1) + ': ' + d });
    });
    each('sua', function (f) {
      var t = String(attr(f.properties, ['TYPE_CODE', 'TYPE']) || '').toUpperCase();
      if (t !== 'P' && t !== 'R') return;
      var i = hit('sua', f); if (i < 0) return;
      var d = describe('sua', f.properties); if (seen[d]) return; seen[d] = 1;
      out.push({ level: t === 'P' ? 'bad' : 'warn', text: (t === 'P' ? '⛔' : '⚠') + ' Point ' + (i + 1) + ': ' + d + '. Check before flying.' });
    });
    var gridHit = null;
    each('grid', function (f) {
      var i = hit('grid', f); if (i < 0) return;
      var c = +attr(f.properties, ['CEILING', 'Ceiling']);
      if (isNaN(c)) return;
      if (!gridHit || c < gridHit.c) gridHit = { c: c, i: i, p: f.properties };
    });
    each('class', function (f) {
      var cl = String(attr(f.properties, ['CLASS', 'Class']) || '').toUpperCase();
      var surface = cl !== 'E' || /surface/i.test(String(attr(f.properties, ['LOCAL_TYPE', 'TYPE_CODE']) || ''));
      if (!surface) return;
      var i = hit('class', f); if (i < 0) return;
      var d = describe('class', f.properties); if (seen[d]) return; seen[d] = 1;
      out.push({ level: 'warn', text: '⚠ Point ' + (i + 1) + ' is in ' + d + '. Get LAANC approval first (free, in an app like Aloft).' });
    });
    if (gridHit) {
      var apt = attr(gridHit.p, ['APT1_NAME', 'APT1_FAAID']);
      if (gridHit.c === 0) out.push({ level: 'bad', text: '⛔ Point ' + (gridHit.i + 1) + ' is in a 0 ft LAANC cell' + (apt ? ' near ' + apt : '') + ': no automatic approval here.' });
      else if (maxFt > gridHit.c + 1) out.push({ level: 'warn', text: '⚠ This plan goes to ' + Math.round(maxFt) + ' ft, but LAANC only approves ' + gridHit.c + ' ft here' + (apt ? ' (near ' + apt + ')' : '') + '.' });
      else out.push({ level: 'ok', text: '✔ Inside LAANC limits here (' + gridHit.c + ' ft max). Still request approval before flying.' });
    }
    return out;
  }

  /** Point the DJI link at the map's centre. */
  function syncLinks() {
    if (!map) return;
    var a = map.getContainer().querySelector('.dji-link'); if (!a) return;
    var c = map.getCenter();
    a.href = 'https://fly-safe.dji.com/nfz/nfz-query?lng=' + c.lng.toFixed(5) + '&lat=' + c.lat.toFixed(5);
  }

  window.AirSpace = { init: init, check: check, isOn: function () { return on; }, syncLinks: syncLinks, _contains: contains };
})();
