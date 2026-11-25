export interface Point { x: number; y: number }
export interface Size { width: number; height: number }
export interface Bounds { left: number; top: number; right: number; bottom: number }
export type Layout = 'wide' | 'compact';
export type Positions = Partial<Record<Layout, Point>>;
export type PositionEdits = Partial<Record<Layout, Point | null>>;
export type Side = 'left' | 'right' | 'top' | 'bottom';

export const layouts = ['wide', 'compact'] as const;
export const defaultPosition: Point = {x: 1, y: 1};
export const layoutFor = (width: number): Layout => width <= 520 ? 'compact' : 'wide';
const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));

function readPoint(value: unknown): Point | undefined {
    if (!value || typeof value !== 'object') return;
    const {x, y} = value as Partial<Point>;
    if (typeof x === 'number' && typeof y === 'number' && x >= 0 && x <= 1 && y >= 0 && y <= 1) {
        return {x, y};
    }
}

export function readEdits(value: unknown): PositionEdits {
    const result: PositionEdits = {};
    if (!value || typeof value !== 'object') return result;
    for (const key of layouts) {
        const item = (value as Record<Layout, unknown>)[key];
        const point = item === null ? null : readPoint(item);
        if (point !== undefined) result[key] = point;
    }
    return result;
}

export function applyEdits(positions: Positions, edits: PositionEdits): Positions {
    const next = {...positions};
    for (const layout of layouts) {
        const edit = edits[layout];
        if (edit === null) delete next[layout];
        else if (edit) next[layout] = edit;
    }
    return next;
}

export function readPositions(value: unknown): Positions {
    return applyEdits({}, readEdits(value));
}

export function buttonPixels(point: Point, bounds: Bounds, size: Size): Point {
    return {
        x: bounds.left + point.x * Math.max(0, bounds.right - bounds.left - size.width),
        y: bounds.top + point.y * Math.max(0, bounds.bottom - bounds.top - size.height),
    };
}

export function buttonPosition(point: Point, bounds: Bounds, size: Size): Point {
    const width = Math.max(1, bounds.right - bounds.left - size.width);
    const height = Math.max(1, bounds.bottom - bounds.top - size.height);
    return {x: clamp((point.x - bounds.left) / width, 0, 1), y: clamp((point.y - bounds.top) / height, 0, 1)};
}

export function avoidFloatingControls(point: Point, bounds: Bounds, size: Size, controls: Bounds[]): Point {
    const gap = 8;
    const overlaps = (candidate: Point) => controls.some(control =>
        candidate.x < control.right + gap && candidate.x + size.width > control.left - gap
        && candidate.y < control.bottom + gap && candidate.y + size.height > control.top - gap);
    if (!overlaps(point)) return point;

    let nearest = point;
    let distance = Infinity;
    for (const control of controls) {
        const candidates = [
            {x: point.x, y: control.top - gap - size.height},
            {x: point.x, y: control.bottom + gap},
            {x: control.left - gap - size.width, y: point.y},
            {x: control.right + gap, y: point.y},
        ];
        for (const candidate of candidates) {
            const available = {
                x: clamp(candidate.x, bounds.left, bounds.right - size.width),
                y: clamp(candidate.y, bounds.top, bounds.bottom - size.height),
            };
            const offset = Math.hypot(available.x - point.x, available.y - point.y);
            if (offset < distance && !overlaps(available)) {
                nearest = available;
                distance = offset;
            }
        }
    }
    return nearest;
}

