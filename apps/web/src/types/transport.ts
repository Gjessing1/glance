export interface StopCall {
  id: string;
  name: string;
  scheduledArrival: string;
  expectedArrival: string;
  scheduledDeparture: string;
  expectedDeparture: string;
  platform: string;
  latitude: number | null;
  longitude: number | null;
  cancelled: boolean;
}
export interface Departure {
  journeyId: string;
  line: string;
  headsign: string;
  mode: string;
  scheduled: string;
  expected: string;
  arrival: string;
  platform: string;
  realtime: boolean;
  cancelled: boolean;
  predictionInaccurate: boolean;
  originIndex: number;
  destinationIndex: number;
  stops: StopCall[];
  routePoints: string;
}
export interface GroupConfig {
  id: string;
  name: string;
  origin: string;
  destination: string;
  mode: string;
  lines: string;
  /** "line=NSR:StopPlace:id" pairs: those lines board at another stop. */
  lineOrigins?: string;
  fastOnly: boolean;
  count: number;
  enabled: boolean;
}
export interface WidgetConfig {
  id: string;
  type: "transport";
  version?: number;
  name: string;
  groups: GroupConfig[];
}
export interface BoardGroup {
  id: string;
  name: string;
  mode: string;
  departures: Departure[];
}
export interface Board {
  widgetId: number;
  checkedAt: string;
  stale: boolean;
  groups: BoardGroup[];
}
export interface LaunchTarget {
  widgetId: number;
  groupId: string;
  journeyId: string;
  departureTime: string;
  view?: "detail" | "map";
}
