import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { Departure, StopCall } from "../types/transport";

/** Entur encodes service geometry with Google's precision-5 polyline format. */
function decodePolyline(encoded: string): L.LatLngTuple[] {
  const points: L.LatLngTuple[] = [];
  let index = 0,
    latitude = 0,
    longitude = 0;
  while (index < encoded.length) {
    const values: number[] = [];
    for (let coordinate = 0; coordinate < 2; coordinate++) {
      let shift = 0,
        result = 0,
        byte: number;
      do {
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 31) << shift;
        shift += 5;
      } while (byte >= 32 && index < encoded.length);
      values.push(result & 1 ? ~(result >> 1) : result >> 1);
    }
    latitude += values[0] ?? 0;
    longitude += values[1] ?? 0;
    points.push([latitude / 100000, longitude / 100000]);
  }
  return points;
}

/** Index of the route point nearest a stop, searching from `from`. -1 without coordinates. */
function nearestPoint(
  points: L.LatLngTuple[],
  stop: StopCall | undefined,
  from: number,
): number {
  if (!stop || stop.latitude === null || stop.longitude === null) return -1;
  const scale = Math.cos((stop.latitude * Math.PI) / 180);
  let best = -1;
  let bestDistance = Infinity;
  for (let i = from; i < points.length; i++) {
    const [latitude, longitude] = points[i]!;
    const distance =
      (latitude - stop.latitude) ** 2 +
      ((longitude - stop.longitude) * scale) ** 2;
    if (distance < bestDistance) {
      bestDistance = distance;
      best = i;
    }
  }
  return best;
}

function hasPosition(stop: StopCall | undefined): stop is StopCall & {
  latitude: number;
  longitude: number;
} {
  return (
    !!stop &&
    stop.latitude !== null &&
    stop.longitude !== null &&
    Number.isFinite(stop.latitude) &&
    Number.isFinite(stop.longitude)
  );
}

/**
 * The part of the service path between boarding and destination. Entur's geometry covers the whole
 * service journey, so it is cut at the points nearest those two stops. Without usable geometry the
 * stops themselves are joined.
 */
function tripSegment(
  route: L.LatLngTuple[],
  departure: Departure,
): L.LatLngTuple[] {
  const origin = departure.stops[departure.originIndex];
  const destination = departure.stops[departure.destinationIndex];
  const start = nearestPoint(route, origin, 0);
  const end = start < 0 ? -1 : nearestPoint(route, destination, start);
  if (start >= 0 && end > start) return route.slice(start, end + 1);
  return departure.stops
    .slice(departure.originIndex, departure.destinationIndex + 1)
    .filter(hasPosition)
    .map((stop): L.LatLngTuple => [stop.latitude, stop.longitude]);
}

export function MapScreen({
  departure,
  onBack,
}: {
  departure: Departure;
  onBack: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const origin = departure.stops[departure.originIndex];
  const destination = departure.stops[departure.destinationIndex];
  useEffect(() => {
    if (!ref.current) return;
    const map = L.map(ref.current, { zoomControl: true });
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors",
      maxZoom: 18,
    }).addTo(map);
    const color = departure.mode === "rail" ? "#0b3c7a" : "#c5044e";
    const route = decodePolyline(departure.routePoints || "");
    const segment = tripSegment(route, departure);
    if (route.length > 1)
      L.polyline(route, { color, weight: 4, opacity: 0.25 }).addTo(map);
    if (segment.length > 1)
      L.polyline(segment, { color, weight: 6 }).addTo(map);
    departure.stops.forEach((stop, i) => {
      if (!hasPosition(stop)) return;
      const endpoint =
        i === departure.originIndex || i === departure.destinationIndex;
      const onTrip =
        i >= departure.originIndex && i <= departure.destinationIndex;
      const marker = L.circleMarker([stop.latitude, stop.longitude], {
        radius: endpoint ? 8 : onTrip ? 5 : 3,
        color: endpoint ? color : "#fff",
        fillColor: endpoint ? "#fff" : onTrip ? color : "#9a9db8",
        fillOpacity: 1,
        weight: endpoint ? 4 : 2,
      }).bindPopup(stop.name);
      if (endpoint)
        marker.bindTooltip(stop.name, { permanent: true, direction: "top" });
      marker.addTo(map);
    });
    const focus = segment.length > 1 ? segment : route;
    if (focus.length)
      map.fitBounds(L.latLngBounds(focus), { padding: [36, 36] });
    else map.setView([origin?.latitude || 59.9, origin?.longitude || 10.7], 11);
    return () => {
      map.remove();
    };
  }, [departure, origin]);
  return (
    <>
      <button className="back" onClick={onBack}>
        ← Journey
      </button>
      <div className="eyebrow">YOUR TRIP</div>
      <h1>
        {departure.line} {departure.headsign}
      </h1>
      <p className="intro">
        {origin?.name} to {destination?.name}. The faded line shows the rest of
        the route.
      </p>
      <div className="route-map" ref={ref} />
    </>
  );
}
