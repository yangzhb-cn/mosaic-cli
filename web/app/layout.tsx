import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "CoreCoder Web",
  description: "CoreCoder Agent workbench",
  icons: [{ rel: "icon", url: "/icon.svg" }],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
