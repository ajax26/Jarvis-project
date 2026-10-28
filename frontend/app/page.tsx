const corners = [
  "top-4 left-4 border-t-2 border-l-2",
  "top-4 right-4 border-t-2 border-r-2",
  "bottom-4 left-4 border-b-2 border-l-2",
  "bottom-4 right-4 border-b-2 border-r-2",
];

export default function Home() {
  return (
    <main className="hud-grid relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-jarvis-black">
      {/* scan line */}
      <div className="pointer-events-none absolute inset-x-0 top-0 h-24 animate-scan bg-gradient-to-b from-transparent via-jarvis-neon/10 to-transparent" />

      {corners.map((c) => (
        <span key={c} className={`absolute h-10 w-10 border-jarvis-neon/60 ${c}`} />
      ))}

      <header className="absolute top-8 flex w-full justify-between px-16 text-xs tracking-[0.3em] text-jarvis-blue/70">
        <span>SYS // ONLINE</span>
        <span>STARK INDUSTRIES</span>
      </header>

      {/* central reactor */}
      <div className="relative flex h-80 w-80 items-center justify-center" role="status" aria-label="JARVIS loading">
        <div className="absolute inset-0 animate-spin-slow rounded-full border border-dashed border-jarvis-neon/40" />
        <div className="absolute inset-6 animate-spin-reverse rounded-full border-2 border-transparent border-t-jarvis-neon border-b-jarvis-blue shadow-neon" />
        <div className="absolute inset-14 animate-spin-mid rounded-full border-4 border-jarvis-line border-l-jarvis-neon border-r-jarvis-neon" />
        <div className="absolute inset-24 animate-pulse-glow rounded-full border border-jarvis-neon bg-jarvis-gray shadow-neon-lg" />
        <span className="relative text-lg font-bold tracking-[0.4em] text-jarvis-neon drop-shadow-[0_0_6px_#00f0ff]">
          JARVIS
        </span>
      </div>

      <p className="mt-12 text-sm tracking-[0.5em] text-jarvis-blue/80">INITIALIZING...</p>

      <footer className="absolute bottom-8 flex w-full justify-between px-16 text-[10px] tracking-[0.3em] text-jarvis-dim">
        <span>WS://localhost:8000/ws</span>
        <span>v0.1.0</span>
      </footer>
    </main>
  );
}
