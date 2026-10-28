import { delayMinutes, time } from "../integrations/glance";
import type { Board, WidgetConfig } from "../types/transport";

export function BoardScreen({
  board,
  config,
  onOpen,
}: {
  board: Board | null;
  config: WidgetConfig;
  onOpen: (groupId: string, journeyId: string, departureTime: string) => void;
}) {
  const groups =
    board?.groups ??
    config.groups
      .filter((group) => group.enabled)
      .map((group) => ({
        id: group.id,
        name: group.name,
        mode: group.mode,
        departures: [],
      }));
  return (
    <div className="board-list">
      {groups.map((group) => (
        <section className="board-card" key={group.id}>
          <div className="card-heading">
            <div>
              <span className="mode-icon">
                {group.mode === "rail" ? "▰" : "▣"}
              </span>
              <h2>{group.name}</h2>
            </div>
            <span className="chevron">↗</span>
          </div>
          {group.departures.length ? (
            <div className="departure-strip">
              {group.departures.map((departure) => {
                const delay = delayMinutes(
                  departure.expected,
                  departure.scheduled,
                );
                return (
                  <button
                    className="departure"
                    key={`${departure.journeyId}-${departure.scheduled}`}
                    onClick={() =>
                      onOpen(group.id, departure.journeyId, departure.scheduled)
                    }
                  >
                    <span
                      className={`badge ${group.mode === "rail" ? "rail" : "bus"}`}
                    >
                      {group.mode === "rail" ? "▰" : "▣"} {departure.line}
                    </span>
                    <strong>{time(departure.expected)}</strong>
                    <small>
                      {departure.cancelled ? (
                        <span className="alert">Cancelled</span>
                      ) : delay > 0 ? (
                        <span className="alert">+{delay} min</span>
                      ) : group.mode === "rail" && departure.platform ? (
                        `Track ${departure.platform}`
                      ) : (
                        "On time"
                      )}
                    </small>
                  </button>
                );
              })}
            </div>
          ) : (
            <p className="empty">
              {board
                ? "No matching departures in the next four hours."
                : "Add the Android widget to load live departures."}
            </p>
          )}
        </section>
      ))}
    </div>
  );
}
