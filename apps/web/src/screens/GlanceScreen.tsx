import { useCallback, useEffect, useState } from "react";
import { isNative, sectionNative, time } from "../integrations/glance";
import type {
  GlanceData,
  Preset,
  Profile,
  SectionConfig,
  SectionId,
  SectionType,
} from "../types/glance";

const presets: [Preset, string][] = [
  ["full", "Commute, agenda and weather"],
  ["commute-agenda", "Commute and agenda"],
  ["agenda-weather", "Agenda and weather"],
  ["single", "One section"],
];
const profiles: [Profile, string][] = [
  ["auto", "Automatic (Combined at work, Personal otherwise)"],
  ["personal", "Personal"],
  ["work", "Work"],
  ["combined", "Combined"],
];
const sectionNames: Record<SectionId, string> = {
  commute: "Commute",
  agenda: "Agenda",
  weather: "Weather",
};

function defaults(type: SectionType): SectionConfig {
  return {
    id: "preview",
    type,
    preset: "full",
    single: "agenda",
    profile: "auto",
    weatherAdviceOnly: false,
  };
}

/**
 * A Glance, agenda or weather widget: what it shows right now (computed natively by the widget's
 * own rules) and its few settings. The commute inside a Glance widget, and the stops the weather is
 * read at, are edited with the commute screens.
 */
