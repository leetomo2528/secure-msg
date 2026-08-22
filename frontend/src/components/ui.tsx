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
        className="flex w-full items-center justify-between px-3.5 py-3 transition hover:bg-fg/[0.03]"
      >
        <span className="flex items-center gap-2 text-xs font-semibold text-tx-2">
          <span className="text-tx-4">{icon}</span>
          {title}
          {typeof badge === "number" && (
            <span className="rounded-full bg-accent-tx/10 px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-accent-tx">
              {badge}
            </span>
          )}
        </span>
        <svg
          width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden
          className={`text-tx-4 transition-transform ${open ? "rotate-180" : ""}`}
        >
          <path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {open && <div className="space-y-2.5 px-3.5 pb-3.5 animate-rise">{children}</div>}
    </div>
  );
}

/**
 * Segmented tab switch (login/register, SMS/chat).
 *
 * `tone` picks how the active tab reads: "brand" is the gradient pill used on
 * the dark app shell; "surface" is a raised pill in the surrounding surface
 * color, which is what stays legible on the theme-following onboarding card.
 */
export function Segmented<T extends string>({
  options, value, onChange, tone = "brand",
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
  tone?: "brand" | "surface";
}) {
  const active = tone === "brand"
    ? "bg-brand-gradient font-semibold text-white shadow"
    : "bg-night-soft font-semibold text-tx-1 shadow-bubble";
  return (
    <div
      className={`grid gap-1 rounded-xl p-1 ${tone === "brand" ? "bg-fg/[0.04] ring-1 ring-fg/10" : "bg-fg/[0.06]"}`}
      style={{ gridTemplateColumns: `repeat(${options.length}, 1fr)` }}
    >
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`rounded-lg py-2 text-sm transition ${
            value === opt.value ? active : "text-tx-3 hover:text-tx-1"
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
