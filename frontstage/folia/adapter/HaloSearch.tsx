import { useEffect, useId, useState } from 'react';
import type { SearchKind } from '../stores/useSearchNavigationStore';
import { haloGet, haloSettings, musicSearch, searchKind } from './haloApi';

interface Suggestion { id: string; name: string; type: string; sub?: string }

export function HaloSearchField({ query, submittedQuery, searching, label, onQuery, onSubmit }: {
    query: string; submittedQuery: string; searching: boolean; label: string;
    onQuery: (query: string) => void; onSubmit: (kind?: SearchKind) => void;
}) {
    const listId = useId();
    const [items, setItems] = useState<Suggestion[]>([]);
    const [active, setActive] = useState(-1);
    const [focused, setFocused] = useState(false);
    const [dismissed, setDismissed] = useState('');
    const [matched, setMatched] = useState('');
    const [composing, setComposing] = useState(false);
    const word = query.trim();
    const canSuggest = haloSettings.suggest && focused && !composing && dismissed !== query
        && word !== submittedQuery.trim() && !!word && word.length <= 100;
    const show = canSuggest && matched === query && items.length > 0;
    useEffect(() => {
        if (!canSuggest) return;
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            void haloGet<{ groups: { items: Suggestion[] }[] }>('/music/search/suggest', { q: word }, controller.signal)
                .then(result => {
                    if (!controller.signal.aborted) {
                        setItems(result.groups.flatMap(group => group.items).slice(0, 8));
                        setMatched(query);
                        setActive(-1);
                    }
                }).catch(() => { if (!controller.signal.aborted) setItems([]); });
        }, 250);
        return () => { window.clearTimeout(timer); controller.abort(); };
    }, [canSuggest, query, word]);

    function pick(item: Suggestion) {
        setDismissed(item.name);
        onQuery(item.name);
        onSubmit(searchKind(item.type));
    }

    return (
        <form className="search-field halo-search-field flex-1" onSubmit={event => {
            event.preventDefault();
            if (composing) return;
            if (show && active >= 0) pick(items[active]);
            else { setDismissed(query); onSubmit(); }
        }}>
            <span className={`search-field-mark ${searching ? 'is-busy' : ''}`} aria-hidden="true" />
            <input aria-label="搜索网易云音乐" role="combobox" aria-autocomplete="list" autoComplete="off"
                aria-expanded={show} aria-controls={show ? listId : undefined}
                aria-activedescendant={show && active >= 0 ? listId + '-' + active : undefined}
                autoFocus value={query} maxLength={100} placeholder={label}
                onChange={event => { setDismissed(''); onQuery(event.target.value); }}
                onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
                onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)}
                onKeyDown={event => {
                    if (event.nativeEvent.isComposing || !show) return;
                    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                        event.preventDefault();
                        setActive(current => event.key === 'ArrowDown' ? Math.min(current + 1, items.length - 1) : Math.max(current - 1, 0));
                    } else if (event.key === 'Escape') {
                        event.preventDefault();
                        event.stopPropagation();
                        setDismissed(query);
                    }
                }} />
            <button type="submit" className="halo-search-submit" aria-label="搜索" disabled={!word}>搜索</button>
            {show && <ul id={listId} className="halo-search-suggestions" role="listbox" aria-label="搜索联想">
                {items.map((item, index) => <li role="option" key={item.type + ':' + item.id} id={listId + '-' + index}
                    aria-selected={index === active} onPointerDown={event => event.preventDefault()}
                    onClick={() => pick(item)}>
                    <span>{item.name}</span><small>{item.sub || ({ song: '单曲', artist: '歌手', playlist: '歌单', album: '专辑' }[searchKind(item.type)])}</small>
                </li>)}
            </ul>}
        </form>
    );
}

export function HaloHotSearch({ onSelect }: { onSelect: (query: string) => void }) {
    const [data, setData] = useState<{ keyword: string }[]>([]);
    const [failed, setFailed] = useState(false);
    const [attempt, setAttempt] = useState(0);
    useEffect(() => {
        const controller = new AbortController();
        setFailed(false);
        void haloGet<{ hotKeywords: { keyword: string }[] }>(musicSearch, {}, controller.signal)
            .then(result => setData(result.hotKeywords))
            .catch(() => { if (!controller.signal.aborted) setFailed(true); });
        return () => controller.abort();
    }, [attempt]);
    if (failed) return <p role="status">热搜暂时无法加载。 <button className="search-flat-button" type="button" onClick={() => setAttempt(i => i + 1)}>重试</button></p>;
    if (!data.length) return null;
    return (
        <section className="halo-hot-search" aria-label="网易云热搜">
            <h2>网易云热搜</h2>
            <ul>{data.map(item => <li key={item.keyword}>
                <button type="button" className="search-flat-button" onClick={() => onSelect(item.keyword)}>{item.keyword}</button>
            </li>)}</ul>
        </section>
    );
}
