export interface Situation {
  id: string;
  severity: string;
  reportType: string;
  summary: string;
  detail: string;
  advice: string;
  url: string;
}
export interface Leg {
  mode: string;
  submode: string;
  line: string;
  serviceJourneyId: string;
  towards: string;
  from: string;
  fromQuay: string;
  to: string;
  toQuay: string;
  aimedStart: string;
  expectedStart: string;
  aimedEnd: string;
  expectedEnd: string;
  distance: number;
  realtime: boolean;
  cancelled: boolean;
  inaccurate: boolean;
  situations: Situation[];
}
export interface Journey {
  /** Services and their timetabled departures: stable across refreshes. */
  key: string;
  legs: Leg[];
  aimedStart: string;
  expectedStart: string;
  aimedEnd: string;
  expectedEnd: string;
  /** Expected departure of the first vehicle. */
  rideStart: string;
}
export interface CommuteSection {
  id?: string;
  from: string;
  to: string;
  journeys: Journey[];
  noService: boolean;
}
export type CommuteDirection = "outbound" | "inbound";
export type CommuteMode = CommuteDirection | "via";
export interface CommuteBoard {
  widgetId: number;
  checkedAt: string;
  stale: boolean;
  names: { from: string; via: string; to: string };
  outbound: CommuteSection;
  inbound: CommuteSection;
  hops: CommuteSection[];
  tracked: { direction: CommuteDirection; journey: Journey } | null;
}
export interface CommuteConfig {
  id: string;
  type: "commute";
  version?: number;
  name: string;
  from: string;
  fromName: string;
  via: string;
  viaName: string;
  to: string;
  toName: string;
  /** Line codes, comma separated; empty allows any line. */
  lines: string;
  count: number;
  /** Leaves the Interchange button off the widget and the commute screen. */
  hideInterchange?: boolean;
  /** "weekday" shows the commute on weekends too; "hide" rests with a quiet line. */
  weekend?: "weekday" | "hide";
}
export interface CommuteTarget {
  kind: "commute";
  widgetId: number;
  mode: CommuteMode;
  journeyKey: string;
}
