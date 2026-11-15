/* Air Pilot mission generator: Roof face sweep and Orbit.
 * Pure functions (no DOM) so they can be tested with Node.
 * Units: meters, degrees. Altitudes are relative to the takeoff point. */
(function (root) {
  'use strict';

  // DJI Mavic Air, 4:3 photos: ~85° diagonal -> ~72° horizontal, ~57° vertical
  var HFOV = 72 * Math.PI / 180;
  var VFOV = 57 * Math.PI / 180;
  var MAX_WAYPOINTS = 99;
  var MIN_STANDOFF = 4.5; // m (~15 ft), hard minimum
  var MAX_ALT = 120;      // m (~400 ft)

  var R_LAT = 110574, R_LNG = 111320;

  function toLocal(origin, p) {
    return {
      x: (p.lng - origin.lng) * Math.cos(origin.lat * Math.PI / 180) * R_LNG,
      y: (p.lat - origin.lat) * R_LAT
    };
  }
  function toGeo(origin, v) {
    return {
      lat: origin.lat + v.y / R_LAT,
      lng: origin.lng + v.x / (Math.cos(origin.lat * Math.PI / 180) * R_LNG)
    };
  }
  function bearingDeg(vx, vy) { // 0 = north, clockwise
    var b = Math.atan2(vx, vy) * 180 / Math.PI;
    return b > 180 ? b - 360 : b;
  }
  function round(v, d) { var k = Math.pow(10, d); return Math.round(v * k) / k; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  /**
   * Roof face sweep.
   * p: { a:{lat,lng}, b:{lat,lng}  - the roof's lower edge (eave), drawn along the face
   *      side: 1 | -1             - which side of A->B the drone flies on (1 = right of A->B)
   *      eave, ridge              - heights above takeoff (m)
   *      depth                    - horizontal distance from eave to ridge (m)
   *      standoff                 - horizontal distance from the eave line (m)
   *      overlap                  - 0..0.9 photo overlap
   *      margin                   - extra flight past each end (m)
   *      lookDown                 - camera depression angle (deg), default 35
   *      speed }
   */
  function sweep(p) {
    var warnings = [];
    var o = p.a;
    var A = { x: 0, y: 0 }, B = toLocal(o, p.b);
    var len = Math.hypot(B.x, B.y);
    if (len < 2) return { error: 'Draw the roof edge longer (at least 2 m / 7 ft).' };
    var u = { x: B.x / len, y: B.y / len };
    var side = p.side === -1 ? -1 : 1;
    // normal pointing from the roof edge toward the drone (right of A->B when side = 1)
    var n = { x: u.y * side, y: -u.x * side };
    var standoff = Math.max(MIN_STANDOFF, p.standoff || 8);
    if ((p.standoff || 8) < MIN_STANDOFF) warnings.push('Distance raised to the 15 ft minimum.');
    if (standoff < 6) warnings.push('Closer than 20 ft: GPS error is a few feet, so watch it closely.');
    var eave = Math.max(0, p.eave), ridge = Math.max(eave, p.ridge);
    var depth = Math.max(0.5, p.depth || 4);
    var overlap = clamp(p.overlap == null ? 0.6 : p.overlap, 0.2, 0.85);
    var margin = Math.max(0, p.margin == null ? 2 : p.margin);
    var look = clamp(p.lookDown || 35, 15, 70) * Math.PI / 180;

    var slope = Math.hypot(depth, ridge - eave);
    // camera distance to the middle of the roof face decides the footprint
    var midHoriz = standoff + depth / 2;
    var dist = midHoriz / Math.cos(look);
    var footV = 2 * dist * Math.tan(VFOV / 2);
    var rows = clamp(Math.ceil(slope / (footV * (1 - overlap))), 1, 6);

    var wps = [];
    var heading = round(bearingDeg(-n.x, -n.y), 0);
    for (var r = 0; r < rows; r++) {
      var t = (r + 0.5) / rows; // aim fraction from eave (0) to ridge (1)
      var aimH = eave + (ridge - eave) * t;
      var horiz = standoff + depth * t;
      var alt = clamp(aimH + horiz * Math.tan(look), eave + 2, MAX_ALT);
      var pitch = -Math.atan2(alt - aimH, horiz) * 180 / Math.PI;
      var d = Math.hypot(horiz, alt - aimH);
      var foot = 2 * d * Math.tan(HFOV / 2);
      var step = Math.max(1, foot * (1 - overlap));
      var span = len + 2 * margin;
      var count = Math.max(2, Math.ceil(span / step) + 1);
      var realStep = span / (count - 1);
      for (var i = 0; i < count; i++) {
        var k = (r % 2 === 0) ? i : count - 1 - i; // serpentine
        var x = -margin + k * realStep;
        var pos = { x: A.x + u.x * x + n.x * standoff, y: A.y + u.y * x + n.y * standoff };
        var g = toGeo(o, pos);
        var aim = { x: A.x + u.x * x - n.x * depth * t, y: A.y + u.y * x - n.y * depth * t };
        wps.push({ lat: g.lat, lng: g.lng, alt: round(alt, 1), heading: heading,
          pitch: round(clamp(pitch, -90, 0), 0), photo: true, row: r, aim: toGeo(o, aim) });
      }
    }
    // target = middle of the roof face
    var target = toGeo(o, { x: B.x / 2 - n.x * depth / 2, y: B.y / 2 - n.y * depth / 2 });
    // house footprint guess (for the phone's safety check): the face rectangle
    var house = [toGeo(o, A), toGeo(o, B),
      toGeo(o, { x: B.x - n.x * depth * 2, y: B.y - n.y * depth * 2 }),
      toGeo(o, { x: -n.x * depth * 2, y: -n.y * depth * 2 })];
    return finish(p, 'sweep', wps, target, house, warnings, { rows: rows, eave: eave, ridge: ridge });
  }

  /**
   * Orbit around a house footprint.
   * p: { house:[{lat,lng}...] (3+ corners), eave, ridge, standoff (m beyond the farthest corner),
   *      rings: 1|2, perRing: photos per ring, speed }
   */
  function orbit(p) {
    var warnings = [];
    if (!p.house || p.house.length < 3) return { error: 'Tap at least 3 corners of the house.' };
    var o = p.house[0];
    var pts = p.house.map(function (q) { return toLocal(o, q); });
    var cx = 0, cy = 0;
    pts.forEach(function (q) { cx += q.x; cy += q.y; });
    cx /= pts.length; cy /= pts.length;
    var far = 0;
    pts.forEach(function (q) { far = Math.max(far, Math.hypot(q.x - cx, q.y - cy)); });
    var standoff = Math.max(MIN_STANDOFF, p.standoff || 8);
    if ((p.standoff || 8) < MIN_STANDOFF) warnings.push('Distance raised to the 15 ft minimum.');
    var radius = far + standoff;
    var eave = Math.max(0, p.eave), ridge = Math.max(eave, p.ridge);
    var mid = (eave + ridge) / 2;
    var rings = p.rings === 2 ? 2 : 1;
    var per = clamp(p.perRing || 24, 8, 48);
    if (rings * per > MAX_WAYPOINTS) per = Math.floor(MAX_WAYPOINTS / rings);
    var ringAlts = rings === 1
      ? [ridge + Math.max(4, radius * 0.45)]
      : [ridge + Math.max(3, radius * 0.3), ridge + Math.max(8, radius * 0.75)];

    var wps = [];
    for (var r = 0; r < rings; r++) {
      var alt = clamp(ringAlts[r], ridge + 2, MAX_ALT);
      var pitch = -Math.atan2(alt - mid, radius) * 180 / Math.PI;
      for (var i = 0; i < per; i++) {
        var ang = (i / per) * 2 * Math.PI + (r * Math.PI / per); // offset ring 2 by half a step
        var pos = { x: cx + radius * Math.sin(ang), y: cy + radius * Math.cos(ang) };
        var g = toGeo(o, pos);
        wps.push({ lat: g.lat, lng: g.lng, alt: round(alt, 1),
          heading: round(bearingDeg(cx - pos.x, cy - pos.y), 0),
          pitch: round(clamp(pitch, -90, 0), 0), photo: true, row: r, aim: toGeo(o, { x: cx, y: cy }) });
      }
    }
    return finish(p, 'orbit', wps, toGeo(o, { x: cx, y: cy }), p.house, warnings,
      { rings: rings, radius: round(radius, 1), eave: eave, ridge: ridge });
  }

  function finish(p, kind, wps, target, house, warnings, extra) {
    if (wps.length > MAX_WAYPOINTS) {
      return { error: 'That needs ' + wps.length + ' points (the drone allows 99). Lower the overlap or shorten the edge.' };
    }
    var speed = clamp(p.speed || 2.5, 1, 6);
    var dist = 0;
    for (var i = 1; i < wps.length; i++) {
      var a = wps[i - 1], b = wps[i];
      var dx = (b.lng - a.lng) * Math.cos(a.lat * Math.PI / 180) * R_LNG, dy = (b.lat - a.lat) * R_LAT;
      dist += Math.sqrt(dx * dx + dy * dy + (b.alt - a.alt) * (b.alt - a.alt));
    }
    var photos = wps.filter(function (w) { return w.photo; }).length;
    var seconds = Math.round(dist / speed + photos * 3 + wps.length * 1.5 + 60);
    var battery = Math.ceil(seconds * 0.09 * 1.2);
    if (battery > 60) warnings.push('Uses about ' + battery + '% battery. Start with a full battery or split it into two missions.');
    var maxAlt = 0;
    wps.forEach(function (w) { maxAlt = Math.max(maxAlt, w.alt); });
    return {
      app: 'airpilot-mission', v: 1,
      name: p.name || (kind === 'sweep' ? 'Roof sweep' : 'Roof orbit'),
      kind: kind, created: new Date().toISOString(),
      speed: speed, finish: p.finish === 'hover' ? 'hover' : 'home',
      target: { lat: target.lat, lng: target.lng },
      house: house.map(function (q) { return [round(q.lat, 7), round(q.lng, 7)]; }),
      roof: { eave: extra.eave, ridge: extra.ridge },
      wp: wps.map(function (w) {
        return [round(w.lat, 7), round(w.lng, 7), w.alt, w.heading, w.pitch, w.photo ? 1 : 0];
      }),
      // preview-only details (not needed by the phone)
      _preview: { wps: wps, distance: Math.round(dist), seconds: seconds, battery: battery,
        photos: photos, maxAlt: maxAlt, warnings: warnings, extra: extra }
    };
  }

  /** Strip preview data for sending to the phone. */
  function toTransfer(plan) {
    var out = {};
    Object.keys(plan).forEach(function (k) { if (k.charAt(0) !== '_') out[k] = plan[k]; });
    return out;
  }

  var api = { sweep: sweep, orbit: orbit, toTransfer: toTransfer, MIN_STANDOFF: MIN_STANDOFF };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.AirPlan = api;
})(this);
