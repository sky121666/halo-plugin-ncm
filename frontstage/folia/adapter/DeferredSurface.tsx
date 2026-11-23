import { Component, Suspense, useEffect, useState, type ReactNode } from 'react';

class SurfaceBoundary extends Component<{ label: string; children: ReactNode; visible: boolean }, { failed: boolean }> {
    state = { failed: false };

    static getDerivedStateFromError() {
        return { failed: true };
    }

    render() {
        if (this.state.failed) {
            return this.props.visible
                ? <div className="halo-surface-message" role="alert">{this.props.label}加载失败。刷新页面后可重试。</div>
                : null;
        }
        return this.props.children;
    }
}

export function DeferredSurface({ active, label, children, silent = false }: { active: boolean; label: string; children: ReactNode; silent?: boolean }) {
    const [opened, setOpened] = useState(active);
    useEffect(() => {
        if (active) setOpened(true);
    }, [active]);
    if (!active && !opened) return null;
    return <SurfaceBoundary label={label} visible={active && !silent}>
        <Suspense fallback={active && !silent && <div className="halo-surface-message" role="status">正在加载{label}…</div>}>
            {children}
        </Suspense>
    </SurfaceBoundary>;
}

