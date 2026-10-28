import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // static HTML in out/: bundled into the Android app as its HUD screen (android/app/build.gradle.kts)
  output: "export",
};

export default nextConfig;
