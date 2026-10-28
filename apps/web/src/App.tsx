import { useCallback, useEffect, useState } from "react";
import {
  commuteNative,
  defaultCommuteConfig,
  defaultConfig,
  isNative,
  native,
  time,
  type AppUpdate,
  type WebUpdate,
  type WidgetType,
} from "./integrations/glance";
import type { Board, LaunchTarget, WidgetConfig } from "./types/transport";
import type { CommuteConfig, CommuteTarget } from "./types/commute";
import type { GlanceTarget, SectionType } from "./types/glance";
import { BoardScreen } from "./screens/BoardScreen";
import { CommuteConfigScreen } from "./screens/CommuteConfigScreen";
import { CommuteScreen } from "./screens/CommuteScreen";
import { ConfigScreen } from "./screens/ConfigScreen";
import { DetailScreen } from "./screens/DetailScreen";
import { ForecastScreen } from "./screens/ForecastScreen";
import { GlanceScreen } from "./screens/GlanceScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { MapScreen } from "./screens/MapScreen";

const previewId = -1;
type Screen =
  | "home"
  | "config"
  | "commute-config"
  | "detail"
  | "map"
  | "settings"
  | "forecast";
interface WidgetEntry {
  widgetId: number;
  type: WidgetType;
}
const entryKey = (entry: WidgetEntry) => `${entry.type}:${entry.widgetId}`;

/** Each type's placed widgets, or its default setup (the template new widgets start from) when none is placed. */
function withDefaults(entries: WidgetEntry[]): WidgetEntry[] {
  const types: WidgetType[] = [
    "smart",
    "transport",
    "commute",
    "agenda",
    "weather",
  ];
  return types.flatMap((type) => {
    const placed = entries.filter((entry) => entry.type === type);
    return placed.length ? placed : [{ widgetId: previewId, type }];
  });
}

const labels: Record<WidgetType, string> = {
  smart: "Smart Glance",
  transport: "Departures",
  commute: "Commute",
  agenda: "Agenda",
  weather: "Weather",
};
const sectionTypes: WidgetType[] = ["smart", "agenda", "weather"];
const isSection = (type: WidgetType): type is SectionType =>
  sectionTypes.includes(type);

function isCommuteTarget(
  target: LaunchTarget | CommuteTarget | GlanceTarget,
): target is CommuteTarget {
  return (target as CommuteTarget).kind === "commute";
}
function isGlanceTarget(
  target: LaunchTarget | CommuteTarget | GlanceTarget,
): target is GlanceTarget {
  return (target as GlanceTarget).kind === "glance";
}

