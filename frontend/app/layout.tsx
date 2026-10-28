import type { Metadata } from "next";
import { Orbitron } from "next/font/google";
import "./globals.css";

const hud = Orbitron({ subsets: ["latin"], variable: "--font-hud" });

export const metadata: Metadata = {
  title: "SINGKRO",
  description: "도어스코리아 AI 어시스턴트",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={hud.variable}>
      <body>{children}</body>
    </html>
  );
}
