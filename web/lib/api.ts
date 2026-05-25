export const API_BASE =
  process.env.NEXT_PUBLIC_CORECODER_API?.replace(/\/$/, "") ?? "http://localhost:8080";

export type SessionSummary = {
  id: string;
  cwd: string;
  created: string;
  modified: string;
  messageCount: number;
  firstMessage: string;
  running: boolean;
  tokens: {
    input: number;
    output: number;
  };
};

export type FileEntry = {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
};

export type ModelInfo = {
  model: string;
  baseUrl: string;
  temperature: number;
  maxContextTokens: number;
};

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      message = body.error ?? message;
    } catch {
      // Keep the status message when the response is not JSON.
    }
    throw new Error(message);
  }
  return res.json() as Promise<T>;
}

export function eventUrl(sessionId: string) {
  return `${API_BASE}/api/agent/${encodeURIComponent(sessionId)}/events`;
}

export async function getHome() {
  return json<{ home: string; cwd: string }>("/api/home");
}

export async function getModels() {
  return json<ModelInfo>("/api/models");
}

export async function getSessions() {
  return json<{ sessions: SessionSummary[] }>("/api/sessions");
}

export async function createSession(cwd: string) {
  return json<{ session: SessionSummary }>("/api/agent/new", {
    method: "POST",
    body: JSON.stringify({ cwd }),
  });
}

export async function sendMessage(sessionId: string, message: string) {
  return json<{ accepted: true }>(`/api/agent/${encodeURIComponent(sessionId)}/messages`, {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}

export async function listFiles(path: string) {
  return json<{ path: string; entries: FileEntry[] }>(
    `/api/files/${encodeURIComponent(path)}?type=list`,
  );
}

export async function readFile(path: string) {
  return json<{ path: string; name: string; content: string }>(
    `/api/files/${encodeURIComponent(path)}?type=read`,
  );
}
