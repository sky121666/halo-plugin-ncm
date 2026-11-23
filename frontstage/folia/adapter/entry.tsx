import { Buffer } from 'buffer';
import { createRoot } from 'react-dom/client';
import { MotionConfig } from 'framer-motion';
import '../i18n/config';
import App from '../App';
import AppSplashGate from '../components/AppSplashGate';
import { installNativeDragGuard } from '../utils/nativeDragGuard';
import { applyHaloPlaybackPreferences } from './haloPlayback';
import { HaloAccountProvider } from './HaloAccount';
import '../index.css';

Object.assign(globalThis, { Buffer });
installNativeDragGuard();
applyHaloPlaybackPreferences();

createRoot(document.getElementById('root')!).render(
    <MotionConfig reducedMotion="user">
        <HaloAccountProvider><AppSplashGate><App /></AppSplashGate></HaloAccountProvider>
    </MotionConfig>,
);
