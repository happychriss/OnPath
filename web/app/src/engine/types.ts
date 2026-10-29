export type Activity = 'BIKE_TOURING' | 'BIKE_TREKKING' | 'EBIKE' | 'MTB' | 'EMTB' | 'HIKE';

export type EngineRequest =
  | { id: number; type: 'config'; base: string; segments: string; packed?: boolean }
  | { id: number; type: 'route' | 'plan'; points: string; activity: Activity; sac: number; pinned?: string }
  | { id: number; type: 'encode'; plan: SharedPlan }
  | { id: number; type: 'parse'; text: string }
  | { id: number; type: 'edit'; op: string; points: string; index: number; to: number }
  | { id: number; type: 'rows'; points: string; names: string }
  | { id: number; type: 'searchAreas'; areas: { key: string; dir: string }[] }
  | { id: number; type: 'search'; text: string; lat: number; lon: number; route: string }
  | { id: number; type: 'searchOnline'; text: string; photon: string }
  | { id: number; type: 'placePreview'; points: string; lat: number; lon: number }
  | { id: number; type: 'prefetch'; lat: number; lon: number }
  | { id: number; type: 'call'; fn: string; args: (string | number | boolean)[] }
  | { id: number; type: 'insert'; points: string; lat: number; lon: number; lineSnapM: number }
  | { id: number; type: 'gpxRead'; data: Uint8Array; fileName: string; activity: Activity; sac: number }
  | { id: number; type: 'planFromTrack'; points: string; activity: Activity; sac: number };

/** A plan as it travels in a QR code or link (the app's SharedPlan / PlanCode). */
export interface SharedPlan {
  points: [number, number][]; activity: Activity; sac: number;
  pinned: number[]; names: Record<string, string>; title: string | null;
}

export interface PlanResult {
  error: string | null;
  distanceM: number; ascentM: number; descentM: number; timeS: number; difficulty: string; ms: number;
  /** Why the badge is raised (RouteSummary.Rating.because), or null. */
  because: string | null;
  /** The plan sheet's texts from the app's Format: "4 h 36 min", "12.5 km", "↑ 842 m  ↓ 310 m  ·  max 954 m". */
  texts: { time: string; distance: string; climb: string };
  /** The app's amber route warning line (RouteWarnings.text) and its "On this route" rows, or null / []. */
  warning: string | null;
  spots: { title: string; detail: string; zoom: number | null; line: [number, number][] }[];
  wayTypes: [string, number][]; surfaces: [string, number][];
  legs: { straight: boolean; points: [number, number, number | null][] }[];
  profile: { dist: number[]; ele: number[]; steep: number[] };
}

export interface RouteResult {
  error?: string;
  distanceM: number; ascentM: number; descentM: number; durationS: number; timeS: number; ms: number;
  wayTypes: [string, number][]; surfaces: [string, number][];
  points: [number, number, number | null][];
}

export interface RouteStats { engineLoadMs: number; fetchMs: number; storeMs?: number; routeMs: number; bytesFetched: number }

export interface EngineReply { id: number; ok: boolean; result?: RouteResult; plan?: PlanResult; index?: number; code?: string; text?: string; shared?: SharedPlan | null; stats?: RouteStats; problems?: string[]; error?: string }

/** One row of the Points list (the app's PlanPoints.rows). */
export interface PointRow { i: number; role: 'START' | 'STOP' | 'GOAL'; name: string; legM: number | null; approx: boolean; loop: boolean }
export interface PointRows { roundTrip: boolean; rows: PointRow[] }

/** One search result (the app's SearchHit, with the app's row texts). */
export interface Hit {
  id: string; title: string; name: string | null; kind: string; lat: number; lon: number;
  subtitle: string; address: string | null; glyph: string; color: string; online: boolean;
}
/** The place card's "Add to route" line (the app's AddPreview). */
export interface PlacePreview { index?: number; existing?: number; subtitle: string }
