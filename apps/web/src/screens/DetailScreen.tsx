import { delayMinutes, time } from "../integrations/glance";
import type { Board, LaunchTarget, StopCall } from "../types/transport";
import { useState } from "react";

export function DetailScreen({
  board,
  target,
  onBack,
  onSelect,
  onMap,
}: {
  board: Board | null;
  target: LaunchTarget;
  onBack: () => void;
  onMap: () => void;
  onSelect: (journeyId: string, departureTime: string) => void;
}) {
  const [showPrevious, setShowPrevious] = useState(false);
  const [showNext, setShowNext] = useState(false);
  const group = board?.groups.find((item) => item.id === target.groupId);
  const departure =
    group?.departures.find(
      (item) =>
        item.journeyId === target.journeyId &&
        item.scheduled === target.departureTime,
    ) ?? group?.departures.find((item) => item.journeyId === target.journeyId);
  if (!departure || !group)
    return (
      <>
        <button className="back" onClick={onBack}>
          ← Back
        </button>
        <h1>Journey unavailable</h1>
        <p>Refresh the board and choose a current departure.</p>
      </>
    );
  const origin = departure.originIndex;
  const destination = departure.destinationIndex;
  const stops = departure.stops
    .map((stop, index) => ({ stop, index }))
    .filter(
      ({ index }) =>
        (index >= origin || showPrevious) && (index <= destination || showNext),
    );
  const destinationStop = departure.stops[destination];
  return (
    <>
      <button className="back" onClick={onBack}>
        ← Departures
      </button>
      <div className="detail-header">
        <span className={`badge ${departure.mode === "rail" ? "rail" : "bus"}`}>
          {departure.line}
        </span>
        <div>
          <div className="eyebrow">SERVICE JOURNEY</div>
          <h1>
            {departure.line} {departure.headsign}
          </h1>
          <p>{group.name}</p>
        </div>
      </div>
      <div className="trip-picker">
        {group.departures.map((item) => (
          <button
            key={`${item.journeyId}-${item.scheduled}`}
            className={
              item.journeyId === departure.journeyId &&
              item.scheduled === departure.scheduled
                ? "active"
                : ""
            }
            onClick={() => {
              setShowPrevious(false);
              setShowNext(false);
              onSelect(item.journeyId, item.scheduled);
            }}
          >
            {time(item.expected)}
          </button>
        ))}
      </div>
      <div className="arrival-summary">
        <span>Arrival at {destinationStop?.name}</span>
        <strong>
          {time(destinationStop?.expectedArrival || departure.arrival)}
        </strong>
        <small>
          {departure.cancelled
            ? "Cancelled"
            : delayMinutes(
                  destinationStop?.expectedArrival ?? "",
                  destinationStop?.scheduledArrival ?? "",
                ) > 0
              ? `+${delayMinutes(destinationStop?.expectedArrival ?? "", destinationStop?.scheduledArrival ?? "")} min delay`
              : "Expected arrival"}
        </small>
      </div>
      <section className="timeline-card">
        <h2>Journey stops</h2>
        {origin > 0 && !showPrevious && (
          <button className="text-button" onClick={() => setShowPrevious(true)}>
            Show {origin} previous stops
          </button>
        )}
        <ol className="timeline">
          {stops.map(({ stop, index }) => (
            <StopRow
              key={`${stop.id}-${index}`}
              stop={stop}
              role={
                index === origin
                  ? "origin"
                  : index === destination
                    ? "destination"
                    : ""
              }
            />
          ))}
        </ol>
        {destination < departure.stops.length - 1 && !showNext && (
          <button className="text-button" onClick={() => setShowNext(true)}>
            Show subsequent stops
          </button>
        )}
      </section>
      <button className="map-link" onClick={onMap}>
        See in map ↗
      </button>
    </>
  );
}
function StopRow({ stop, role }: { stop: StopCall; role: string }) {
  const expected = stop.expectedArrival || stop.expectedDeparture;
  const scheduled = stop.scheduledArrival || stop.scheduledDeparture;
  const delay = delayMinutes(expected, scheduled);
  return (
    <li className={role}>
      <span className="timeline-dot" />
      <div>
        <strong>{stop.name}</strong>
        {role && (
          <small className="role-label">
            {role === "origin" ? "Board here" : "Your destination"}
          </small>
        )}
        {stop.platform && <small>Track / stop {stop.platform}</small>}
      </div>
      <div className="stop-time">
        <strong>{time(expected)}</strong>
        {delay > 0 && <small className="alert">+{delay} min</small>}
        {stop.cancelled && <small className="alert">Cancelled</small>}
      </div>
    </li>
  );
}
