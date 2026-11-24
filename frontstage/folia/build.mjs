import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('./', import.meta.url));
const playbackSession = resolve(directory, '../mini-player/playbackSession.ts');
const appearance = resolve(directory, '../mini-player/appearance.ts');
const brandMark = resolve(directory, '../../src/main/resources/static/brand-mark.png');
const { repository, revision, sha256 } = JSON.parse(readFileSync(join(directory, 'upstream.json'), 'utf8'));
const build = resolve(directory, '../../build/folia');
const cache = join(build, 'downloads');
const output = join(build, 'resources');
const base = '/plugins/plugin-ncm/assets/static/folia/';
const upstream = 'https://codeload.github.com/folia-ce/folia-web/tar.gz/' + revision;
const archive = join(cache, revision + '.tar.gz');
const cleanEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    !/^(?:VITE_|FOLIA_|VERCEL_|ELECTRON$|REQUIRE_COMMIT_NAME$)/.test(key)));

function run(command, args, cwd, env = cleanEnv) {
    const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
    if (result.status !== 0) throw result.error ?? new Error(command + ' exited with ' + result.status);
}

function valid(file) {
    return existsSync(file) && createHash('sha256').update(readFileSync(file)).digest('hex') === sha256;
}

mkdirSync(cache, { recursive: true });
const work = mkdtempSync(join(build, 'stage-'));
try {
    if (!valid(archive)) {
        const download = join(work, 'folia.tar.gz');
        run('curl', ['--fail', '--silent', '--show-error', '--location', '--retry', '2', '--max-time', '180',
            '--output', download, upstream], work);
        if (!valid(download)) throw new Error('Folia archive does not match upstream.json');
        renameSync(download, archive);
    }

    const project = join(work, 'upstream');
    mkdirSync(project);
    run('tar', ['-xzf', archive, '--strip-components=1', '-C', project], work);
    run('patch', ['-p1', '--batch', '--forward', '-i', join(directory, 'upstream.patch')], project);
    cpSync(brandMark, join(project, 'src/assets/providers/netease.png'));
    cpSync(join(directory, 'adapter'), join(project, 'src/halo'), { recursive: true });
    cpSync(playbackSession, join(project, 'src/halo/playbackSession.ts'));
    cpSync(appearance, join(project, 'src/halo/appearance.ts'));
    const modelSource = resolve(directory, '../../ui/src/api/generated/models');
    const models = join(work, 'api-models');
    mkdirSync(models);
    for (const name of readdirSync(modelSource)) {
        if (/^ncm-(?:account-view|login-result|captcha-request|cellphone-login-request|(?:me|qr)-.+)\.ts$/.test(name)) {
            cpSync(join(modelSource, name), join(models, name));
        }
    }
    cpSync(models, join(project, 'src/halo/generated'), { recursive: true });
    cpSync(join(directory, 'tests'), join(project, 'test/unit/halo'), { recursive: true });
    run('npm', ['ci', '--include=dev', '--ignore-scripts', '--no-audit', '--no-fund', '--prefer-offline'], project);
    run('npm', ['run', 'typecheck'], project);
    run('npm', ['test', '--', 'test/unit/halo', 'test/unit/cache/themeCache.test.ts',
        'test/unit/theme/themeGenerationRace.test.ts'], project);
    run('npm', ['run', 'build:web', '--', '--logLevel', 'warn'], project, {
        ...cleanEnv, FOLIA_BASE_PATH: base, VITE_LIBRARY_INITIAL_SUITE: 'grid',
        VERCEL_GIT_COMMIT_SHA: revision, VERCEL_GIT_COMMIT_REF: 'main',
    });

    const staging = join(work, 'resources');
    const assets = join(staging, 'static/folia');
    const privateEntry = join(staging, 'folia');
    const dist = join(project, 'dist');
    const ignored = new Set(['index.html', 'CNAME', 'folia-cover-sw.js', 'runtime-config.js', 'icon.svg.bak']);
    const index = readFileSync(join(dist, 'index.html'), 'utf8');
    if (!index.includes('__NCM_FOLIA_CONFIG__') || !index.includes('__NCM_FOLIA_HEAD__') || !index.includes(base + 'assets/')
        || /registerSW|<script[^>]+src="https?:/.test(index)) throw new Error('Invalid Folia HTML entry');
    cpSync(dist, assets, { recursive: true, filter: file => !ignored.has(basename(file)) });
    mkdirSync(privateEntry, { recursive: true });
    writeFileSync(join(privateEntry, 'index.html'), index);

    const source = join(assets, 'source');
    mkdirSync(source);
    const notices = join(work, 'source-notices');
    mkdirSync(notices);
    cpSync(join(project, 'LICENSE'), join(notices, 'LICENSE'));
    writeFileSync(join(notices, 'SOURCE.txt'), [
        'Folia Web (AGPL-3.0) · Halo adaptation',
        'Upstream: ' + repository,
        'Corresponding upstream source: ' + upstream,
        'SHA-256: ' + sha256,
        'Extract the upstream archive, apply upstream.patch with patch -p1, and copy adapter into src/halo.',
        'Copy brand-mark.png to src/assets/providers/netease.png for the Halo build.',
        'Copy playbackSession.ts and appearance.ts into src/halo for playback and appearance integration.',
        'Copy tests into test/unit/halo for the Halo configuration sync checks.',
        'Copy api-models into src/halo/generated to compile against the plugin OpenAPI types.',
        'Build with Node.js 24 and npm ci, then npm run build:web with FOLIA_BASE_PATH=' + base,
        'The bundled build.mjs automates these steps in the Halo plugin source tree.',
        '',
    ].join('\n'));
    run('tar', ['-czf', join(source, 'halo-adaptation.tar.gz'),
        '-C', directory, 'upstream.json', 'upstream.patch', 'adapter', 'tests', 'build.mjs',
        '-C', resolve(directory, '../mini-player'), 'playbackSession.ts', 'appearance.ts',
        '-C', dirname(brandMark), basename(brandMark),
        '-C', work, 'api-models',
        '-C', notices, 'LICENSE', 'SOURCE.txt'], work);
    cpSync(join(notices, 'LICENSE'), join(source, 'LICENSE.txt'));
    cpSync(join(notices, 'SOURCE.txt'), join(source, 'SOURCE.txt'));

    rmSync(output, { recursive: true, force: true });
    renameSync(staging, output);
    console.log('Folia Web packaged at ' + revision.slice(0, 12));
} finally {
    rmSync(work, { recursive: true, force: true });
}
