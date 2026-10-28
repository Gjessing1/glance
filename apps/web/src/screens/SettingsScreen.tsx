import { useEffect, useState } from "react";
import { isNative, sectionNative } from "../integrations/glance";
import type { GlanceSettings, TimeSettings } from "../types/glance";

const defaults: GlanceSettings = {
  nodecalUrl: "",
  hasToken: false,
  refreshMinutes: 30,
  time: {
    workStart: "08:00",
    workEnd: "17:00",
    morningStart: "07:00",
    morningEnd: "12:00",
    eveningStart: "12:00",
    eveningEnd: "18:30",
    switchAt: "12:00",
    focusStart: "03:00",
  },
  weather: { probability: 50, amount: 0.3, heavy: 2 },
};

const times: [keyof TimeSettings, string][] = [
  ["workStart", "Work starts"],
  ["workEnd", "Work ends"],
  ["morningStart", "Morning commute from"],
  ["morningEnd", "Morning commute until"],
  ["eveningStart", "Return commute from"],
  ["eveningEnd", "Return commute until"],
  ["switchAt", "Commute turns for home at"],
  ["focusStart", "Commute focus from"],
];

/**
 * App-wide settings every Glance widget reads: the Nodecal connection for the agenda, the time
 * rules (work hours, commute windows, direction switch) and the rain thresholds. The token is
 * written to native storage and only reported back as "saved".
 */
export function SettingsScreen({ onBack }: { onBack: () => void }) {
  const [draft, setDraft] = useState<GlanceSettings>(defaults);
  const [token, setToken] = useState("");
  const [test, setTest] = useState("");
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!isNative) return;
    void sectionNative.getSettings().then(setDraft);
  }, []);

  async function runTest() {
    setTesting(true);
    setTest("");
    try {
      const result = await sectionNative.testNodecal({
        nodecalUrl: draft.nodecalUrl,
        token: token || undefined,
      });
      setTest(
        result.ok
          ? `Connected · ${result.events ?? 0} events this week in ${result.calendars ?? 0} calendars`
          : `Failed · ${result.error ?? "unknown error"}`,
      );
    } catch (problem) {
      setTest(`Failed · ${String(problem)}`);
    } finally {
      setTesting(false);
    }
  }

  async function save() {
    setSaving(true);
    setError("");
    try {
      if (isNative)
        await sectionNative.saveSettings({
          nodecalUrl: draft.nodecalUrl,
          token: token || undefined,
          refreshMinutes: draft.refreshMinutes,
          time: draft.time,
          weather: draft.weather,
        });
      onBack();
    } catch (problem) {
      setError(String(problem));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <button className="back" onClick={onBack}>
        ← Back
      </button>
      <div className="eyebrow">SETTINGS</div>
      <h1>Glance settings</h1>
      <p className="intro">
        Shared by every widget: where the agenda comes from, when you work and
        commute, and when rain is worth an umbrella.
      </p>

      <section className="config-card">
        <div className="config-head">
          <strong>Nodecal agenda</strong>
          <span className="toggle">
            {draft.hasToken ? "Token saved" : "No token yet"}
          </span>
        </div>
        <div className="field-grid single">
          <label className="field">
            Nodecal address
            <input
              type="url"
              inputMode="url"
              value={draft.nodecalUrl}
              placeholder="https://nodecal.gjessing.io"
              onChange={(event) =>
                setDraft({ ...draft, nodecalUrl: event.target.value })
              }
            />
          </label>
          <label className="field">
            Integration token
            <input
              type="password"
              autoComplete="off"
              value={token}
              placeholder={
                draft.hasToken ? "Saved · paste to replace" : "From Nodecal"
              }
              onChange={(event) => setToken(event.target.value)}
            />
          </label>
        </div>
        <div className="config-actions">
          <span>{test}</span>
          <span>
            {draft.hasToken && (
              <button
                onClick={() =>
                  void sectionNative
                    .saveSettings({ clearToken: true })
                    .then(() => setDraft({ ...draft, hasToken: false }))
                }
              >
                Forget token
              </button>
            )}
            <button
              disabled={testing || !isNative}
              onClick={() => void runTest()}
            >
              {testing ? "Testing…" : "Test connection"}
            </button>
          </span>
        </div>
        <p className="hint">
          Create the token in Nodecal under Settings → Integrations. It is kept
          in the app’s private storage and only sent to Nodecal.
        </p>
      </section>

      <section className="config-card">
        <div className="config-head">
          <strong>Work and commute times</strong>
          <span className="toggle">Mon–Fri</span>
        </div>
        <div className="field-grid">
          {times.map(([key, label]) => (
            <label className="field" key={key}>
              {label}
              <input
                type="time"
                value={draft.time[key]}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    time: { ...draft.time, [key]: event.target.value },
                  })
                }
              />
            </label>
          ))}
        </div>
        <p className="hint">
          On workdays the widget turns to the commute at the focus hour and
          stays there until the return window ends: the commute shows, the
          weather is the commute’s rain check and the agenda uses the Combined
          profile. Evenings, nights and weekends show the Personal agenda and
          the forecast instead. Work hours decide which end of the commute the
          weather is for.
        </p>
      </section>

      <section className="config-card">
        <div className="config-head">
          <strong>Updates</strong>
        </div>
        <div className="field-grid single">
          <label className="field">
            Refresh in the background every
            <select
              value={draft.refreshMinutes}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  refreshMinutes: Number(
                    event.target.value,
                  ) as GlanceSettings["refreshMinutes"],
                })
              }
            >
              <option value={15}>15 minutes</option>
              <option value={30}>30 minutes</option>
              <option value={60}>hour</option>
            </select>
          </label>
        </div>
        <p className="hint">
          Inside the commute windows departures are also refreshed every 15
          minutes while the phone is in use. The ↻ button on a widget refreshes
          everything at once. Countdowns tick from saved data either way.
        </p>
      </section>

      <section className="config-card">
        <div className="config-head">
          <strong>Rain advice</strong>
        </div>
        <div className="field-grid">
          <label className="field">
            Likely from (%)
            <input
              type="number"
              min={0}
              max={100}
              value={draft.weather.probability}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  weather: {
                    ...draft.weather,
                    probability: Number(event.target.value),
                  },
                })
              }
            />
          </label>
          <label className="field">
            Rain from (mm/h)
            <input
              type="number"
              step={0.1}
              min={0}
              value={draft.weather.amount}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  weather: {
                    ...draft.weather,
                    amount: Number(event.target.value),
                  },
                })
              }
            />
          </label>
          <label className="field">
            Heavy from (mm/h)
            <input
              type="number"
              step={0.5}
              min={0.1}
              value={draft.weather.heavy}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  weather: {
                    ...draft.weather,
                    heavy: Number(event.target.value),
                  },
                })
              }
            />
          </label>
        </div>
      </section>

      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <button
        className="primary action"
        disabled={saving}
        onClick={() => void save()}
      >
        {saving ? "Saving…" : "Save settings"}
      </button>
    </>
  );
}
