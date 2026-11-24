const svgNamespace = 'http://www.w3.org/2000/svg';
const clear = 128;
const bendScale = 23;
let lastMap: { width: number; height: number; radius: number; url: string } | undefined;
let nextFilterId = 0;

function displacementMap(width: number, height: number, radius: number): string | undefined {
    if (lastMap?.width === width && lastMap.height === height && lastMap.radius === radius) {
        return lastMap.url;
    }

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const map = ctx.createImageData(width, height);
    const rim = Math.min(21, height / 5.5);
    const halfWidth = width / 2;
    const halfHeight = height / 2;

    for (let y = 0; y < height; y++) {
        const centerY = y + 0.5 - halfHeight;
        const edgeY = Math.abs(centerY) - (halfHeight - radius);
        for (let x = 0; x < width; x++) {
            const centerX = x + 0.5 - halfWidth;
            const edgeX = Math.abs(centerX) - (halfWidth - radius);
            const cornerX = Math.max(edgeX, 0);
            const cornerY = Math.max(edgeY, 0);
            const cornerDistance = Math.hypot(cornerX, cornerY);
            // Distance and outward normal of the same rounded rectangle that CSS draws.
            const distance = cornerDistance + Math.min(Math.max(edgeX, edgeY), 0) - radius;
            const strength = 119 * Math.max(0, 1 + Math.min(distance, 0) / rim) ** 2.6;
            const normalX = cornerDistance ? cornerX / cornerDistance : Number(edgeX >= edgeY);
            const normalY = cornerDistance ? cornerY / cornerDistance : Number(edgeY > edgeX);
            const offset = (y * width + x) * 4;
            map.data[offset] = clear - Math.sign(centerX) * normalX * strength;
            map.data[offset + 1] = clear - Math.sign(centerY) * normalY * strength;
            map.data[offset + 2] = clear;
            map.data[offset + 3] = 255;
        }
    }
    ctx.putImageData(map, 0, 0);
    const url = canvas.toDataURL();
    lastMap = {width, height, radius, url};
    return url;
}

/** Optional Chromium edge refraction. The CSS glass is ready before the map exists. */
export function observeLens(panel: HTMLElement): (() => void) | undefined {
    const canRefract = /\b(?:Chrome|Chromium)\//.test(navigator.userAgent)
        && CSS.supports('backdrop-filter', 'url("#ncm-glass")');
    if (!canRefract || window.matchMedia('(prefers-reduced-transparency: reduce), (forced-colors: active)').matches) return;

    const svg = document.createElementNS(svgNamespace, 'svg');
    svg.setAttribute('aria-hidden', 'true');
    svg.classList.add('lens-resource');
    svg.setAttribute('width', '0');
    svg.setAttribute('height', '0');
    const filter = document.createElementNS(svgNamespace, 'filter');
    filter.id = 'ncm-mini-glass-' + ++nextFilterId;
    filter.setAttribute('x', '0');
    filter.setAttribute('y', '0');
    filter.setAttribute('width', '1');
    filter.setAttribute('height', '1');
    filter.setAttribute('color-interpolation-filters', 'sRGB');

    const map = document.createElementNS(svgNamespace, 'feImage');
    map.setAttribute('preserveAspectRatio', 'none');
    map.setAttribute('result', 'rim');
    const bend = document.createElementNS(svgNamespace, 'feDisplacementMap');
    bend.setAttribute('in', 'SourceGraphic');
    bend.setAttribute('in2', 'rim');
    bend.setAttribute('scale', String(bendScale));
    bend.setAttribute('xChannelSelector', 'R');
    bend.setAttribute('yChannelSelector', 'G');
    filter.append(map, bend);
    svg.append(filter);
    panel.after(svg);

    let render = 0;
    let previousSize = '';
    const observer = new ResizeObserver(() => {
        const {offsetWidth: width, offsetHeight: height} = panel;
        if (!width || !height) return;
        const radius = parseFloat(getComputedStyle(panel).borderTopLeftRadius);
        const size = [width, height, radius].join(':');
        if (size === previousSize) return;
        const generation = ++render;
        panel.style.removeProperty('--_ncm-rim-lens');
        const url = displacementMap(width, height, radius);
        if (!url) return;
        previousSize = size;
        const image = new Image();
        image.src = url;
        void image.decode().then(() => {
            if (generation !== render) return;
            map.setAttribute('href', url);
            panel.style.setProperty('--_ncm-rim-lens', 'url("#' + filter.id + '")');
        }, () => {
            if (generation === render) previousSize = '';
        });
    });
    observer.observe(panel);
    return () => {
        render++;
        observer.disconnect();
        panel.style.removeProperty('--_ncm-rim-lens');
        svg.remove();
    };
}
