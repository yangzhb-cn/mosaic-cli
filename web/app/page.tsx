"use client";

import {
  ChevronDown,
  ChevronRight,
  Cpu,
  File,
  FileText,
  Folder,
  GitBranch,
  ImageIcon,
  Layers,
  Moon,
  PanelLeft,
  Pencil,
  Plus,
  RefreshCcw,
  Send,
  Trash2,
  X,
  Zap,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  API_BASE,
  FileEntry,
  ModelInfo,
  SessionSummary,
  createSession,
  eventUrl,
  getHome,
  getModels,
  getSessions,
  listFiles,
  readFile,
  sendMessage,
} from "../lib/api";

type ChatItem =
  | { id: string; type: "user"; text: string; time: string }
  | { id: string; type: "assistant"; text: string; model: string; done: boolean }
  | { id: string; type: "tool"; name: string; args: Record<string, unknown> }
  | { id: string; type: "error"; text: string };

type FileNode = FileEntry & {
  loaded?: boolean;
  open?: boolean;
  children?: FileNode[];
};

type Preview = {
  name: string;
  path: string;
  content: string;
};

const nowTime = () =>
  new Intl.DateTimeFormat("en", { hour: "2-digit", minute: "2-digit", hour12: false }).format(
    new Date(),
  );

const compactPath = (path: string) => {
  const parts = path.split("/").filter(Boolean);
  if (parts.length <= 2) return path;
  return `~/${parts.slice(-2).join("/")}`;
};

const briefArgs = (args: Record<string, unknown>) => {
  const text = Object.entries(args)
    .map(([key, value]) => `${key} ${String(value)}`)
    .join(" ");
  return text.length > 110 ? `${text.slice(0, 110)}...` : text;
};

