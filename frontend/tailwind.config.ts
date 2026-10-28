import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        jarvis: {
          black: "#05070a",
          dark: "#0b0f14",
          gray: "#141a21",
          line: "#1f2a35",
          neon: "#00f0ff",
          blue: "#00d2ff",
          dim: "#0a6b78",
        },
      },
      fontFamily: {
        hud: ["var(--font-hud)", "ui-monospace", "monospace"],
      },
      boxShadow: {
        neon: "0 0 8px #00f0ff, 0 0 24px rgba(0,240,255,0.45)",
        "neon-lg": "0 0 16px #00f0ff, 0 0 48px rgba(0,210,255,0.5), inset 0 0 24px rgba(0,240,255,0.25)",
      },
      keyframes: {
        "spin-reverse": { to: { transform: "rotate(-360deg)" } },
        "pulse-glow": {
          "0%,100%": { opacity: "0.6", transform: "scale(0.95)" },
          "50%": { opacity: "1", transform: "scale(1.05)" },
        },
        scan: {
          "0%": { transform: "translateY(-100%)" },
          "100%": { transform: "translateY(100vh)" },
        },
      },
      animation: {
        "spin-slow": "spin 12s linear infinite",
        "spin-mid": "spin 6s linear infinite",
        "spin-reverse": "spin-reverse 9s linear infinite",
        "pulse-glow": "pulse-glow 2.4s ease-in-out infinite",
        scan: "scan 6s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
