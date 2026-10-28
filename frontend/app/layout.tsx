import type { Metadata } from "next";
import { Orbitron } from "next/font/google";
import "./globals.css";

const hud = Orbitron({ subsets: ["latin"], variable: "--font-hud" });

export const metadata: Metadata = {
  title: "J.A.R.V.I.S.",
  description: "Just A Rather Very Intelligent System",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className={hud.variable}>
      <body>{children}</body>
    </html>
  );
}
