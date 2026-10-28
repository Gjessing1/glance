import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import {
  commuteNative,
  defaultCommuteConfig,
  isNative,
  time,
} from "../integrations/glance";
import {
  changes,
  countdown,
  isActive,
  isRail,
  minutes,
  notices,
  phase,
  rides,
  sharedNotices,
  totalMinutes,
} from "../commute/model";
import type {
  CommuteBoard,
  CommuteConfig,
  CommuteDirection,
  CommuteMode,
  CommuteSection,
  CommuteTarget,
  Journey,
  Leg,
  Situation,
} from "../types/commute";

/**
 * The commute board, after Atlas's commute panel: it answers "when do I leave" before anything
 * else. The journey you would catch by leaving now leads, the following ones are one scannable row
 * each that open into their itinerary, and the via stop is its own mode showing the two boards
 * leaving it. Tracking keeps a journey in place until it arrives; the widget holds the same one.
 */
export function CommuteScreen({
  widgetId,
  target,
  onConfigure,
}: {
  widgetId: number;
  target: CommuteTarget | null;
  onConfigure: (config: CommuteConfig) => void;
}) {
  const [config, setConfig] = useState<CommuteConfig>(defaultCommuteConfig);
  const [board, setBoard] = useState<CommuteBoard | null>(null);
  const [mode, setMode] = useState<CommuteMode>(target?.mode ?? "outbound");
  const [expanded, setExpanded] = useState<string | null>(
    target?.journeyKey || null,
  );
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [now, setNow] = useState(() => Date.now());

  const refresh = useCallback(async () => {
    if (!isNative) return;
    setBusy(true);
    try {
      const result = await commuteNative.refresh({ widgetId, type: "commute" });
      setBoard(result.board);
      setMessage(
        result.error ? `Showing saved journeys · ${result.error}` : "",
      );
    } catch (error) {
      setMessage(`Could not load journeys: ${String(error)}`);
    } finally {
      setBusy(false);
    }
  }, [widgetId]);

  useEffect(() => {
    if (!isNative) {
      const raw = localStorage.getItem("glance-preview-commute");
      if (raw) setConfig(JSON.parse(raw) as CommuteConfig);
      return;
    }
    let alive = true;
    void commuteNative
      .getBoard({ widgetId, type: "commute" })
      .then((data) => {
        if (!alive) return;
        setConfig(data.config);
        setBoard(data.board ?? null);
        // Open where the widget is, unless a tap on the widget said where.
        if (!target) setMode(data.mode);
        void refresh();
      })
      .catch((error: unknown) => setMessage(String(error)));
    return () => {
      alive = false;
    };
  }, [widgetId, target, refresh]);

  useEffect(() => {
    if (target) {
      setMode(target.mode);
      setExpanded(target.journeyKey || null);
    }
  }, [target]);

  useEffect(() => {
    if (!isNative) return;
    const tick = window.setInterval(() => setNow(Date.now()), 30_000);
    const poll = window.setInterval(() => void refresh(), 60_000);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [refresh]);

  async function track(direction: CommuteDirection, journey: Journey | null) {
    try {
      const result = await commuteNative.track({
        widgetId,
        direction,
        journey,
      });
      if (result.board) setBoard(result.board);
    } catch (error) {
      setMessage(String(error));
    }
  }

  const tracked = board?.tracked ?? null;
  const modes = (
    [
      { mode: "outbound", label: "Outbound" },
      { mode: "via", label: "Interchange" },
      { mode: "inbound", label: "Return" },
    ] satisfies { mode: CommuteMode; label: string }[]
  ).filter((item) => item.mode !== "via" || !config.hideInterchange);
  // A hidden interchange falls back to outbound rather than showing a tab that is not there.
  const view: CommuteMode =
    mode === "via" && config.hideInterchange ? "outbound" : mode;
  const toggle = (key: string) =>
    setExpanded((current) => (current === key ? null : key));

  return (
    <div className="commute">
      <div className="commute-head">
        <div>
          <div className="eyebrow">COMMUTE</div>
          <h1>{config.name}</h1>
        </div>
        <button className="ghost" onClick={() => onConfigure(config)}>
          Edit
        </button>
      </div>
      <div className="commute-modes" role="tablist" aria-label="Direction">
        {modes.map((item) => (
          <button
            key={item.mode}
            role="tab"
            aria-selected={view === item.mode}
            onClick={() => setMode(item.mode)}
          >
            {item.label}
          </button>
        ))}
      </div>
      {tracked && (
        <TrackedCard
          journey={tracked.journey}
          now={now}
          onStop={() => void track(tracked.direction, null)}
        />
      )}
      {!board ? (
        <p className="empty">
          {isNative
            ? "Checking upcoming journeys…"
            : "Add the Glance commute widget on Android to load live journeys."}
        </p>
      ) : view === "via" ? (
        board.hops.map((hop) => (
          <HopBoard
            key={hop.id}
            hop={hop}
            now={now}
            expanded={expanded}
            onToggle={toggle}
          />
        ))
      ) : (
        <Journeys
          section={board[view]}
          trackedKey={tracked?.direction === view ? tracked.journey.key : null}
          now={now}
          expanded={expanded}
          onToggle={toggle}
          onTrack={(journey) =>
            void track(
              view,
              tracked?.journey.key === journey.key ? null : journey,
            )
          }
        />
      )}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      <div className="commute-foot">
        <span className={board?.stale ? "alert" : undefined}>
          Entur ·{" "}
          {board ? `checked ${time(board.checkedAt)}` : "awaiting first update"}
        </span>
        {isNative && (
          <button onClick={() => void refresh()} disabled={busy}>
            {busy ? "Refreshing…" : "↻ Refresh"}
          </button>
        )}
      </div>
    </div>
  );
}

function Journeys({
  section,
  trackedKey,
  now,
  expanded,
  onToggle,
  onTrack,
}: {
  section: CommuteSection;
  trackedKey: string | null;
  now: number;
  expanded: string | null;
  onToggle: (key: string) => void;
  onTrack: (journey: Journey) => void;
}) {
  // The tracked journey has its own card above; departures already gone are dropped.
  const list = section.journeys.filter(
    (journey) =>
      journey.key !== trackedKey &&
      new Date(journey.rideStart).getTime() > now - 60_000,
  );
  if (list.length === 0)
    return (
      <p className="empty">
        {section.noService
          ? `No connection found from ${section.from} to ${section.to}.`
          : "No journeys in the next hours."}
      </p>
    );
  const shared = sharedNotices(list);
  const [lead, ...rest] = list;
  return (
    <>
      <Disruptions items={shared} />
      <section className="commute-card lead">
        <div className="eyebrow">IF YOU LEAVE NOW</div>
        <JourneyBody
          journey={lead!}
          big
          countdownText={countdown(lead!.rideStart, now)}
          open={expanded === lead!.key}
          hidden={shared}
        />
        <div className="commute-actions">
          <button className="primary small" onClick={() => onTrack(lead!)}>
            Track this journey
          </button>
          <button className="ghost" onClick={() => onToggle(lead!.key)}>
            {expanded === lead!.key ? "Hide details" : "Details"}
          </button>
        </div>
      </section>
      {rest.length > 0 && <h2 className="commute-section">Next journeys</h2>}
      <ol className="commute-list">
        {rest.map((journey) => (
          <li key={journey.key} className="commute-card">
            {/* A div, not a button: the notices inside it are buttons of their own. */}
            <div
              className="commute-row-head"
              role="button"
              tabIndex={0}
              aria-expanded={expanded === journey.key}
              onClick={() => onToggle(journey.key)}
              onKeyDown={(event) => {
                if (event.target !== event.currentTarget) return;
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onToggle(journey.key);
                }
              }}
            >
              <JourneyBody
                journey={journey}
                open={expanded === journey.key}
                hidden={shared}
              />
            </div>
            {expanded === journey.key && (
              <div className="commute-actions">
                <button
                  className="primary small"
                  onClick={() => onTrack(journey)}
                >
                  Track this journey
                </button>
              </div>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}

/** Collapsed: the scan line, the ribbon, the change and notices. Open: the itinerary in their place. */
function JourneyBody({
  journey,
  open,
  hidden,
  big = false,
  countdownText,
}: {
  journey: Journey;
  open: boolean;
  hidden: Situation[];
  big?: boolean;
  countdownText?: string;
}) {
  const first = rides(journey)[0];
  return (
    <>
      <ScanLine journey={journey} big={big} countdownText={countdownText} />
      {open ? (
        <Itinerary journey={journey} />
      ) : (
        <>
          <Ribbon journey={journey} />
          {changes(journey).map((change) => (
            <p
              key={change.at}
              className={`commute-change${change.broken ? " broken" : change.tight ? " tight" : ""}`}
            >
              ⇄ {change.at} ·{" "}
              {change.broken
                ? "connection missed"
                : `${change.planned} min${change.live !== change.planned ? ` · ${change.tight ? "⚠ " : ""}${change.live} min est.` : ""}`}
            </p>
          ))}
          {first?.cancelled && (
            <p className="commute-change broken">{first.line} cancelled</p>
          )}
          <Disruptions
            items={notices(journey).filter(
              (item) => !hidden.some((other) => other.summary === item.summary),
            )}
          />
        </>
      )}
    </>
  );
}

/** Scheduled times lead; the live ones follow on their own line only when something moved. */
function ScanLine({
  journey,
  big,
  countdownText,
}: {
  journey: Journey;
  big: boolean;
  countdownText?: string;
}) {
  const first = rides(journey)[0];
  const moved =
    minutes(journey.aimedStart, journey.expectedStart) !== 0 ||
    minutes(journey.aimedEnd, journey.expectedEnd) !== 0;
  return (
    <div className={`commute-scan${big ? " big" : ""}`}>
      <span>
        <strong>{time(first?.aimedStart ?? journey.aimedStart)}</strong>
        {countdownText && <em className="countdown">{countdownText}</em>}
        <i aria-hidden="true">→</i>
        <strong>{time(journey.aimedEnd)}</strong>
        <small>{totalMinutes(journey)} min</small>
      </span>
      {moved && (
        <span className="commute-live">
          Live {time(first?.expectedStart ?? journey.expectedStart)} →{" "}
          {time(journey.expectedEnd)}
        </span>
      )}
    </div>
  );
}

/** The journey as one bar, each vehicle as wide as it is long, changes and walks as gaps. */
function Ribbon({ journey }: { journey: Journey }) {
  return (
    <div className="ribbon" aria-hidden="true">
      {journey.legs.map((leg, index) => {
        const weight = Math.max(1, minutes(leg.expectedStart, leg.expectedEnd));
        return leg.mode === "foot" ? (
          <span key={index} className="gap" style={{ flexGrow: weight }} />
        ) : (
          <span
            key={index}
            className={`seg ${isRail(leg) ? "rail" : "bus"}${leg.cancelled ? " cancelled" : ""}`}
            style={{ flexGrow: weight }}
          >
            {leg.line}
            {minutes(leg.aimedStart, leg.expectedStart) > 0 && (
              <u>+{minutes(leg.aimedStart, leg.expectedStart)}</u>
            )}
          </span>
        );
      })}
    </div>
  );
}

function LegTime({ aimed, expected }: { aimed: string; expected: string }) {
  const late = minutes(aimed, expected);
  return (
    <span className="leg-time">
      <strong>{time(aimed)}</strong>
      {late !== 0 && (
        <em className={late > 0 ? "alert" : undefined}>
          {time(expected)} est.
        </em>
      )}
    </span>
  );
}

/** Each leg's departure and arrival with its platform, the change between legs, the walks at either end. */
function Itinerary({
  journey,
  activeKey,
}: {
  journey: Journey;
  activeKey?: string;
}) {
  const list = rides(journey);
  const connections = changes(journey);
  return (
    <ol className="itinerary">
      {journey.legs.map((leg, index) => {
        if (leg.mode === "foot") {
          const between = index > 0 && index < journey.legs.length - 1;
          if (between) return null;
          return (
            <li key={index} className="walk">
              <span className="leg-time">
                <em>{time(leg.expectedStart)}</em>
              </span>
              <span>
                🚶 Walk{" "}
                {Math.max(1, minutes(leg.expectedStart, leg.expectedEnd))} min
                to {leg.to} ({leg.distance} m)
              </span>
            </li>
          );
        }
        const rideIndex = list.indexOf(leg);
        const change = rideIndex > 0 ? connections[rideIndex - 1] : undefined;
        const key = `${leg.serviceJourneyId}@${leg.aimedStart}`;
        return (
          <Fragment key={index}>
            {change && <ChangeRow change={change} />}
            <li
              className={`ride ${isRail(leg) ? "rail" : "bus"}${key === activeKey ? " active" : ""}`}
            >
              <LegTime aimed={leg.aimedStart} expected={leg.expectedStart} />
              <div>
                <strong>
                  {leg.from}
                  {leg.fromQuay &&
                    ` · ${isRail(leg) ? "track" : "stop"} ${leg.fromQuay}`}
                </strong>
                <span className="leg-line">
                  <span className={`badge ${isRail(leg) ? "rail" : "bus"}`}>
                    {isRail(leg) ? "▰" : "▣"} {leg.line}
                  </span>
                  {leg.towards && <span>→ {leg.towards}</span>}
                  <small>{minutes(leg.aimedStart, leg.aimedEnd)} min</small>
                </span>
                {leg.cancelled && (
                  <p className="commute-change broken">Cancelled</p>
                )}
                <Disruptions items={leg.situations} />
              </div>
            </li>
            <li className={`ride end ${isRail(leg) ? "rail" : "bus"}`}>
              <LegTime aimed={leg.aimedEnd} expected={leg.expectedEnd} />
              <div>
                <strong>
                  {leg.to}
                  {leg.toQuay &&
                    ` · ${isRail(leg) ? "track" : "stop"} ${leg.toQuay}`}
                </strong>
              </div>
            </li>
          </Fragment>
        );
      })}
    </ol>
  );
}

function ChangeRow({ change }: { change: ReturnType<typeof changes>[number] }) {
  return (
    <li
      className={`change${change.broken ? " broken" : change.tight ? " tight" : ""}`}
    >
      <span className="leg-time" />
      <span>
        {change.broken || change.tight ? "⚠ " : ""}
        {change.planned} min change
        {change.walk > 0 && ` · ${change.walk} m walk`}
        {change.broken
          ? " · connection missed"
          : change.live !== change.planned && ` · ${change.live} min est.`}
      </span>
    </li>
  );
}

/** One line per notice until tapped, the operator's headline; the tap opens the whole message. */
function Disruptions({ items }: { items: Situation[] }) {
  const [open, setOpen] = useState<string | null>(null);
  if (items.length === 0) return null;
  return (
    <ul className="disruptions">
      {items.map((item) => {
        const key = item.id || item.summary;
        const expanded = open === key;
        return (
          <li key={key} className={isActive(item) ? "active" : "info"}>
            <button
              aria-expanded={expanded}
              onClick={(event) => {
                // Inside a journey row, opening a notice must not also toggle the row.
                event.stopPropagation();
                setOpen(expanded ? null : key);
              }}
            >
              {isActive(item) ? "⚠" : "ⓘ"} {item.summary}
            </button>
            {expanded && (
              <div>
                {item.detail && <p>{item.detail}</p>}
                {item.advice && <p>{item.advice}</p>}
                {item.url && (
                  <a href={item.url} target="_blank" rel="noreferrer noopener">
                    Operator’s notice
                  </a>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function TrackedCard({
  journey,
  now,
  onStop,
}: {
  journey: Journey;
  now: number;
  onStop: () => void;
}) {
  const current = rides(journey).find(
    (ride) => new Date(ride.expectedEnd).getTime() > now,
  );
  return (
    <section className="commute-card tracked">
      <div className="eyebrow">
        TRACKING · {phase(journey, now).toUpperCase()}
      </div>
      <ScanLine journey={journey} big={false} />
      <Ribbon journey={journey} />
      <Itinerary
        journey={journey}
        activeKey={
          current
            ? `${current.serviceJourneyId}@${current.aimedStart}`
            : undefined
        }
      />
      <div className="commute-actions">
        <button className="ghost" onClick={onStop}>
          Stop tracking
        </button>
      </div>
    </section>
  );
}

/**
 * One leg's board at the interchange. A row opens into its stops and times like a journey row, and
 * the departure a widget tap named opens already, scrolled into view.
 */
function HopBoard({
  hop,
  now,
  expanded,
  onToggle,
}: {
  hop: CommuteSection;
  now: number;
  expanded: string | null;
  onToggle: (key: string) => void;
}) {
  const list = hop.journeys.filter(
    (journey) => new Date(journey.rideStart).getTime() > now - 60_000,
  );
  const shared = sharedNotices(list);
  const target = useRef<HTMLLIElement | null>(null);
  const named = list.some((journey) => journey.key === expanded);
  useEffect(() => {
    if (named) target.current?.scrollIntoView({ block: "center" });
    // Only when the named departure first shows up, not on every tick or toggle.
  }, [named]);
  return (
    <section className="commute-card">
      <h2 className="hop-title">
        {hop.from} <i aria-hidden="true">→</i> {hop.to}
      </h2>
      <Disruptions items={shared} />
      {list.length === 0 ? (
        <p className="empty">No departures in the next hours.</p>
      ) : (
        <ul className="hop-rows">
          {list.map((journey) => {
            const ride: Leg = rides(journey)[0]!;
            const open = expanded === journey.key;
            return (
              <li
                key={journey.key}
                ref={open ? target : undefined}
                className={open ? "open" : undefined}
                role="button"
                tabIndex={0}
                aria-expanded={open}
                onClick={() => onToggle(journey.key)}
                onKeyDown={(event) => {
                  if (event.target !== event.currentTarget) return;
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onToggle(journey.key);
                  }
                }}
              >
                <LegTime
                  aimed={ride.aimedStart}
                  expected={ride.expectedStart}
                />
                <span className={`badge ${isRail(ride) ? "rail" : "bus"}`}>
                  {isRail(ride) ? "▰" : "▣"} {ride.line}
                </span>
                <span className="hop-arrival">
                  arrives {time(journey.expectedEnd)}
                  {ride.cancelled && (
                    <span className="alert"> · cancelled</span>
                  )}
                </span>
                <span className="hop-when">
                  {countdown(ride.expectedStart, now)}
                  {ride.fromQuay && (
                    <small>
                      {isRail(ride) ? "track" : "stop"} {ride.fromQuay}
                    </small>
                  )}
                </span>
                <Disruptions
                  items={ride.situations.filter(
                    (item) =>
                      isActive(item) &&
                      !shared.some((other) => other.summary === item.summary),
                  )}
                />
                {open && (
                  <div className="hop-itinerary">
                    <Itinerary journey={journey} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