export function App() {
  const [widgets, setWidgets] = useState<WidgetEntry[]>(withDefaults([]));
  const [widgetId, setWidgetId] = useState(previewId);
  const [widgetType, setWidgetType] = useState<WidgetType>("transport");
  const [commuteTarget, setCommuteTarget] = useState<CommuteTarget | null>(
    null,
  );
  const [commuteConfig, setCommuteConfig] = useState<CommuteConfig | null>(
    null,
  );
  // A Glance widget shows its commute screen in place of its overview.
  const [showCommute, setShowCommute] = useState(false);
  // Bumped after a save so the commute screen reloads its configuration.
  const [commuteVersion, setCommuteVersion] = useState(0);
  const [config, setConfig] = useState<WidgetConfig>(defaultConfig);
  const [board, setBoard] = useState<Board | null>(null);
  const [detailBoard, setDetailBoard] = useState<Board | null>(null);
  const [screen, setScreen] = useState<Screen>("home");
  const [selected, setSelected] = useState<LaunchTarget | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [update, setUpdate] = useState<AppUpdate | null>(null);
  const [webUpdate, setWebUpdate] = useState<WebUpdate | null>(null);
  const [applying, setApplying] = useState(false);
  const [version, setVersion] = useState("");
  const [webId, setWebId] = useState("");
  const [updateStatus, setUpdateStatus] = useState("");

  const load = useCallback(async (id: number) => {
    if (!isNative) {
      const raw = localStorage.getItem("glance-preview-config");
      if (raw) setConfig(JSON.parse(raw) as WidgetConfig);
      return;
    }
    const data = await native.getBoard({ widgetId: id });
    setConfig(data.config);
    setBoard(data.board ?? null);
  }, []);

  const refresh = useCallback(async (id: number) => {
    if (!isNative) return;
    setBusy(true);
    try {
      const result = await native.refresh({ widgetId: id });
      setBoard(result.board);
      setMessage(
        result.error ? `Showing saved departures · ${result.error}` : "",
      );
    } catch (error) {
      setMessage(`Could not load departures: ${String(error)}`);
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (!isNative) return;
    let alive = true;
    void native
      .listWidgets()
      .then(async ({ widgets: entries }) => {
        if (!alive) return;
        const placed = entries.map((entry) => ({
          widgetId: entry.widgetId,
          type: entry.type ?? "transport",
        }));
        setWidgets(withDefaults(placed));
        const { target: launch } = await native.getLaunchTarget();
        if (launch && (isCommuteTarget(launch) || isGlanceTarget(launch))) {
          openTarget(launch, placed);
          return;
        }
        const target = launch;
        const first = placed[0];
        if (!target && first && first.type !== "transport") {
          setWidgetType(first.type);
          setWidgetId(first.widgetId);
          return;
        }
        const id =
          placed.find((entry) => entry.type === "transport")?.widgetId ??
          previewId;
        setWidgetId(id);
        const initial = await native.getBoard({
          widgetId: target?.widgetId ?? id,
        });
        setConfig(initial.config);
        setBoard(initial.board ?? null);
        if (target) {
          setWidgetId(target.widgetId);
          setDetailBoard(initial.board ?? null);
          setSelected(target);
          setScreen(target.view === "map" ? "map" : "detail");
        }
        void refresh(target?.widgetId ?? id);
      })
      .catch((error: unknown) => setMessage(String(error)));
    const listener = (event: Event) => {
      const detail = (
        event as CustomEvent<LaunchTarget | CommuteTarget | GlanceTarget>
      ).detail;
      if (isCommuteTarget(detail) || isGlanceTarget(detail)) {
        void native.listWidgets().then(({ widgets: entries }) =>
          openTarget(
            detail,
            entries.map((entry) => ({
              widgetId: entry.widgetId,
              type: entry.type ?? "transport",
            })),
          ),
        );
        return;
      }
      const target = detail;
      setWidgetType("transport");
      setWidgetId(target.widgetId);
      void native.getBoard({ widgetId: target.widgetId }).then((data) => {
        setConfig(data.config);
        setBoard(data.board ?? null);
        setDetailBoard(data.board ?? null);
        setSelected(target);
        setScreen(target.view === "map" ? "map" : "detail");
      });
    };
    const onVisible = () => {
      if (document.visibilityState === "visible")
        void native.listWidgets().then(({ widgets: entries }) =>
          setWidgets(
            withDefaults(
              entries.map((entry) => ({
                widgetId: entry.widgetId,
                type: entry.type ?? "transport",
              })),
            ),
          ),
        );
    };
    window.addEventListener("glance:open", listener);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      alive = false;
      window.removeEventListener("glance:open", listener);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [load, refresh]);

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [screen, showCommute]);

  const checkUpdate = useCallback(async (manual: boolean) => {
    if (manual) setUpdateStatus("Checking…");
    try {
      const result = await native.checkUpdate();
      setVersion(result.versionName);
      setWebId(result.webId ?? "");
      setUpdate(result.update ?? null);
      setWebUpdate(result.web ?? null);
      if (!manual) return;
      if (result.update || result.web) {
        // The download banner lives on the home screen.
        setScreen("home");
        setUpdateStatus("Update available");
      } else {
        setUpdateStatus(
          result.checked ? "Latest version" : "Could not check for updates",
        );
      }
    } catch {
      if (manual) setUpdateStatus("Could not check for updates");
    }
  }, []);

  useEffect(() => {
    if (!isNative) return;
    // Rendering this far is what keeps an over-the-air bundle; APKs before 0.5.1 lack the method.
    void native.webReady().catch(() => undefined);
    void checkUpdate(false);
  }, [checkUpdate]);

  useEffect(() => {
    // The commute screen keeps its own board fresh.
    if (!isNative || widgetType !== "transport") return;
    const interval = window.setInterval(() => {
      void refresh(widgetId);
    }, 60_000);
    return () => window.clearInterval(interval);
  }, [widgetId, widgetType, refresh]);

  /**
   * A tap from a widget that lands on a commute or a Glance widget. A commute target can belong to
   * a Glance widget, whose commute lives under the same widget id.
   */
  function openTarget(
    target: CommuteTarget | GlanceTarget,
    placed: WidgetEntry[],
  ) {
    const type =
      placed.find((entry) => entry.widgetId === target.widgetId)?.type ??
      (isCommuteTarget(target) ? "commute" : "smart");
    setWidgetType(type);
    setWidgetId(target.widgetId);
    setSelected(null);
    if (isCommuteTarget(target)) {
      setCommuteTarget(target);
      setShowCommute(type === "smart");
      setScreen("home");
      return;
    }
    setCommuteTarget(null);
    setShowCommute(false);
    setScreen(
      target.screen === "settings"
        ? "settings"
        : target.screen === "weather"
          ? "forecast"
          : "home",
    );
  }
  async function openCommuteStops() {
    const data = isNative
      ? await commuteNative.getBoard({ widgetId, type: "commute" })
      : null;
    setCommuteConfig(data?.config ?? defaultCommuteConfig());
    setScreen("commute-config");
  }
  async function chooseWidget(entry: WidgetEntry) {
    setWidgetId(entry.widgetId);
    setWidgetType(entry.type);
    setScreen("home");
    setSelected(null);
    setCommuteTarget(null);
    setShowCommute(false);
    if (entry.type !== "transport") return;
    await load(entry.widgetId);
    void refresh(entry.widgetId);
  }
  async function saveCommute(next: CommuteConfig) {
    if (isNative)
      await commuteNative.saveConfig({
        widgetId,
        type: "commute",
        config: next,
      });
    else localStorage.setItem("glance-preview-commute", JSON.stringify(next));
    setCommuteVersion((version) => version + 1);
    setScreen("home");
  }
  async function save(next: WidgetConfig) {
    if (isNative) await native.saveConfig({ widgetId, config: next });
    else localStorage.setItem("glance-preview-config", JSON.stringify(next));
    setConfig(next);
    setScreen("home");
    void refresh(widgetId);
  }
  function openDeparture(
    groupId: string,
    journeyId: string,
    departureTime: string,
  ) {
    setDetailBoard(board);
    setSelected({ widgetId, groupId, journeyId, departureTime });
    setScreen("detail");
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-mark">G</div>
        <div>
          <strong>Glance</strong>
          <span>What matters now, and next</span>
        </div>
        <button
          className="icon-button"
          onClick={() => setScreen("settings")}
          aria-label="Settings"
        >
          ⚙
        </button>
        <button
          className="icon-button"
          onClick={() => {
            setScreen("home");
            setSelected(null);
          }}
          aria-label="Home"
        >
          ⌂
        </button>
      </header>
      <main className="content">
        {screen === "home" && (
          <>
            {update && (
              <div className="update-banner" role="status">
                <span>
                  <strong>Glance {update.versionName} is available</strong>
                  <small>{(update.bytes / 1048576).toFixed(1)} MB</small>
                </span>
                <button
                  className="primary"
                  onClick={() =>
                    void native
                      .openUpdate({ url: update.apkUrl })
                      .catch((error: unknown) => setMessage(String(error)))
                  }
                >
                  Download
                </button>
              </div>
            )}
            {webUpdate && (
              <div className="update-banner" role="status">
                <span>
                  <strong>A Glance update is ready</strong>
                  <small>
                    {Math.max(1, Math.round(webUpdate.bytes / 1024))} KB · no
                    reinstall
                  </small>
                </span>
                <button
                  className="primary"
                  disabled={applying}
                  onClick={() => {
                    setApplying(true);
                    // On success the WebView reloads into the new bundle.
                    native.applyWebUpdate().catch((error: unknown) => {
                      setApplying(false);
                      setMessage(String(error));
                    });
                  }}
                >
                  {applying ? "Updating…" : "Update"}
                </button>
              </div>
            )}
            <div className="hero">
              <div className="eyebrow">YOUR WIDGETS</div>
              <h1>Where to?</h1>
              <p>Your commute, agenda and weather, live on the home screen.</p>
            </div>
            <div className="toolbar">
              <label>
                Widget{" "}
                <select
                  value={entryKey({ widgetId, type: widgetType })}
                  onChange={(event) => {
                    const entry = widgets.find(
                      (item) => entryKey(item) === event.target.value,
                    );
                    if (entry) void chooseWidget(entry);
                  }}
                >
                  {widgets.map((entry) => {
                    const sameType = widgets.filter(
                      (item) => item.type === entry.type,
                    );
                    const label = labels[entry.type];
                    return (
                      <option key={entryKey(entry)} value={entryKey(entry)}>
                        {entry.widgetId === previewId
                          ? `${label} · default setup`
                          : `${label} widget${sameType.length > 1 ? ` ${sameType.indexOf(entry) + 1}` : ""}`}
                      </option>
                    );
                  })}
                </select>
              </label>
              {widgetType === "transport" && (
                <button onClick={() => void refresh(widgetId)} disabled={busy}>
                  {busy ? "Refreshing…" : "↻ Refresh"}
                </button>
              )}
            </div>
            {isSection(widgetType) && !showCommute ? (
              <GlanceScreen
                key={`${widgetType}-${widgetId}-${commuteVersion}`}
                widgetId={widgetId}
                type={widgetType}
                onCommute={() => setShowCommute(true)}
                onCommuteStops={() => void openCommuteStops()}
                onSettings={() => setScreen("settings")}
                onForecast={() => setScreen("forecast")}
              />
            ) : widgetType === "commute" || showCommute ? (
              <>
                {showCommute && (
                  <button
                    className="back"
                    onClick={() => {
                      setShowCommute(false);
                      setCommuteTarget(null);
                    }}
                  >
                    ← {labels[widgetType]}
                  </button>
                )}
                <CommuteScreen
                  key={`${widgetId}-${commuteVersion}`}
                  widgetId={widgetId}
                  target={commuteTarget}
                  onConfigure={(value) => {
                    setCommuteConfig(value);
                    setScreen("commute-config");
                  }}
                />
              </>
            ) : (
              <>
                <BoardScreen
                  board={board}
                  config={config}
                  onOpen={openDeparture}
                />
                {message && (
                  <p className="notice" role="status">
                    {message}
                  </p>
                )}
                <button
                  className="primary action"
                  onClick={() => setScreen("config")}
                >
                  Configure transport widget
                </button>
              </>
            )}
            <section className="available">
              <div className="eyebrow">WIDGET LIBRARY</div>
              <h2>More widgets</h2>
              {(
                [
                  ["smart", "◎", "Commute, agenda and weather in one card"],
                  ["commute", "⇄", "Whole journeys both ways"],
                  ["agenda", "▣", "Your next events from Nodecal"],
                  ["weather", "☂", "Rain advice for your commute"],
                ] as [WidgetType, string, string][]
              )
                .filter(([type]) => type !== widgetType)
                .map(([type, icon, text]) => (
                  <button
                    key={type}
                    className="coming available-widget"
                    onClick={() => {
                      const entry = widgets.find((item) => item.type === type);
                      if (entry) void chooseWidget(entry);
                    }}
                  >
                    <span>{icon}</span>
                    <div>
                      <strong>{labels[type]}</strong>
                      <small>
                        {text} · add “
                        {type === "smart" ? "Smart Glance" : `Glance ${type}`}”
                        from the widget picker
                      </small>
                    </div>
                  </button>
                ))}
            </section>
          </>
        )}
        {screen === "forecast" && (
          <ForecastScreen
            widgetId={widgetId}
            onBack={() => setScreen("home")}
          />
        )}
        {screen === "settings" && (
          <SettingsScreen
            onBack={() => {
              setScreen("home");
              setCommuteVersion((version) => version + 1);
            }}
          />
        )}
        {screen === "commute-config" && commuteConfig && (
          <CommuteConfigScreen
            config={commuteConfig}
            onSave={saveCommute}
            onBack={() => setScreen("home")}
          />
        )}
        {screen === "config" && (
          <ConfigScreen
            config={config}
            onSave={save}
            onBack={() => setScreen("home")}
          />
        )}
        {screen === "detail" && selected && (
          <DetailScreen
            board={detailBoard ?? board}
            target={selected}
            onMap={() => setScreen("map")}
            onBack={() => setScreen("home")}
            onSelect={(journeyId, departureTime) =>
              setSelected({ ...selected, journeyId, departureTime })
            }
          />
        )}
        {screen === "map" &&
          selected &&
          (() => {
            const group = (detailBoard ?? board)?.groups.find(
              (item) => item.id === selected.groupId,
            );
            const departure = group?.departures.find(
              (item) =>
                item.journeyId === selected.journeyId &&
                item.scheduled === selected.departureTime,
            );
            return departure ? (
              <MapScreen
                departure={departure}
                onBack={() => setScreen("detail")}
              />
            ) : (
              <p>Route unavailable.</p>
            );
          })()}
      </main>
      <footer>
        Entur live data · Times in Norway
        {widgetType === "transport" &&
          (board
            ? ` · Updated ${time(board.checkedAt)}${board.stale ? " · stale" : ""}`
            : " · Awaiting first update")}
        {version && ` · v${version}`}
        {webId && ` · web ${webId.slice(0, 6)}`}
        {isNative && (
          <>
            {" · "}
            <button
              className="footer-link"
              disabled={updateStatus === "Checking…"}
              onClick={() => void checkUpdate(true)}
            >
              {updateStatus || "Check for updates"}
            </button>
          </>
        )}
      </footer>
    </div>
  );
}
