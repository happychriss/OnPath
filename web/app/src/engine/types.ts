export type Activity = 'BIKE_TOURING' | 'BIKE_TREKKING' | 'EBIKE' | 'MTB' | 'EMTB' | 'HIKE';

export type EngineRequest =
  | { id: number; type: 'config'; base: string; segments: string }
  | { id: number; type: 'route'; points: string; activity: Activity; sac: number };

export interface RouteResult {
  error?: string;
  distanceM: number; ascentM: number; descentM: number; durationS: number; timeS: number; ms: number;
  wayTypes: [string, number][]; surfaces: [string, number][];
  points: [number, number, number | null][];
}

export interface RouteStats { engineLoadMs: number; fetchMs: number; routeMs: number; bytesFetched: number }

export interface EngineReply { id: number; ok: boolean; result?: RouteResult; stats?: RouteStats; problems?: string[]; error?: string }
