import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import './haloAuth.css';

export function HaloDialog({ title, onClose, children }: {
    title: string;
    onClose: () => void;
    children: ReactNode;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const titleId = useId();
    useEffect(() => {
        const element = dialog.current;
        element?.showModal();
        return () => { if (element?.open) element.close(); };
    }, []);

    return <dialog ref={dialog} className="halo-auth-dialog" aria-labelledby={titleId}
        data-folia-keyboard-window="true"
        onCancel={event => { event.preventDefault(); onClose(); }}>
        <header className="halo-auth-header">
            <h2 id={titleId}>{title}</h2>
            <button type="button" className="halo-auth-close" aria-label="关闭" onClick={onClose}>
                <X size={19} aria-hidden="true" />
            </button>
        </header>
        {children}
    </dialog>;
}
