/** Shared logo mark: shield + message lines on the brand gradient. */
export default function BrandMark({ className = "h-8 w-8 rounded-xl" }: { className?: string }) {
  return (
    <div className={`grid shrink-0 place-items-center bg-brand-gradient shadow-glow ${className}`} aria-hidden>
      <svg width="60%" height="60%" viewBox="0 0 24 24" fill="none">
        <path
          d="M12 2.8l7.2 2.6v5.1c0 4.7-3.1 7.9-7.2 9.7-4.1-1.8-7.2-5-7.2-9.7V5.4L12 2.8z"
          fill="rgba(12, 16, 45, 0.92)"
        />
        <path d="M8.4 10.6h7.2M8.4 13.6h4.4" stroke="#c7d2fe" strokeWidth="1.7" strokeLinecap="round" />
      </svg>
    </div>
  );
}
