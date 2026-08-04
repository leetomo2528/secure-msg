import { useStore } from "../store/useStore";

/** Desktop notification on/off. Preference persists in localStorage; the
 * browser permission is requested on first enable. */
export default function NotifyToggle() {
  const notifyEnabled = useStore((s) => s.notifyEnabled);
  const setNotifyEnabled = useStore((s) => s.setNotifyEnabled);
  return (
    <button
      type="button"
      onClick={() => void setNotifyEnabled(!notifyEnabled)}
      aria-pressed={notifyEnabled}
      className="card flex w-full items-center justify-between px-3.5 py-3 transition hover:bg-fg/[0.02]"
    >
      <span className="flex items-center gap-2 text-xs font-semibold text-tx-2">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden className="text-tx-4">
          <path
            d="M6 9.5a6 6 0 0 1 12 0c0 4 1.5 5.5 1.5 5.5h-15S6 13.5 6 9.5zM10 18.5a2 2 0 0 0 4 0"
            stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
          />
        </svg>
        데스크톱 알림
      </span>
      <span
        className={`relative h-5 w-9 rounded-full transition ${
          notifyEnabled ? "bg-brand-gradient" : "bg-fg/10 ring-1 ring-fg/10"
        }`}
        aria-hidden
      >
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all ${
            notifyEnabled ? "left-[18px]" : "left-0.5"
          }`}
        />
      </span>
    </button>
  );
}
