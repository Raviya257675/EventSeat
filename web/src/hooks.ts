import { useEffect, useState, useSyncExternalStore } from 'react';
import { connection } from './connection';
export function useConnection() { return useSyncExternalStore(connection.subscribe, connection.snapshot); }
export function useRoute() {
  const [route, setRoute] = useState(() => location.hash.slice(1) || '/');
  useEffect(() => { const update = () => setRoute(location.hash.slice(1) || '/'); window.addEventListener('hashchange', update); return () => window.removeEventListener('hashchange', update); }, []);
  return route;
}