export function placePanel(anchor: Bounds, panel: Size, bounds: Bounds) {
    const gap = 10;
    const width = Math.min(panel.width, Math.max(1, bounds.right - bounds.left));
    const height = Math.min(panel.height, Math.max(1, bounds.bottom - bounds.top));
    const space = {
        left: anchor.left - bounds.left - gap, right: bounds.right - anchor.right - gap,
        top: anchor.top - bounds.top - gap, bottom: bounds.bottom - anchor.bottom - gap,
    };
    const vertical = (space.top >= space.bottom ? ['top', 'bottom'] : ['bottom', 'top']) as Side[];
    const horizontal = (space.left >= space.right ? ['left', 'right'] : ['right', 'left']) as Side[];
    const edge = Math.min(84, (bounds.right - bounds.left) * .2);
    const preferred = Math.min(space.left, space.right) < edge ? [...horizontal, ...vertical] : [...vertical, ...horizontal];
    const side = preferred.find(candidate => space[candidate] >= (candidate === 'left' || candidate === 'right' ? width : height))
        ?? vertical[0]!;
    const maximumHeight = Math.max(1, side === 'top' || side === 'bottom' ? space[side] : bounds.bottom - bounds.top);
    const visibleHeight = Math.min(height, maximumHeight);
    const centerX = (anchor.left + anchor.right) / 2;
    const centerY = (anchor.top + anchor.bottom) / 2;
    const x = clamp(side === 'right' ? anchor.right + gap : side === 'left' ? anchor.left - gap - width
        : centerX - width / 2, bounds.left, bounds.right - width);
    const y = clamp(side === 'bottom' ? anchor.bottom + gap : side === 'top' ? anchor.top - gap - visibleHeight
        : centerY - visibleHeight / 2, bounds.top, bounds.bottom - visibleHeight);
    return {
        side, x, y, width, maximumHeight,
        originX: clamp(centerX - x, 0, width), originY: clamp(centerY - y, 0, visibleHeight),
    };
}

const arrows: Record<string, Point> = {
    ArrowLeft: {x: -1, y: 0}, ArrowRight: {x: 1, y: 0},
    ArrowUp: {x: 0, y: -1}, ArrowDown: {x: 0, y: 1},
};

export function moveWithKey(event: KeyboardEvent, origin: Point): Point | undefined {
    const arrow = arrows[event.key];
    if (!arrow) return;
    const step = event.shiftKey ? 24 : 8;
    return {x: origin.x + arrow.x * step, y: origin.y + arrow.y * step};
}

export function bindPositionDrag(target: HTMLElement, callbacks: {
    origin(): Point | undefined;
    move(point: Point): void;
    finish(point: Point | null): void;
}, signal: AbortSignal) {
    let gesture: {id: number; start: Point; origin: Point; moved: boolean} | undefined;
    let frame = 0;
    let suppressClick = false;
    const pointAt = (event: PointerEvent, start: NonNullable<typeof gesture>) => ({
        x: start.origin.x + event.clientX - start.start.x,
        y: start.origin.y + event.clientY - start.start.y,
    });
    const finish = (event?: PointerEvent) => {
        const active = gesture;
        if (!active) return;
        gesture = undefined;
        cancelAnimationFrame(frame);
        if (target.hasPointerCapture(active.id)) target.releasePointerCapture(active.id);
        if (active.moved) {
            suppressClick = true;
            callbacks.finish(event ? pointAt(event, active) : null);
        }
    };
    target.addEventListener('pointerdown', event => {
        if (!event.isPrimary || event.button !== 0 || gesture) return;
        suppressClick = false;
        const origin = callbacks.origin();
        if (!origin) return;
        gesture = {id: event.pointerId, start: {x: event.clientX, y: event.clientY}, origin, moved: false};
        target.setPointerCapture(event.pointerId);
    }, {signal});
    target.addEventListener('pointermove', event => {
        if (!gesture || event.pointerId !== gesture.id) return;
        const point = pointAt(event, gesture);
        if (!gesture.moved && Math.hypot(point.x - gesture.origin.x, point.y - gesture.origin.y) < 7) return;
        gesture.moved = true;
        cancelAnimationFrame(frame);
        frame = requestAnimationFrame(() => callbacks.move(point));
    }, {signal});
    target.addEventListener('pointerup', event => { if (event.pointerId === gesture?.id) finish(event); }, {signal});
    const cancel = (event: PointerEvent) => { if (event.pointerId === gesture?.id) finish(); };
    target.addEventListener('pointercancel', cancel, {signal});
    target.addEventListener('lostpointercapture', cancel, {signal});
    target.addEventListener('click', event => {
        if (!suppressClick || event.detail === 0) return;
        suppressClick = false;
        event.preventDefault();
        event.stopImmediatePropagation();
    }, {capture: true, signal});
    signal.addEventListener('abort', () => finish(), {once: true});
    return () => finish();
}
