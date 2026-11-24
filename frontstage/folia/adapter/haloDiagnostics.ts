import { useSyncExternalStore } from 'react';
import { setIsDevDebugOverlayVisible, setIsMemoryMonitorVisible } from '../stores/useAppChromeStore';
import { installConsoleLogCapture, setConsoleCaptureAllowed } from '../utils/consoleLogBuffer';

let allowed = false;
const listeners = new Set<() => void>();
const get = () => allowed;
const subscribe = (fn: () => void) => {
    listeners.add(fn);
    return () => listeners.delete(fn);
};

export function setHaloDiagnosticAccess(enabled: boolean) {
    if (enabled === allowed) return;
    allowed = enabled;
    setConsoleCaptureAllowed(enabled);
    if (enabled) {
        installConsoleLogCapture();
    } else {
        setIsDevDebugOverlayVisible(false);
        setIsMemoryMonitorVisible(false);
    }
    listeners.forEach(fn => fn());
}

export const canUseHaloDiagnostics = get;
export const useHaloDiagnostics = () => useSyncExternalStore(subscribe, get, () => false);
