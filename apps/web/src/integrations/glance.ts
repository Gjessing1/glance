import { Capacitor, registerPlugin } from "@capacitor/core";
import type { Board, LaunchTarget, WidgetConfig } from "../types/transport";
import type {
  CommuteBoard,
  CommuteConfig,
  CommuteDirection,
  CommuteMode,
  CommuteTarget,
  Journey,
} from "../types/commute";

import type {
  Forecast,
  GlanceData,
  GlanceSettings,
  GlanceTarget,
  SectionConfig,
  SectionType,
} from "../types/glance";

export type WidgetType = "transport" | "commute" | SectionType;

interface GlanceNative {
  listWidgets(): Promise<{
    widgets: { widgetId: number; type?: WidgetType }[];
  }>;
  getBoard(options: {
    widgetId: number;
  }): Promise<{ config: WidgetConfig; board?: Board }>;
  saveConfig(options: {
    widgetId: number;
    config: WidgetConfig;
  }): Promise<void>;
  refresh(options: {
    widgetId: number;
  }): Promise<{ board: Board; error?: string }>;
  getLaunchTarget(): Promise<{
    target?: LaunchTarget | CommuteTarget | GlanceTarget;
  }>;
  checkUpdate(): Promise<{
    versionName: string;
    versionCode: number;
    /** The web bundle on screen, built into the APK or applied over the air. */
    webId?: string;
    checked?: boolean;
    update?: AppUpdate;
    web?: WebUpdate;
  }>;
  openUpdate(options: { url: string }): Promise<void>;
  /** Downloads the offered web bundle and reloads into it. */
  applyWebUpdate(): Promise<void>;
  /** Keeps an applied bundle; one that never calls this is rolled back. */
  webReady(): Promise<void>;
}
export interface AppUpdate {
  versionName: string;
  versionCode: number;
  apkUrl: string;
  bytes: number;
}
export interface WebUpdate {
  id: string;
  bytes: number;
}
/** The same plugin methods, asked about a commute widget. */
interface CommuteNative {
  getBoard(options: { widgetId: number; type: "commute" }): Promise<{
    config: CommuteConfig;
    board?: CommuteBoard;
    mode: CommuteMode;
  }>;
  saveConfig(options: {
    widgetId: number;
    type: "commute";
    config: CommuteConfig;
  }): Promise<void>;
  refresh(options: {
    widgetId: number;
    type: "commute";
  }): Promise<{ board: CommuteBoard; error?: string }>;
  track(options: {
    widgetId: number;
    direction: CommuteDirection;
    journey: Journey | null;
  }): Promise<{ board: CommuteBoard | null }>;
}
/** The section widgets (Glance, agenda, weather) and the app-wide settings they read. */
interface SectionNative {
  getGlance(options: {
    widgetId: number;
    type: SectionType;
  }): Promise<GlanceData>;
  saveConfig(options: {
    widgetId: number;
    type: SectionType;
    config: SectionConfig;
  }): Promise<void>;
  refresh(options: {
    widgetId: number;
    type: SectionType;
  }): Promise<GlanceData>;
  /** Now, 48 hours and up to nine days at both ends of the widget's commute. */
  getForecast(options: { widgetId: number }): Promise<Forecast>;
  getSettings(): Promise<GlanceSettings>;
  /** The token goes to native storage and never comes back; `clearToken` forgets it. */
  saveSettings(options: {
    nodecalUrl?: string;
    token?: string;
    clearToken?: boolean;
    refreshMinutes?: GlanceSettings["refreshMinutes"];
    time?: GlanceSettings["time"];
    weather?: GlanceSettings["weather"];
  }): Promise<void>;
  testNodecal(options: { nodecalUrl?: string; token?: string }): Promise<{
    ok: boolean;
    events?: number;
    calendars?: number;
    error?: string;
  }>;
}
export const native = registerPlugin<GlanceNative>("Glance");
export const commuteNative = native as unknown as CommuteNative;
export const sectionNative = native as unknown as SectionNative;
export const isNative = Capacitor.isNativePlatform();

export function defaultConfig(): WidgetConfig {
  return {
    id: "preview",
    type: "transport",
    name: "My departures",
    groups: [
      {
        id: "engelsrud-asker",
        name: "Engelsrud → Asker stasjon",
        origin: "NSR:StopPlace:3369",
        destination: "NSR:StopPlace:3247",
        mode: "bus",
        lines: "71,280",
        lineOrigins: "71=NSR:StopPlace:70146",
        fastOnly: false,
        count: 6,
        enabled: true,
      },
      {
        id: "asker-engelsrud",
        name: "Asker stasjon → Engelsrud",
        origin: "NSR:StopPlace:3247",
        destination: "NSR:StopPlace:3369",
        mode: "bus",
        lines: "71,280",
        fastOnly: false,
        count: 6,
        enabled: true,
      },
      {
        id: "asker-national",
        name: "Asker stasjon → Nationaltheatret",
        origin: "NSR:StopPlace:418",
        destination: "NSR:StopPlace:288",
        mode: "rail",
        lines: "",
        fastOnly: true,
        count: 6,
        enabled: true,
      },
      {
        id: "national-asker",
        name: "Nationaltheatret → Asker stasjon",
        origin: "NSR:StopPlace:288",
        destination: "NSR:StopPlace:418",
        mode: "rail",
        lines: "",
        fastOnly: true,
        count: 6,
        enabled: true,
      },
    ],
  };
}

export function defaultCommuteConfig(): CommuteConfig {
  return {
    id: "preview",
    type: "commute",
    name: "My commute",
    from: "NSR:StopPlace:70146",
    fromName: "Engelsrud",
    via: "NSR:StopPlace:59616",
    viaName: "Asker",
    to: "NSR:StopPlace:288",
    toName: "Nationaltheatret",
    lines: "",
    count: 6,
  };
}

export function time(iso: string): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("nb-NO", {
    timeZone: "Europe/Oslo",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
export function delayMinutes(expected: string, scheduled: string): number {
  return Math.round(
    (new Date(expected).getTime() - new Date(scheduled).getTime()) / 60000,
  );
}