export default function AppPage() {
  const [cwd, setCwd] = useState("");
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [active, setActive] = useState<SessionSummary | null>(null);
  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [model, setModel] = useState<ModelInfo | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [tree, setTree] = useState<FileNode[]>([]);
  const [treeLoading, setTreeLoading] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState("");
  const assistantId = useRef<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const refreshSessions = useCallback(async () => {
    const data = await getSessions();
    setSessions(data.sessions);
    setActive((current) => {
      if (!current) return current;
      return data.sessions.find((item) => item.id === current.id) ?? current;
    });
  }, []);

  const loadRoot = useCallback(async (path: string) => {
    setTreeLoading(true);
    try {
      const data = await listFiles(path);
      setTree(data.entries.map((entry) => ({ ...entry })));
    } finally {
      setTreeLoading(false);
    }
  }, []);

  useEffect(() => {
    getHome()
      .then((home) => {
        setCwd(home.cwd);
        return loadRoot(home.cwd);
      })
      .catch((err: Error) => setError(err.message));
    getModels().then(setModel).catch((err: Error) => setError(err.message));
    refreshSessions().catch((err: Error) => setError(err.message));
  }, [loadRoot, refreshSessions]);

  useEffect(() => {
    if (!active) return;
    const source = new EventSource(eventUrl(active.id));
    source.addEventListener("token", (event) => {
      const data = JSON.parse((event as MessageEvent).data) as { text: string };
      setMessages((items) => {
        const currentId = assistantId.current;
        const hasCurrent = currentId
          ? items.some((item) => item.type === "assistant" && item.id === currentId)
          : false;
        if (!currentId || !hasCurrent) {
          const id = crypto.randomUUID();
          assistantId.current = id;
          return [
            ...items,
            {
              id,
              type: "assistant",
              text: data.text,
              model: model?.model ?? "CoreCoder",
              done: false,
            },
          ];
        }
        return items.map((item) =>
          item.type === "assistant" && item.id === currentId
            ? { ...item, text: item.text + data.text }
            : item,
        );
      });
    });
    source.addEventListener("tool", (event) => {
      const data = JSON.parse((event as MessageEvent).data) as {
        name: string;
        args: Record<string, unknown>;
      };
      setMessages((items) => [
        ...items,
        { id: crypto.randomUUID(), type: "tool", name: data.name, args: data.args },
      ]);
      assistantId.current = null;
    });
    source.addEventListener("done", (event) => {
      const data = JSON.parse((event as MessageEvent).data) as { session: SessionSummary };
      setBusy(false);
      setActive(data.session);
      setSessions((items) => [data.session, ...items.filter((item) => item.id !== data.session.id)]);
      setMessages((items) =>
        items.map((item) =>
          item.type === "assistant" && item.id === assistantId.current
            ? { ...item, done: true }
            : item,
        ),
      );
      assistantId.current = null;
    });
    source.addEventListener("error", (event) => {
      if ("data" in event && event.data) {
        const data = JSON.parse((event as MessageEvent).data) as { message: string };
        setMessages((items) => [
          ...items,
          { id: crypto.randomUUID(), type: "error", text: data.message },
        ]);
        setBusy(false);
      }
    });
    return () => source.close();
  }, [active?.id, model?.model]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages]);

  const startSession = useCallback(async () => {
    if (!cwd) return null;
    const created = await createSession(cwd);
    setActive(created.session);
    setSessions((items) => [created.session, ...items.filter((item) => item.id !== created.session.id)]);
    setMessages([]);
    return created.session;
  }, [cwd]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setError("");
    const session = active ?? (await startSession());
    if (!session) return;
    const nextAssistantId = crypto.randomUUID();
    assistantId.current = nextAssistantId;
    setMessages((items) => [
      ...items,
      { id: crypto.randomUUID(), type: "user", text, time: nowTime() },
      {
        id: nextAssistantId,
        type: "assistant",
        text: "",
        model: model?.model ?? "CoreCoder",
        done: false,
      },
    ]);
    setInput("");
    setBusy(true);
    try {
      await sendMessage(session.id, text);
    } catch (err) {
      setBusy(false);
      const message = err instanceof Error ? err.message : String(err);
      setMessages((items) => [...items, { id: crypto.randomUUID(), type: "error", text: message }]);
    }
  };

  const totals = useMemo(() => {
    const latest = active?.tokens ?? sessions[0]?.tokens;
    return {
      input: latest?.input ?? 0,
      output: latest?.output ?? 0,
    };
  }, [active, sessions]);

  return (
    <main className="shell">
      <aside className={sidebarOpen ? "sidebar" : "sidebar sidebar-collapsed"}>
        <div className="sidebar-head">
          <button className="brand" type="button" title="CoreCoder Web">
            CoreCoder Web
          </button>
          <div className="head-actions">
            <button className="icon-label" type="button" onClick={() => void startSession()}>
              <Plus size={16} />
              New
            </button>
            <button className="icon" type="button" onClick={() => void refreshSessions()} title="Refresh">
              <RefreshCcw size={16} />
            </button>
          </div>
        </div>

        <button className="cwd-button" type="button" title={cwd}>
          {cwd ? compactPath(cwd) : "Loading project..."}
        </button>

        <section className="session-list">
          {sessions.length === 0 ? (
            <div className="empty">No sessions found</div>
          ) : (
            sessions.map((session) => (
              <button
                className={active?.id === session.id ? "session active" : "session"}
                type="button"
                key={session.id}
                onClick={() => {
                  setActive(session);
                  setMessages([]);
                }}
              >
                <span>
                  <strong>{session.firstMessage || "New session"}</strong>
                  <small>{session.messageCount} msgs</small>
                </span>
                <span className="session-tools">
                  <Pencil size={15} />
                  <Trash2 size={15} />
                </span>
              </button>
            ))
          )}
        </section>

        <section className="explorer">
          <div className="explorer-title">
            <span>EXPLORER</span>
            <button className="icon ghost" type="button" onClick={() => cwd && void loadRoot(cwd)}>
              <RefreshCcw size={14} />
            </button>
          </div>
          {treeLoading ? (
            <div className="empty small">Loading files...</div>
          ) : tree.length === 0 ? (
            <div className="empty small">No files found</div>
          ) : (
            <FileTree nodes={tree} onChange={setTree} onPreview={setPreview} />
          )}
        </section>

        <div className="sidebar-footer">
          <button className="footer-action" type="button">
            <Cpu size={16} />
            Models
          </button>
          <button className="footer-action" type="button">
            <Layers size={16} />
            Skills
          </button>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <button
            className="top-button"
            type="button"
            onClick={() => setSidebarOpen((value) => !value)}
            title="Toggle sidebar"
          >
            <PanelLeft size={16} />
          </button>
          <button className="top-button" type="button" title="Theme">
            <Moon size={16} />
          </button>
          <button className="top-tab" type="button">
            <GitBranch size={14} />
            Branches
          </button>
          <button className="top-tab" type="button">
            <FileText size={14} />
            System
          </button>
          <div className="top-stats">
            <span>↑ {formatCount(totals.input)}</span>
            <span>↓ {formatCount(totals.output)}</span>
            <span>&lt;$0.01</span>
          </div>
        </header>

        <div className="chat-scroll" ref={scrollRef}>
          <div className="chat">
            {messages.length === 0 ? (
              <div className="welcome">
                <div className="thinking">Thinking</div>
                <p>
                  Hello. Ask CoreCoder to inspect code, run tools, or explain files in this
                  project.
                </p>
                <small>Connected to {API_BASE}</small>
              </div>
            ) : (
              messages.map((item) => (
                <ChatRow key={item.id} item={item} model={model?.model ?? "CoreCoder"} />
              ))
            )}
            {error && <div className="error-line">{error}</div>}
          </div>
        </div>

        <form className="composer" onSubmit={submit}>
          <button className="composer-icon" type="button" title="Attach image">
            <ImageIcon size={17} />
          </button>
          <input
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder={active ? "Message..." : "Start a new session..."}
          />
          <div className="model-pill">
            <Zap size={14} />
            {model?.model ?? "Model"}
          </div>
          <button className="send" type="submit" disabled={busy || !input.trim()}>
            <Send size={16} />
            Send
          </button>
        </form>
      </section>

      {preview && <FilePreview preview={preview} onClose={() => setPreview(null)} />}
    </main>
  );
}

