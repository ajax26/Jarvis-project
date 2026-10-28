import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        jarvis: {
          black: "#111522",
          dark: "#1E2638",
          gray: "#1E2638",
          line: "#303D59",
          dim: "#536684",
          blue: "#98A9C4",
          neon: "#DCE3EF",
        },
      },
      fontFamily: {
        hud: ["var(--font-hud)", "ui-monospace", "monospace"],
      },
      boxShadow: {
        neon: "0 0 8px #98A9C4, 0 0 24px rgba(220,227,239,0.3)",
        "neon-lg": "0 0 16px #98A9C4, 0 0 48px rgba(152,169,196,0.4), inset 0 0 24px rgba(220,227,239,0.2)",
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
