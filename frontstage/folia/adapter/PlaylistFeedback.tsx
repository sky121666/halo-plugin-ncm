import { AlertCircle, ArrowRight } from 'lucide-react';
import { LibraryRequestError } from './haloLibraryApi';

export type PlaylistIssue = {
    message: string;
    action?: 'review';
    status?: number;
};

export function playlistIssue(error: unknown, writing = false, subject?: '红心' | '收藏'): PlaylistIssue {
    const status = error instanceof LibraryRequestError ? error.status : undefined;
    const detail = error instanceof LibraryRequestError ? error.message : undefined;
    if (status === 401) return { status, message: '请在音乐首页或播放器的「音质」面板登录 Halo。' };
    if (status === 403) return {
        status, message: detail ?? '请在音乐首页检查网易云绑定，并确认歌单权限。',
    };
    if (writing && (status === undefined || status === 0 || status === 408 || status >= 500)) return {
        status, message: subject ? subject + '状态未确认。核对后再操作。' : '提交结果未确认。查看最新歌单后再操作。',
        action: 'review',
    };
    if (writing && status === 404) return {
        status, message: detail ?? '歌单或曲目已不可用。', action: 'review',
    };
    if (!writing && subject && (status === undefined || status === 0 || status >= 500)) {
        return { status, message: '暂时无法读取' + subject + '状态。' };
    }
    return { status, message: detail ?? (writing ? '操作失败，请重试。' : '暂时无法读取歌单。') };
}

export function PlaylistFeedback({ issue, onReview, onRetry, reviewLabel = '查看最新歌单' }: {
    issue?: PlaylistIssue;
    onReview?: () => void;
    onRetry?: () => void;
    reviewLabel?: string;
}) {
    if (!issue) return null;
    return <div role="alert" className="halo-playlist-feedback" data-halo-playlist-feedback>
        <AlertCircle size={17} aria-hidden="true" />
        <div>
            <p>{issue.message}</p>
            <div className="halo-playlist-feedback-actions">
                {issue.action === 'review' && onReview && <button type="button" onClick={onReview} data-halo-review-playlist>
                    {reviewLabel} <ArrowRight size={14} aria-hidden="true" />
                </button>}
                {onRetry && <button type="button" onClick={onRetry}>重新读取</button>}
            </div>
        </div>
    </div>;
}