function ChatRow({ item, model }: { item: ChatItem; model: string }) {
  if (item.type === "user") {
    return (
      <div className="row user-row">
        <div className="user-bubble">{item.text}</div>
        <time>{item.time}</time>
      </div>
    );
  }
  if (item.type === "tool") {
    return (
      <div className="tool-call">
        <strong>{item.name.toLowerCase()}</strong>
        <span>{briefArgs(item.args)}</span>
        <small>2s</small>
        <ChevronDown size={14} />
      </div>
    );
  }
  if (item.type === "error") {
    return <div className="error-line">{item.text}</div>;
  }
  return (
    <div className="row assistant-row">
      <div className="model-name">{model}</div>
      <div className="thinking">Thinking</div>
      {item.text ? <p>{item.text}</p> : <p className="muted">Waiting for response...</p>}
      {!item.done && <div className="typing-dot" />}
    </div>
  );
}

function FileTree({
  nodes,
  onChange,
  onPreview,
}: {
  nodes: FileNode[];
  onChange: (nodes: FileNode[]) => void;
  onPreview: (preview: Preview) => void;
}) {
  const updateNode = (path: string, updater: (node: FileNode) => FileNode) => {
    const walk = (items: FileNode[]): FileNode[] =>
      items.map((node) => {
        if (node.path === path) return updater(node);
        if (node.children) return { ...node, children: walk(node.children) };
        return node;
      });
    onChange(walk(nodes));
  };

  const openNode = async (node: FileNode) => {
    if (!node.isDir) {
      const file = await readFile(node.path);
      onPreview(file);
      return;
    }
    if (node.loaded) {
      updateNode(node.path, (current) => ({ ...current, open: !current.open }));
      return;
    }
    const data = await listFiles(node.path);
    updateNode(node.path, (current) => ({
      ...current,
      open: true,
      loaded: true,
      children: data.entries.map((entry) => ({ ...entry })),
    }));
  };

  return (
    <div className="file-tree">
      {nodes.map((node) => (
        <TreeNode key={node.path} node={node} depth={0} onOpen={openNode} />
      ))}
    </div>
  );
}

function TreeNode({
  node,
  depth,
  onOpen,
}: {
  node: FileNode;
  depth: number;
  onOpen: (node: FileNode) => void;
}) {
  return (
    <div>
      <button
        className="tree-node"
        type="button"
        style={{ paddingLeft: 10 + depth * 14 }}
        onClick={() => void onOpen(node)}
      >
        {node.isDir ? (
          node.open ? (
            <ChevronDown size={13} />
          ) : (
            <ChevronRight size={13} />
          )
        ) : (
          <span className="tree-spacer" />
        )}
        {node.isDir ? <Folder size={15} /> : <File size={15} />}
        <span>{node.name}</span>
      </button>
      {node.open &&
        node.children?.map((child) => (
          <TreeNode key={child.path} node={child} depth={depth + 1} onOpen={onOpen} />
        ))}
    </div>
  );
}

function FilePreview({ preview, onClose }: { preview: Preview; onClose: () => void }) {
  return (
    <div className="preview-backdrop">
      <section className="preview">
        <header>
          <div>
            <strong>{preview.name}</strong>
            <small>{preview.path}</small>
          </div>
          <button className="icon" type="button" onClick={onClose}>
            <X size={16} />
          </button>
        </header>
        <pre>{preview.content}</pre>
      </section>
    </div>
  );
}

function formatCount(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}m`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}k`;
  return String(value);
}