export function GlanceScreen({
  widgetId,
  type,
  onCommute,
  onCommuteStops,
  onSettings,
  onForecast,
}: {
  widgetId: number;
  type: SectionType;
  onCommute: () => void;
  onCommuteStops: () => void;
  onSettings: () => void;
  onForecast: () => void;
}) {
  const [data, setData] = useState<GlanceData | null>(null);
  const [draft, setDraft] = useState<SectionConfig>(defaults(type));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [saved, setSaved] = useState(false);

  const refresh = useCallback(async () => {
    if (!isNative) return;
    setBusy(true);
    try {
      const result = await sectionNative.refresh({ widgetId, type });
      setData(result);
      setMessage(result.error ? `Showing saved data · ${result.error}` : "");
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }, [widgetId, type]);

  useEffect(() => {
    if (!isNative) return;
    let alive = true;
    void sectionNative.getGlance({ widgetId, type }).then((result) => {
      if (!alive) return;
      setData(result);
      setDraft({ ...defaults(type), ...result.config });
      void refresh();
    });
    return () => {
      alive = false;
    };
  }, [widgetId, type, refresh]);

  async function save() {
    try {
      if (isNative)
        await sectionNative.saveConfig({ widgetId, type, config: draft });
      setSaved(true);
      setData(
        isNative ? await sectionNative.getGlance({ widgetId, type }) : data,
      );
    } catch (error) {
      setMessage(String(error));
    }
  }

  const sections: SectionId[] =
    type === "agenda"
      ? ["agenda"]
      : type === "weather"
        ? ["weather"]
        : draft.preset === "single"
          ? [draft.single ?? "agenda"]
          : draft.preset === "commute-agenda"
            ? ["commute", "agenda"]
            : draft.preset === "agenda-weather"
              ? ["agenda", "weather"]
              : ["commute", "agenda", "weather"];

  return (
    <div className="glance">
      {data?.context && (
        <div className="eyebrow">{data.context.toUpperCase()} GLANCE</div>
      )}
      {sections.includes("commute") && (
        <button className="glance-card glance-link" onClick={onCommute}>
          <strong>Commute</strong>
          <small>Journeys both ways, tracking and the change · open</small>
        </button>
      )}
      {sections.includes("agenda") && (
        <section className="glance-card">
          <div className="glance-head">
            <strong>Upcoming agenda</strong>
            <small>{profileName(data?.profile)}</small>
          </div>
          {!data?.nodecalConfigured ? (
            <p className="glance-empty">
              Connect Nodecal to see your agenda.{" "}
              <button className="text-button" onClick={onSettings}>
                Open settings
              </button>
            </p>
          ) : !data.agenda ? (
            <p className="glance-empty">
              {data.agendaError || "Loading agenda…"}
            </p>
          ) : (
            <>
              {data.agenda.slots.length === 0 && (
                <p className="glance-empty">
                  No upcoming events
                </p>
              )}
              {data.agenda.slots.map((slot) => (
                <div
                  className={`glance-event${slot.futureDay ? " glance-event-future" : ""}`}
                  key={slot.id}
                >
                  <span className="glance-time">
                    <strong>{slot.time}</strong>
                    <small className={slot.ongoing ? "live" : ""}>
                      {slot.countdown}
                    </small>
                  </span>
                  <i style={{ background: slot.color || "#183d78" }} />
                  <span className="glance-title">
                    <strong>{slot.title}</strong>
                    <small className={slot.clash ? "clash" : ""}>
                      {slot.meta}
                    </small>
                  </span>
                  {slot.meetingUrl && (
                    <a href={slot.meetingUrl} target="_blank" rel="noreferrer">
                      Join
                    </a>
                  )}
                </div>
              ))}
              {data.agenda.allDay && (
                <p className="glance-allday">
                  {data.agenda.allDay}
                  <small>{data.agenda.allDayLabel}</small>
                </p>
              )}
              {data.agendaCheckedAt && (
                <p className="hint">
                  From Nodecal at {time(data.agendaCheckedAt)}
                </p>
              )}
            </>
          )}
        </section>
      )}
      {sections.includes("weather") && data?.advice && (
        <section
          className={`glance-card glance-tappable advice-${data.advice.kind}`}
          onClick={onForecast}
        >
          <div className="glance-head">
            <strong>
              {data.advice.kind === "unknown"
                ? "Loading forecast…"
                : data.advice.title}
            </strong>
            {data.weatherNow && (
              <small>
                {data.weatherNow.emoji} {Math.round(data.weatherNow.temp)}° ·{" "}
                {data.weatherNow.place}
              </small>
            )}
          </div>
          {data.advice.reason && <p className="hint">{data.advice.reason}</p>}
          {data.hours && data.hours.length > 0 && (
            <div className="glance-hours">
              {data.hours.slice(0, 8).map((hour) => (
                <span key={hour.t}>
                  <small>{time(hour.t).slice(0, 2)}</small>
                  {hour.emoji}
                  <strong>
                    {hour.temp === null ? "" : `${Math.round(hour.temp)}°`}
                  </strong>
                  <small>{hour.mm >= 0.1 ? hour.mm.toFixed(1) : ""}</small>
                </span>
              ))}
            </div>
          )}
          <p className="hint">
            Checked at{" "}
            {(data.places ?? []).map((place) => place.name).join(" and ") ||
              "your commute’s two ends"}{" "}
            · MET Norway · tap for the longer forecast
          </p>
        </section>
      )}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      <div className="toolbar">
        <button onClick={() => void refresh()} disabled={busy}>
          {busy ? "Refreshing…" : "↻ Refresh"}
        </button>
      </div>

      <section className="config-card">
        <div className="config-head">
          <strong>Widget settings</strong>
          {saved && <span className="toggle">Saved</span>}
        </div>
        <div className="field-grid single">
          {type === "smart" && (
            <label className="field">
              Sections
              <select
                value={draft.preset ?? "full"}
                onChange={(event) => {
                  setSaved(false);
                  setDraft({ ...draft, preset: event.target.value as Preset });
                }}
              >
                {presets.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          )}
          {type === "smart" && draft.preset === "single" && (
            <label className="field">
              Section
              <select
                value={draft.single ?? "agenda"}
                onChange={(event) => {
                  setSaved(false);
                  setDraft({
                    ...draft,
                    single: event.target.value as SectionId,
                  });
                }}
              >
                {(Object.keys(sectionNames) as SectionId[]).map((id) => (
                  <option key={id} value={id}>
                    {sectionNames[id]}
                  </option>
                ))}
              </select>
            </label>
          )}
          {type !== "weather" && (
            <label className="field">
              Agenda profile
              <select
                value={draft.profile ?? "auto"}
                onChange={(event) => {
                  setSaved(false);
                  setDraft({
                    ...draft,
                    profile: event.target.value as Profile,
                  });
                }}
              >
                {profiles.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        {type === "smart" && (
          <div className="config-actions">
            <label>
              <input
                type="checkbox"
                checked={draft.weatherAdviceOnly ?? false}
                onChange={(event) => {
                  setSaved(false);
                  setDraft({
                    ...draft,
                    weatherAdviceOnly: event.target.checked,
                  });
                }}
              />{" "}
              Weather only when there is rain to plan for
            </label>
          </div>
        )}
        <div className="config-actions">
          {type !== "agenda" && (
            <button onClick={onCommuteStops}>
              {type === "weather" ? "Forecast stops" : "Commute stops"}
            </button>
          )}
          <button onClick={onSettings}>Times and Nodecal</button>
        </div>
      </section>
      <button className="primary action" onClick={() => void save()}>
        Save widget
      </button>
      <p className="hint">
        {type === "smart"
          ? "Sections keep their order. Inside the commute windows the commute takes the full card; outside them it shrinks to one line and the agenda gets the room."
          : type === "weather"
            ? "The rain check reads both ends of the commute for the commute windows still ahead today, or the next workday morning."
            : "The agenda shows the next two events in the next two days, all-day events as one row, and the next event’s day when nothing is close."}
      </p>
    </div>
  );
}

function profileName(profile?: string) {
  if (!profile) return "";
  return profile.charAt(0).toUpperCase() + profile.slice(1);
}
