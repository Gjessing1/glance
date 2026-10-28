import type { Journey, Leg, Situation } from "../types/commute";

/**
 * The arithmetic behind the commute board, borrowed from Atlas's commute panel: connections are
 * computed rather than left to the reader, and scheduled times lead with live ones labelled beside
 * them. The widget draws the same rules natively in CommuteWidget.kt.
 */

export const rides = (journey: Journey): Leg[] =>
  journey.legs.filter((leg) => leg.mode !== "foot");

export function minutes(from: string, to: string): number {
  const a = new Date(from).getTime();
  const b = new Date(to).getTime();
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.floor((b - a) / 60000);
}

export interface Change {
  at: string;
  planned: number;
  live: number;
  /** Live data cut the wait under four minutes. */
  tight: boolean;
  /** The arrival misses the departure. */
  broken: boolean;
  walk: number;
}

export function changes(journey: Journey): Change[] {
  const list = rides(journey);
  return list.slice(1).map((ride, index) => {
    const previous = list[index]!;
    const planned = minutes(previous.aimedEnd, ride.aimedStart);
    const live = minutes(previous.expectedEnd, ride.expectedStart);
    const walk = journey.legs
      .filter(
        (leg) =>
          leg.mode === "foot" &&
          leg.expectedStart >= previous.expectedEnd &&
          leg.expectedEnd <= ride.expectedStart,
      )
      .reduce((sum, leg) => sum + leg.distance, 0);
    return {
      at: ride.from,
      planned,
      live,
      tight: live >= 0 && live < 4 && live !== planned,
      broken: live < 0,
      walk,
    };
  });
}

export const totalMinutes = (journey: Journey) =>
  minutes(journey.expectedStart, journey.expectedEnd);

/** Active notices compete with the time; routine ones are there to be read, muted. */
export const isActive = (situation: Situation) =>
  situation.reportType === "incident" ||
  situation.severity === "severe" ||
  situation.severity === "verySevere";

export function notices(journey: Journey): Situation[] {
  const seen = new Set<string>();
  return rides(journey)
    .flatMap((ride) => ride.situations)
    .filter((situation) => {
      if (!situation.summary || seen.has(situation.summary)) return false;
      seen.add(situation.summary);
      return true;
    });
}

/** A notice on every journey belongs to the corridor: said once above the list, not on each row. */
export function sharedNotices(journeys: Journey[]): Situation[] {
  if (journeys.length < 2) return [];
  const [first, ...rest] = journeys;
  return notices(first!).filter((situation) =>
    rest.every((journey) =>
      notices(journey).some((other) => other.summary === situation.summary),
    ),
  );
}

export function countdown(iso: string, nowMs: number): string {
  const diff = Math.round((new Date(iso).getTime() - nowMs) / 60000);
  if (diff < 0) return "left";
  if (diff === 0) return "now";
  if (diff < 60) return `in ${diff} min`;
  return `in ${Math.floor(diff / 60)} h ${diff % 60} min`;
}

/** Where a tracked journey stands: the vehicle to catch next, or the one being ridden. */
export function phase(journey: Journey, nowMs: number): string {
  const list = rides(journey);
  const current = list.findIndex(
    (ride) => new Date(ride.expectedEnd).getTime() > nowMs,
  );
  if (current < 0) return "Arrived";
  const ride = list[current]!;
  if (nowMs < new Date(ride.expectedStart).getTime())
    return current === 0
      ? `Catch the ${ride.line}`
      : `Change to the ${ride.line}`;
  return `On the ${ride.line} to ${ride.to}`;
}

export const isRail = (leg: Leg) => leg.mode !== "bus" && leg.mode !== "coach";
