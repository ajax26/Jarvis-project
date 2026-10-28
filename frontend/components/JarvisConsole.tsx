"use client";

import { FormEvent, useEffect, useRef, useState } from "react";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8000/ws";

type Message = { type: "system" | "reply" | "user" | "error"; text: string };
type ServerEvent =
  | { type: "system"; text: string }
  | { type: "error"; text: string }
  | { type: "delta"; text: string }
  | { type: "start" }
  | { type: "done" };

export default function JarvisConsole() {
  const [online, setOnline] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const ws = useRef<WebSocket | null>(null);
  const log = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    let retry: ReturnType<typeof setTimeout>;

    const connect = () => {
      const sock = new WebSocket(WS_URL);
      ws.current = sock;
      sock.onopen = () => setOnline(true);
      sock.onmessage = (e) => {
        const ev: ServerEvent = JSON.parse(e.data);
        if (ev.type === "start") {
          setBusy(true);
          setMessages((m) => [...m, { type: "reply", text: "" }]);
        } else if (ev.type === "delta") {
          // stream tokens into the reply opened by "start" (system lines may arrive after it)
          setMessages((m) => {
            const i = m.findLastIndex((x) => x.type === "reply");
            return m.map((x, j) => (j === i ? { ...x, text: x.text + ev.text } : x));
          });
        } else if (ev.type === "done") {
          setBusy(false);
          setMessages((m) => m.filter((x) => x.type !== "reply" || x.text));
        } else {
          setMessages((m) => [...m, { type: ev.type, text: ev.text }]);
        }
      };
      sock.onclose = () => {
        setOnline(false);
        setBusy(false);
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

  const send = (e: FormEvent) => {
    e.preventDefault();
    const text = input.trim();
    if (!text || busy || ws.current?.readyState !== WebSocket.OPEN) return;
    ws.current.send(text);
    setMessages((m) => [...m, { type: "user", text }]);
    setInput("");
  };

  return (
    <section className="w-full max-w-xl px-4">
      <p className="mb-4 text-center text-sm tracking-[0.5em] text-jarvis-blue/80" aria-live="polite">
        {!online ? "CONNECTING..." : busy ? "PROCESSING..." : "ONLINE"}
      </p>

      <div
        ref={log}
        className="mb-3 h-36 overflow-y-auto border border-jarvis-line bg-jarvis-dark/60 p-3 font-sans text-sm whitespace-pre-line"
        aria-live="polite"
      >
        {messages.map((m, i) => (
          <p
            key={i}
            className={
              m.type === "user" ? "text-right text-jarvis-neon" : m.type === "error" ? "text-red-300" : "text-jarvis-blue"
            }
          >
            {m.type === "user" ? m.text : `› ${m.text}`}
          </p>
        ))}
      </div>

      <form onSubmit={send} className="flex gap-2">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="SINGKRO에게 말하기..."
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
