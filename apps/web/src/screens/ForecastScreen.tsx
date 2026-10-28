import { useEffect, useState } from "react";
import { isNative, sectionNative, time } from "../integrations/glance";
import type { Forecast, ForecastStep, PlaceForecast } from "../types/glance";

const weekday = new Intl.DateTimeFormat("en-GB", { weekday: "short" });

function dayName(date: string, index: number): string {
  if (index === 0) return "Today";
  if (index === 1) return "Tomorrow";
  return weekday.format(new Date(`${date}T12:00:00`));
}

/** "14" for an hourly step, "12–18" for one of MET's six-hour steps. */
function stepLabel(step: ForecastStep): string {
  const start = time(step.t).slice(0, 2);
  if (step.span <= 1) return start;
  const end = (Number(start) + step.span) % 24;
  return `${start}–${String(end).padStart(2, "0")}`;
}

/**
 * The longer forecast behind a tap on a widget's weather: the advice, then for each end of the
 * commute the next 48 hours and the coming days, from MET Norway. A tap on a day opens its hours.
 */
export function ForecastScreen({
  widgetId,
  onBack,
}: {
  widgetId: number;
  onBack: () => void;
}) {
  const [forecast, setForecast] = useState<Forecast | null>(null);
  const [place, setPlace] = useState("");
  const [openDay, setOpenDay] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!isNative) return;
    void sectionNative
      .getForecast({ widgetId })
      .then((result) => {
        setForecast(result);
        setPlace(result.here || result.places[0]?.name || "");
      })
      .catch((problem: unknown) => setError(String(problem)));
  }, [widgetId]);

  const shown: PlaceForecast | undefined =
    forecast?.places.find((item) => item.name === place) ?? forecast?.places[0];
  const wettest = Math.max(1, ...(shown?.days.map((day) => day.mm) ?? [1]));

  return (
    <>
      <button className="back" onClick={onBack}>
        ← Back
      </button>
      <div className="eyebrow">FORECAST</div>
      <h1>
        {shown?.now
          ? `${shown.now.emoji} ${Math.round(shown.now.temp)}°`
          : "Weather"}
      </h1>
      {forecast?.advice && forecast.advice.kind !== "unknown" && (
        <p className="intro">
          <strong>{forecast.advice.title}</strong>
          {forecast.advice.reason && ` · ${forecast.advice.reason}`}
        </p>
      )}
      {error && <p className="notice">{error}</p>}
      {!forecast && !error && <p className="hint">Loading forecast…</p>}
      {forecast && forecast.places.length > 1 && (
        <div
          className="commute-modes forecast-places"
          role="tablist"
          aria-label="Place"
        >
          {forecast.places.map((item) => (
            <button
              key={item.name}
              role="tab"
              aria-selected={item.name === shown?.name}
              onClick={() => setPlace(item.name)}
            >
              {item.name}
            </button>
          ))}
        </div>
      )}
      {shown && (
        <>
          <section className="glance-card">
            <div className="glance-head">
              <strong>Next 48 hours</strong>
              <small>{shown.name}</small>
            </div>
            <div className="forecast-hours">
              {shown.hours.map((hour) => (
                <span key={hour.t}>
                  <small>{time(hour.t).slice(0, 2)}</small>
                  {hour.emoji}
                  <strong>
                    {hour.temp === null ? "" : `${Math.round(hour.temp)}°`}
                  </strong>
                  <small className={hour.p >= 50 ? "wet" : ""}>
                    {hour.mm >= 0.1 ? hour.mm.toFixed(1) : ""}
                  </small>
                </span>
              ))}
            </div>
          </section>
          <section className="glance-card">
            <div className="glance-head">
              <strong>Coming days</strong>
              <small>mm rain</small>
            </div>
            {shown.days.map((day, index) => {
              const open = openDay === day.date && day.steps?.length > 0;
              return (
                <div className="forecast-day-wrap" key={day.date}>
                  <button
                    className="forecast-day"
                    aria-expanded={open}
                    disabled={!day.steps?.length}
                    onClick={() => setOpenDay(open ? "" : day.date)}
                  >
                    <span className="forecast-name">
                      {dayName(day.date, index)}
                    </span>
                    <span className="forecast-emoji">{day.emoji}</span>
                    <span className="forecast-temp">
                      <small>{Math.round(day.low)}°</small>
                      <strong>{Math.round(day.high)}°</strong>
                    </span>
                    <span className="forecast-rain">
                      <i style={{ width: `${(day.mm / wettest) * 100}%` }} />
                      <small>{day.mm >= 0.1 ? day.mm.toFixed(1) : ""}</small>
                    </span>
                    <span className="forecast-chevron" aria-hidden="true">
                      ›
                    </span>
                  </button>
                  {open && (
                    <div className="forecast-hours forecast-steps">
                      {day.steps.map((step) => (
                        <span key={step.t}>
                          <small>{stepLabel(step)}</small>
                          {step.emoji}
                          <strong>
                            {step.temp === null
                              ? ""
                              : `${Math.round(step.temp)}°`}
                          </strong>
                          <small className={step.p >= 50 ? "wet" : ""}>
                            {step.mm >= 0.1 ? step.mm.toFixed(1) : ""}
                          </small>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </section>
          <p className="hint">
            MET Norway
            {shown.checkedAt > 0 &&
              ` · updated ${time(new Date(shown.checkedAt).toISOString())}`}
          </p>
        </>
      )}
    </>
  );
}
