import DotSphere from "@/components/DotSphere";
import JarvisConsole from "@/components/JarvisConsole";

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
        <span>DOORS KOREA</span>
      </header>

      {/* central reactor */}
      <div className="relative flex h-[min(80vw,26rem)] w-[min(80vw,26rem)] items-center justify-center" role="status" aria-label="SINGKRO loading">
        <div className="absolute inset-0 animate-spin-slow rounded-full border border-dashed border-jarvis-neon/30" />
        <div className="absolute inset-3 animate-spin-reverse rounded-full border border-transparent border-t-jarvis-neon/70 border-b-jarvis-blue/70" />
        <div className="absolute inset-[30%] rounded-full bg-jarvis-neon/10 blur-3xl" />
        <DotSphere className="absolute inset-0 h-full w-full" />
        <span className="relative text-lg font-bold tracking-[0.4em] text-jarvis-neon drop-shadow-[0_0_6px_#98A9C4]">
          SINGKRO
        </span>
      </div>

      <div className="mt-6">
        <JarvisConsole />
      </div>

      <footer className="absolute bottom-8 flex w-full justify-between px-16 text-[10px] tracking-[0.3em] text-jarvis-dim">
        <span>WS://localhost:8000/ws</span>
        <span>v0.1.0</span>
      </footer>
    </main>
  );
}
