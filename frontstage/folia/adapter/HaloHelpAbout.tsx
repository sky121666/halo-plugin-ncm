import { ExternalLink } from 'lucide-react';
import { CopyButton, Group, Link, Row } from '../components/modal/settings/kit';
import { haloSettings, sourceHref } from './haloApi';

const repository = 'https://github.com/sky121666/halo-plugin-ncm';
const about = { id: 'helpAbout', titleKey: '网易云音乐 · Halo 插件' };
const sources = { id: 'helpOpenSource', titleKey: '开源项目' };
const projects = [
    { name: 'Folia Web', usage: '音乐页界面与播放器', license: 'AGPL-3.0', href: 'https://github.com/folia-ce/folia-web' },
    { name: '网易云音乐 API Enhanced', usage: '网易云接口的 Java 移植来源', license: 'MIT', href: 'https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced' },
    { name: 'Halo', usage: '插件运行平台与管理界面组件', license: 'GPL-3.0', href: 'https://github.com/halo-dev/halo' },
];

export function HaloHelpAbout() {
    const version = haloSettings.version;
    return <div data-halo-help-project>
        <Group def={about}>
            <Row label="插件版本">
                {version && version !== '0' ? <span className="flex min-w-0 items-center gap-2">
                    <code className="min-w-0 select-text font-mono text-xs text-[color:var(--st-t2)] [overflow-wrap:anywhere]">{version}</code>
                    <CopyButton text={version} />
                </span> : <span className="text-xs text-[color:var(--st-t2)]">版本未提供</span>}
            </Row>
            <Row label="项目源码" hint="sky121666/halo-plugin-ncm">
                <Link href={repository}>GitHub <ExternalLink size={12} aria-hidden className="inline-block" /></Link>
            </Row>
            <Row label="问题反馈" hint="请附上插件版本和复现步骤。">
                <Link href={repository + '/issues'}>GitHub Issues <ExternalLink size={12} aria-hidden className="inline-block" /></Link>
            </Row>
        </Group>
        <Group def={sources}>
            {projects.map(project => <Row key={project.name} label={project.name} hint={project.usage} tag={project.license}>
                <Link href={project.href}>
                    查看上游<span className="sr-only"> · {project.name}</span> <ExternalLink size={12} aria-hidden className="inline-block" />
                </Link>
            </Row>)}
            <Row label="Folia 适配源码与许可证" hint="含固定的上游版本、补丁和构建说明。">
                <a href={sourceHref} download className="rounded-[3px] underline underline-offset-4">下载源码</a>
            </Row>
        </Group>
    </div>;
}
