import type { ReactNode } from "react";

/** Collapsible settings card used by the sidebar footer panels. */
export function CollapsibleCard({
  open, onToggle, icon, title, badge, children,
}: {
  open: boolean;
  onToggle: () => void;
  icon: ReactNode;
  title: string;
  badge?: number;
  children: ReactNode;
}) {
  return (
    <div className="card overflow-hidden">
      <button
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center justify-between px-3.5 py-3 transition hover:bg-white/[0.03]"
      >
        <span className="flex items-center gap-2 text-xs font-semibold text-slate-200">
          <span className="text-slate-500">{icon}</span>
          {title}
          {typeof badge === "number" && (
            <span className="rounded-full bg-teal-400/10 px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-teal-300">
              {badge}
            </span>
          )}
        </span>
        <svg
          width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden
          className={`text-slate-500 transition-transform ${open ? "rotate-180" : ""}`}
        >
          <path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {open && <div className="space-y-2.5 px-3.5 pb-3.5 animate-rise">{children}</div>}
    </div>
  );
}

/** Segmented tab switch (login/register, SMS/chat). */
export function Segmented<T extends string>({
  options, value, onChange,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className="grid gap-1 rounded-xl bg-white/[0.04] p-1 ring-1 ring-white/10" style={{ gridTemplateColumns: `repeat(${options.length}, 1fr)` }}>
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`rounded-lg py-2 text-sm transition ${
            value === opt.value
              ? "bg-brand-gradient font-semibold text-slate-950 shadow"
              : "text-slate-400 hover:text-slate-200"
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
