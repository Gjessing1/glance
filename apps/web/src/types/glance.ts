/** The combined Glance widget and the standalone agenda and weather widgets. */
export type SectionType = "smart" | "agenda" | "weather";
export type SectionId = "commute" | "agenda" | "weather";
export type Preset = "full" | "commute-agenda" | "agenda-weather" | "single";
export type Profile = "auto" | "personal" | "work" | "combined";

export interface SectionConfig {
  id: string;
  type: SectionType;
  version?: number;
  preset?: Preset;
  /** The section a "single" preset shows. */
  single?: SectionId;
  profile?: Profile;
  /** Hide the weather line when it has nothing to act on. */
  weatherAdviceOnly?: boolean;
}

export interface AgendaSlot {
  id: string;
  title: string;
  time: string;
  countdown: string;
  color: string;
  meta: string;
  ongoing: boolean;
  clash: boolean;
  futureDay: boolean;
  meetingUrl: string;
  location: string;
}

export interface Advice {
  kind: "dry" | "rain" | "heavy" | "unknown";
  title: string;
  reason: string;
}

export interface ForecastHour {
  t: string;
  temp: number | null;
  symbol: string;
  emoji: string;
  mm: number;
  p: number;
}

/** What a section widget shows now, computed natively by the widget's own code. */
export interface GlanceData {
  config: SectionConfig;
  nodecalConfigured: boolean;
  profile?: string;
  agendaCheckedAt?: string;
  agendaError?: string;
  agenda?: {
    slots: AgendaSlot[];
    allDay: string;
    allDayLabel: string;
  };
  advice?: Advice;
  places?: { name: string; lat: number; lon: number }[];
  weatherNow?: { temp: number; symbol: string; emoji: string; place: string };
  hours?: ForecastHour[];
  context?: string;
  sections?: { id: SectionId; size: "line" | "compact" | "full" }[];
  error?: string;
}

/** One step of a day's forecast: hourly for the first two days or so, then six hours (`span`) apart. */
export interface ForecastStep {
  t: string;
  temp: number | null;
  symbol: string;
  emoji: string;
  mm: number;
  p: number;
  span: number;
}

export interface ForecastDay {
  date: string;
  low: number;
  high: number;
  mm: number;
  symbol: string;
  emoji: string;
  /** Empty from an app older than the day view. */
  steps: ForecastStep[];
}

export interface PlaceForecast {
  name: string;
  now?: { temp: number; symbol: string; emoji: string };
  hours: ForecastHour[];
  days: ForecastDay[];
  checkedAt: number;
}

export interface Forecast {
  places: PlaceForecast[];
  /** The end of the commute the user is likely at now. */
  here: string;
  advice: Advice;
}

export interface TimeSettings {
  workStart: string;
  workEnd: string;
  morningStart: string;
  morningEnd: string;
  eveningStart: string;
  eveningEnd: string;
  switchAt: string;
  /** Workdays turn to the commute at this hour of the night. */
  focusStart: string;
}

export interface GlanceSettings {
  nodecalUrl: string;
  hasToken: boolean;
  /** Background refresh interval; the widgets' refresh buttons cover anything sooner. */
  refreshMinutes: 15 | 30 | 60;
  time: TimeSettings;
  weather: { probability: number; amount: number; heavy: number };
}

export interface GlanceTarget {
  kind: "glance";
  widgetId: number;
  screen: string;
}
