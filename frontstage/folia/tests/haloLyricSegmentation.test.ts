// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HaloMusicAiError } from '../../../src/halo/haloMusicAi';
import { requestLyricSegmentation } from '../../../src/services/aiText';
import { segmentLyricsWithAi } from '../../../src/services/lyricSegmentationAi';

vi.mock('../../../src/services/aiText', () => ({ requestLyricSegmentation: vi.fn() }));

beforeEach(() => {
    vi.mocked(requestLyricSegmentation).mockReset().mockImplementation(async lines => lines.map(line => [line]));
});

describe('Halo 歌词分词', () => {
    it('跳过超长行，保留其他行和原始顺序', async () => {
        const oversized = '词'.repeat(241);
        const result = await segmentLyricsWithAi(['听见 风', oversized, '雨来了']);
        expect(requestLyricSegmentation).toHaveBeenCalledExactlyOnceWith(['听见 风', '雨来了'], undefined);
        expect(result.boundaries).toEqual([['听见 风'], null, ['雨来了']]);
    });

    it('按预计输出大小分批，保留失败批次以外的结果', async () => {
        const lines = Array.from({ length: 10 }, (_, i) => String(i).padEnd(240, '词'));
        vi.mocked(requestLyricSegmentation).mockRejectedValueOnce(new Error('time out'));
        const onProgress = vi.fn();
        const result = await segmentLyricsWithAi(lines, { onProgress });
        expect(vi.mocked(requestLyricSegmentation).mock.calls.map(([batch]) => batch.length)).toEqual([4, 4, 2]);
        expect(result.boundaries).toEqual([null, null, null, null, ...lines.slice(4).map(line => [line])]);
        expect(result.appliedCount).toBe(6);
        expect(result.failures).toEqual(['time out']);
        expect(onProgress).toHaveBeenLastCalledWith({ done: 10, total: 10 });
    });

    it('只对截断批次切半一次，保留成功半批对应的原行', async () => {
        const truncated = new HaloMusicAiError('输出不完整', 'SEGMENTATION_TRUNCATED');
        vi.mocked(requestLyricSegmentation).mockRejectedValueOnce(truncated).mockRejectedValueOnce(truncated);
        const onProgress = vi.fn();
        const result = await segmentLyricsWithAi(['甲', '', '乙', '丙', '丁'], { onProgress });
        expect(vi.mocked(requestLyricSegmentation).mock.calls.map(([batch]) => batch)).toEqual([
            ['甲', '乙', '丙', '丁'], ['甲', '乙'], ['丙', '丁'],
        ]);
        expect(result.boundaries).toEqual([null, null, null, ['丙'], ['丁']]);
        expect(result.failures).toEqual(['输出不完整']);
        expect(onProgress.mock.calls).toEqual([[{ done: 3, total: 5 }], [{ done: 5, total: 5 }]]);
    });

    it('单行截断时保留错误类型，停止追加请求', async () => {
        const truncated = new HaloMusicAiError('输出不完整', 'SEGMENTATION_TRUNCATED');
        vi.mocked(requestLyricSegmentation).mockRejectedValueOnce(truncated);
        await expect(segmentLyricsWithAi(['听见风'])).rejects.toBe(truncated);
        expect(requestLyricSegmentation).toHaveBeenCalledTimes(1);
    });
});
