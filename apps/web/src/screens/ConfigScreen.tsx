import { useState } from "react";
import type { GroupConfig, WidgetConfig } from "../types/transport";

export function ConfigScreen({
  config,
  onSave,
  onBack,
}: {
  config: WidgetConfig;
  onSave: (config: WidgetConfig) => Promise<void>;
  onBack: () => void;
}) {
  const [draft, setDraft] = useState<WidgetConfig>(structuredClone(config));
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  function update(index: number, patch: Partial<GroupConfig>) {
    setDraft((current) => ({
      ...current,
      groups: current.groups.map((group, i) =>
        i === index ? { ...group, ...patch } : group,
      ),
    }));
  }
  function move(index: number, direction: number) {
    const next = structuredClone(draft);
    const target = index + direction;
    if (target < 0 || target >= next.groups.length) return;
    [next.groups[index], next.groups[target]] = [
      next.groups[target]!,
      next.groups[index]!,
    ];
    setDraft(next);
  }
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
      <div className="eyebrow">TRANSPORT WIDGET</div>
      <h1>Make it yours</h1>
      <p className="intro">
        Set the journeys and order shown on your home screen.
      </p>
      <label className="field">
        Widget name
        <input
          value={draft.name}
          onChange={(event) => setDraft({ ...draft, name: event.target.value })}
        />
      </label>
      <div className="config-list">
        {draft.groups.map((group, index) => (
          <section className="config-card" key={group.id}>
            <div className="config-head">
              <strong>
                {index + 1}. {group.name}
              </strong>
              <label className="toggle">
                <input
                  type="checkbox"
                  checked={group.enabled}
                  onChange={(event) =>
                    update(index, { enabled: event.target.checked })
                  }
                />{" "}
                Show
              </label>
            </div>
            <div className="field-grid">
              <label className="field">
                Display name
                <input
                  value={group.name}
                  onChange={(event) =>
                    update(index, { name: event.target.value })
                  }
                />
              </label>
              <label className="field">
                Departures
                <select
                  value={group.count}
                  onChange={(event) =>
                    update(index, { count: Number(event.target.value) })
                  }
                >
                  {[1, 2, 3, 4, 5, 6, 7, 8].map((count) => (
                    <option key={count}>{count}</option>
                  ))}
                </select>
              </label>
              <label className="field">
                Origin stop ID
                <input
                  value={group.origin}
                  onChange={(event) =>
                    update(index, { origin: event.target.value })
                  }
                />
              </label>
              <label className="field">
                Destination stop ID
                <input
                  value={group.destination}
                  onChange={(event) =>
                    update(index, { destination: event.target.value })
                  }
                />
              </label>
              <label className="field">
                Transport
                <select
                  value={group.mode}
                  onChange={(event) =>
                    update(index, { mode: event.target.value })
                  }
                >
                  <option value="bus">Bus</option>
                  <option value="rail">Train</option>
                  <option value="">Any</option>
                </select>
              </label>
              <label className="field">
                Lines, comma separated
                <input
                  value={group.lines}
                  onChange={(event) =>
                    update(index, { lines: event.target.value })
                  }
                  placeholder="Any line"
                />
              </label>
              <label className="field">
                Lines boarding elsewhere
                <input
                  value={group.lineOrigins ?? ""}
                  onChange={(event) =>
                    update(index, { lineOrigins: event.target.value })
                  }
                  placeholder="71=NSR:StopPlace:70146"
                />
              </label>
            </div>
            <div className="config-actions">
              <label>
                <input
                  type="checkbox"
                  checked={group.fastOnly}
                  onChange={(event) =>
                    update(index, { fastOnly: event.target.checked })
                  }
                />{" "}
                Fast rail only
              </label>
              <div>
                <button onClick={() => move(index, -1)} disabled={index === 0}>
                  ↑
                </button>
                <button
                  onClick={() => move(index, 1)}
                  disabled={index === draft.groups.length - 1}
                >
                  ↓
                </button>
              </div>
            </div>
          </section>
        ))}
      </div>
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
        {saving ? "Saving…" : "Save widget"}
      </button>
      <p className="hint">
        Stop IDs use Entur’s NSR format. Add a home screen widget to give it an
        independent configuration.
      </p>
    </>
  );
}
