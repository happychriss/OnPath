export type Activity = 'BIKE_TOURING' | 'BIKE_TREKKING' | 'EBIKE' | 'MTB' | 'EMTB' | 'HIKE';

export type EngineRequest =
  | { id: number; type: 'config'; base: string; segments: string }
  | { id: number; type: 'route' | 'plan'; points: string; activity: Activity; sac: number }
  | { id: number; type: 'insert'; points: string; lat: number; lon: number; lineSnapM: number };

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

export interface EngineReply { id: number; ok: boolean; result?: RouteResult; plan?: PlanResult; index?: number; stats?: RouteStats; problems?: string[]; error?: string }
