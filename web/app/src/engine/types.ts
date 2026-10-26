export type Activity = 'BIKE_TOURING' | 'BIKE_TREKKING' | 'EBIKE' | 'MTB' | 'EMTB' | 'HIKE';

export type EngineRequest =
  | { id: number; type: 'config'; base: string; segments: string; packed?: boolean }
  | { id: number; type: 'route' | 'plan'; points: string; activity: Activity; sac: number; pinned?: string }
  | { id: number; type: 'encode'; plan: SharedPlan }
  | { id: number; type: 'parse'; text: string }
  | { id: number; type: 'insert'; points: string; lat: number; lon: number; lineSnapM: number };

/** A plan as it travels in a QR code or link (the app's SharedPlan / PlanCode). */
export interface SharedPlan {
  points: [number, number][]; activity: Activity; sac: number;
  pinned: number[]; names: Record<string, string>; title: string | null;
}

export interface PlanResult {
  error: string | null;
  distanceM: number; ascentM: number; descentM: number; timeS: number; difficulty: string; ms: number;
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

export interface RouteStats { engineLoadMs: number; fetchMs: number; routeMs: number; bytesFetched: number }

export interface EngineReply { id: number; ok: boolean; result?: RouteResult; plan?: PlanResult; index?: number; code?: string; shared?: SharedPlan | null; stats?: RouteStats; problems?: string[]; error?: string }
