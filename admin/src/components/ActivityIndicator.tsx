import { ThinkingOrb } from "thinking-orbs";

interface ActivityIndicatorProps {
  label: string;
  state?: "working" | "searching" | "connecting";
  presentation?: "inline" | "panel";
  inverse?: boolean;
  /** Disable inside buttons or an existing live region. */
  announce?: boolean;
}

/** Only mount while an operation is running; human review queues stay static. */
export function ActivityIndicator({
  label,
  state = "working",
  presentation = "inline",
  inverse = false,
  announce = true,
}: ActivityIndicatorProps) {
  return (
    <span
      className={`activity-indicator activity-indicator--${presentation}`}
      role={announce ? "status" : undefined}
      aria-atomic={announce ? true : undefined}
    >
      <ThinkingOrb
        className="activity-orb"
        state={state}
        size={presentation === "panel" ? 64 : 20}
        // Admin uses light surfaces even when the OS prefers dark mode.
        theme={inverse ? "dark" : "light"}
        aria-hidden="true"
      />
      <span>{label}</span>
    </span>
  );
}
