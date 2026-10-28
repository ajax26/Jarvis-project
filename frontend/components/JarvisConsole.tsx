"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import { setSphereState, type AssistantState } from "./DotSphere";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8000/ws";

// "play": text holds the Singkro web player URL
type Message = { type: "system" | "reply" | "user" | "error" | "play"; text: string };
// same events from the backend WebSocket and from the Android app (HudActivity)
type ServerEvent =
  | { type: "system"; text: string }
  | { type: "user"; text: string }
  | { type: "play"; url: string }
  | { type: "error"; text: string }
  | { type: "delta"; text: string }
  | { type: "state"; state: AssistantState }
  | { type: "start" }
  | { type: "done" };

// inside the Android app, HudActivity injects SingkroBridge and delivers events through window.singkroEvent
type Bridge = { ask(text: string): void; listen(): void };
declare global {
  interface Window {
    SingkroBridge?: Bridge;
    singkroEvent?: (ev: ServerEvent) => void;
  }
}

const STATUS: Record<AssistantState, string> = {
  idle: "ONLINE",
  listening: "LISTENING...",
  thinking: "PROCESSING...",
  speaking: "SPEAKING...",
};

export default function JarvisConsole() {
  const [online, setOnline] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [state, setState] = useState<AssistantState>("idle");
  const [inApp, setInApp] = useState(false);
  const ws = useRef<WebSocket | null>(null);
  const log = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const show = (s: AssistantState) => {
      setState(s);
      setSphereState(s);
    };
    const onEvent = (ev: ServerEvent) => {
      if (ev.type === "start") {
        show("thinking");
        setMessages((m) => [...m, { type: "reply", text: "" }]);
      } else if (ev.type === "delta") {
        // stream tokens into the reply opened by "start" (system lines may arrive after it)
        setMessages((m) => {
          const i = m.findLastIndex((x) => x.type === "reply");
          return m.map((x, j) => (j === i ? { ...x, text: x.text + ev.text } : x));
        });
      } else if (ev.type === "done") {
        show("idle");
        setMessages((m) => m.filter((x) => x.type !== "reply" || x.text));
      } else if (ev.type === "state") {
        show(ev.state);
      } else if (ev.type === "play") {
        setMessages((m) => [...m, { type: "play", text: ev.url }]);
      } else {
        setMessages((m) => [...m, { type: ev.type, text: ev.text }]);
      }
    };

    if (window.SingkroBridge) {
      window.singkroEvent = onEvent;
      setInApp(true);
      setOnline(true);
      return () => {
        window.singkroEvent = undefined;
      };
    }

    let alive = true;
    let retry: ReturnType<typeof setTimeout>;
    const connect = () => {
      const sock = new WebSocket(WS_URL);
      ws.current = sock;
      sock.onopen = () => setOnline(true);
      sock.onmessage = (e) => onEvent(JSON.parse(e.data));
      sock.onclose = () => {
        setOnline(false);
        show("idle");
        if (alive) retry = setTimeout(connect, 2000);
      };
    };
    connect();

    return () => {
      alive = false;
      clearTimeout(retry);
      ws.current?.close();
    };
  }, []);

  useEffect(() => {
    log.current?.scrollTo({ top: log.current.scrollHeight });
  }, [messages]);

  const busy = state !== "idle";

  const send = (e: FormEvent) => {
    e.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    if (window.SingkroBridge) {
      window.SingkroBridge.ask(text); // the app echoes it back as a "user" event
    } else {
      if (ws.current?.readyState !== WebSocket.OPEN) return;
      ws.current.send(text);
      setMessages((m) => [...m, { type: "user", text }]);
    }
    setInput("");
  };

  return (
    <section className="w-full max-w-xl px-4">
      <p className="mb-4 text-center text-sm tracking-[0.5em] text-jarvis-blue/80" aria-live="polite">
        {!online ? "CONNECTING..." : STATUS[state]}
      </p>

      <div
        ref={log}
        className="mb-3 h-36 overflow-y-auto border border-jarvis-line bg-jarvis-dark/60 p-3 font-sans text-sm whitespace-pre-line"
        aria-live="polite"
      >
        {messages.map((m, i) =>
          m.type === "play" ? (
            // a link, not window.open: popups opened from a WebSocket message get blocked
            /^https?:\/\//.test(m.text) && (
              <p key={i}>
                <a href={m.text} target="_blank" rel="noopener noreferrer" className="text-jarvis-neon underline">
                  ▶ Singkro 플레이어에서 열기
                </a>
              </p>
            )
          ) : (
            <p
              key={i}
              className={
                m.type === "user" ? "text-right text-jarvis-neon" : m.type === "error" ? "text-red-300" : "text-jarvis-blue"
              }
            >
              {m.type === "user" ? m.text : `› ${m.text}`}
            </p>
          ),
        )}
      </div>

      <form onSubmit={send} className="flex gap-2">
        {inApp && (
          <button
            type="button"
            onClick={() => window.SingkroBridge?.listen()}
            disabled={busy}
            className="border border-jarvis-dim px-4 text-xs tracking-[0.3em] text-jarvis-neon hover:bg-jarvis-line disabled:opacity-40"
          >
            MIC
          </button>
        )}
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={inApp ? "'씽크'라고 부르거나 입력하세요..." : "SINGKRO에게 말하기..."}
          aria-label="Message"
          className="flex-1 border border-jarvis-line bg-jarvis-dark px-3 py-2 font-sans text-sm text-jarvis-neon placeholder:text-jarvis-dim focus:border-jarvis-blue focus:outline-none"
        />
        <button
          type="submit"
          disabled={!online || busy}
          className="border border-jarvis-dim px-4 text-xs tracking-[0.3em] text-jarvis-neon hover:bg-jarvis-line disabled:opacity-40"
        >
          SEND
        </button>
      </form>
    </section>
  );
}
