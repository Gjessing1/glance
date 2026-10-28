import { useState } from "react";
import type { CommuteConfig } from "../types/commute";

const stops = [
  ["from", "fromName", "From"],
  ["via", "viaName", "Change at"],
  ["to", "toName", "To"],
] as const;

export function CommuteConfigScreen({
  config,
  onSave,
  onBack,
}: {
  config: CommuteConfig;
  onSave: (config: CommuteConfig) => Promise<void>;
  onBack: () => void;
}) {
  const [draft, setDraft] = useState<CommuteConfig>(structuredClone(config));
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  async function save() {
    setSaving(true);
    setError("");
    try {
      await onSave(draft);
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
      <div className="eyebrow">COMMUTE WIDGET</div>
      <h1>Your commute</h1>
      <p className="intro">
        Three stops: where you start, where you change, and where you are going.
        The widget shows whole journeys both ways and the departures leaving the
        change.
      </p>
      <label className="field">
        Commute name
        <input
          value={draft.name}
          onChange={(event) => setDraft({ ...draft, name: event.target.value })}
        />
      </label>
      <section className="config-card">
        <div className="field-grid">
          {stops.map(([id, name, label]) => (
            <div className="field-pair" key={id}>
              <label className="field">
                {label} · stop ID
                <input
                  value={draft[id]}
                  onChange={(event) =>
                    setDraft({ ...draft, [id]: event.target.value })
                  }
                />
              </label>
              <label className="field">
                {label} · short name
                <input
                  value={draft[name]}
                  onChange={(event) =>
                    setDraft({ ...draft, [name]: event.target.value })
                  }
                  placeholder="Entur’s stop name"
                />
              </label>
            </div>
          ))}
          <label className="field">
            Journeys each way
            <select
              value={draft.count}
              onChange={(event) =>
                setDraft({ ...draft, count: Number(event.target.value) })
              }
            >
              {[1, 2, 3, 4, 5, 6, 7, 8].map((count) => (
                <option key={count}>{count}</option>
              ))}
            </select>
          </label>
          <label className="field">
            On weekends
            <select
              value={draft.weekend ?? "weekday"}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  weekend: event.target.value as "weekday" | "hide",
                })
              }
            >
              <option value="weekday">Show as on weekdays</option>
              <option value="hide">Rest (“No commute today”)</option>
            </select>
          </label>
          <label className="field">
            Lines, comma separated
            <input
              value={draft.lines}
              onChange={(event) =>
                setDraft({ ...draft, lines: event.target.value })
              }
              placeholder="Any line"
            />
          </label>
        </div>
        <div className="config-actions">
          <label>
            <input
              type="checkbox"
              checked={draft.hideInterchange ?? false}
              onChange={(event) =>
                setDraft({ ...draft, hideInterchange: event.target.checked })
              }
            />{" "}
            Hide the Interchange button
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
        {saving ? "Saving…" : "Save commute"}
      </button>
      <p className="hint">
        Stop IDs use Entur’s NSR format. A parent stop such as Asker
        (NSR:StopPlace:59616) covers both its bus and rail platforms. Short
        names label the journeys and the boards at the change.
      </p>
    </>
  );
}
