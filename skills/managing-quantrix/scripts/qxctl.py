#!/usr/bin/env python3
"""
qxctl — Client library and CLI for the Quantrix Server Plugin.

Library usage:
    from qxctl import QxClient
    qx = QxClient()
    qx.eval('matrices.collect { it.name }')

CLI usage:
    qxctl.py status
    qxctl.py eval << 'EOF'
    |Revenue::Q1:Net Revenue|.value
    EOF
    qxctl.py eval 'api.types()'
    qxctl.py --help
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_PORT = 8182
DEFAULT_HOST = "127.0.0.1"


# ── Exception ────────────────────────────────────────────────────────

class QxError(Exception):
    """Raised when the server returns an error response."""
    def __init__(self, code, message, status=0):
        self.code = code
        self.message = message
        self.status = status
        super().__init__(f"{code}: {message}")


# ── Client library ───────────────────────────────────────────────────

class QxClient:
    """Client for the Quantrix Server Plugin HTTP API.

    Args:
        host: Server host (default: 127.0.0.1 or $QX_HOST)
        port: Server port (default: 8182 or $QX_PORT)
        model: Model name. If None, auto-detected when only one model is open.
        token: Auth token. If None, read from $QX_TOKEN or server token file.
    """

    TOKEN_PATH = os.path.expanduser(
        "~/Library/Application Support/Quantrix/.server-token")

    def __init__(self, host=None, port=None, model=None, token=None):
        self.host = host or os.environ.get("QX_HOST", DEFAULT_HOST)
        self.port = port or int(os.environ.get("QX_PORT", DEFAULT_PORT))
        self.model = model or os.environ.get("QX_MODEL")
        self._base = f"http://{self.host}:{self.port}"
        self._token = token
        self._explicit_token = token is not None

    # ── HTTP ─────────────────────────────────────────────────────────

    def _resolve_token(self):
        if self._explicit_token:
            return self._token
        token = os.environ.get("QX_TOKEN")
        if token:
            return token
        try:
            with open(self.TOKEN_PATH) as f:
                return f.read().strip()
        except FileNotFoundError:
            raise QxError("no_token",
                "No auth token. Set QX_TOKEN or ensure the server is running.")

    def _http(self, method, path, body=None, content_type=None):
        url = self._base + path
        data = None
        if body is not None:
            data = body.encode("utf-8") if isinstance(body, str) else body
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self._resolve_token()}")
        if data is not None and content_type is not None:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8") if e.fp else ""
            try:
                d = json.loads(raw)
            except (json.JSONDecodeError, ValueError):
                raise QxError("http_error", raw or f"HTTP {e.code}", e.code)
            err = d.get("error", {})
            if isinstance(err, dict):
                raise QxError(err.get("code", "error"), err.get("message", str(d)), e.code)
            raise QxError("error", str(err), e.code)
        except urllib.error.URLError as e:
            raise QxError("connection_failed", str(e.reason))

    def _resolve_model(self):
        if self.model:
            return self.model
        models = self._http("GET", "/models")
        if not isinstance(models, list) or len(models) == 0:
            raise QxError("no_model", "No models are open")
        if len(models) == 1:
            self.model = models[0]["id"]
            return self.model
        names = [m.get("name", m.get("id")) for m in models]
        raise QxError("multiple_models",
                      f"Multiple models open: {names}. Specify one with model=")

    # ── Server ───────────────────────────────────────────────────────

    def status(self):
        """Server health check and model list."""
        return self._http("GET", "/status")

    def models(self):
        """List all open models."""
        return self._http("GET", "/models")

    # ── Scripting ────────────────────────────────────────────────────

    def eval(self, script, model=None):
        """Execute QGroovy in the Quantrix scripting context (sandboxed).

        The script gets the full scripting context: model, matrices,
        getSelection(), pipe syntax (|...|), and the `api` helper
        for scripting introspection and model-health checks. Undo-wrapped.
        """
        mid = urllib.request.quote(model or self._resolve_model(), safe="")
        d = self._http("POST", f"/models/{mid}/script",
                        body=script, content_type="text/x-groovy")
        return d.get("result") if isinstance(d, dict) else d

    def eval_unsafe(self, script):
        """Execute raw Groovy without sandbox (system-level).

        No model context, no pipe syntax, no undo. Full JDK access.
        The 'quantrix' variable provides the app instance.
        """
        d = self._http("POST", "/system/script-unsafe",
                        body=script, content_type="text/x-groovy")
        return d.get("result") if isinstance(d, dict) else d

    # ── Plugin management ────────────────────────────────────────────

    def plugins(self):
        """List loaded groovy plugins."""
        return self._http("GET", "/loader/plugins")

    def reload_plugin(self, plugin_id):
        """Reload a specific groovy plugin by ID or directory name."""
        enc = urllib.request.quote(plugin_id, safe="")
        return self._http("POST", f"/loader/plugins/{enc}/reload")

    def reload_all(self):
        """Reload all groovy plugins (restarts the server)."""
        return self._http("POST", "/loader/reload")

# ══════════════════════════════════════════════════════════════════════
# CLI
# ══════════════════════════════════════════════════════════════════════

def _is_tty():
    return hasattr(sys.stdout, "isatty") and sys.stdout.isatty()


def _print_result(data, args):
    if getattr(args, "raw", False):
        print(json.dumps(data) if isinstance(data, (dict, list)) else data)
    elif _is_tty() and not getattr(args, "json", False):
        print(json.dumps(data, indent=2) if isinstance(data, (dict, list)) else data)
    else:
        print(json.dumps(data) if isinstance(data, (dict, list)) else data)


def _print_table(rows, headers, args):
    if getattr(args, "json", False) or not _is_tty():
        print(json.dumps(rows, indent=2 if getattr(args, "pretty", False) else None))
        return
    if not rows:
        print("(no results)")
        return
    widths = {h: len(h) for h in headers}
    for row in rows:
        for h in headers:
            widths[h] = max(widths[h], len(str(row.get(h, ""))))
    print("  ".join(h.upper().ljust(widths[h]) for h in headers))
    print("  ".join("\u2500" * widths[h] for h in headers))
    for row in rows:
        print("  ".join(str(row.get(h, "")).ljust(widths[h]) for h in headers))


def _make_client(args):
    return QxClient(
        host=args.host,
        port=args.port,
        model=getattr(args, "model", None),
        token=args.token,
    )


def _run(args, fn, table_headers=None):
    try:
        qx = _make_client(args)
        result = fn(qx)
        if table_headers and isinstance(result, list):
            _print_table(result, table_headers, args)
        else:
            _print_result(result, args)
        return True
    except QxError as e:
        if _is_tty() and not getattr(args, "json", False):
            print(f"Error: {e.message}", file=sys.stderr)
        else:
            print(json.dumps({"error": e.code, "message": e.message}), file=sys.stderr)
        return False


def _read_script(args):
    script = args.script
    if script is None or script == "-":
        script = sys.stdin.read()
    if not script.strip():
        print("Error: No script provided. Pass as argument or pipe to stdin.", file=sys.stderr)
        return None
    return script


# ── Commands ─────────────────────────────────────────────────────────

def cmd_status(args):
    return _run(args, lambda q: q.status())

def cmd_models(args):
    return _run(args, lambda q: q.models(), ["id", "name", "dirty", "readOnly"])

def cmd_eval(args):
    script = _read_script(args)
    if script is None:
        return False
    return _run(args, lambda q: q.eval(script))

def cmd_eval_unsafe(args):
    script = _read_script(args)
    if script is None:
        return False
    return _run(args, lambda q: q.eval_unsafe(script))

def cmd_plugins(args):
    return _run(args, lambda q: q.plugins(), ["id", "directory", "version", "status"])

def cmd_reload_plugin(args):
    return _run(args, lambda q: q.reload_plugin(args.plugin_id))

def cmd_reload_all(args):
    return _run(args, lambda q: q.reload_all())


# ── Argument parser ──────────────────────────────────────────────────

def build_parser():
    p = argparse.ArgumentParser(
        prog="qxctl",
        description="CLI for the Quantrix Server Plugin.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""examples:
  qxctl status
  qxctl models
  qxctl eval 'matrices.collect { it.name }'
  qxctl eval << 'EOF'
    |Revenue::Q1:Net Revenue|.value
  EOF
  qxctl eval-unsafe 'System.getProperty("user.home")'
  qxctl plugins
  qxctl reload-all

environment:
  QX_PORT    Server port (default: 8182)
  QX_HOST    Server host (default: 127.0.0.1)
  QX_MODEL   Model name (auto-detected if only one model open)
  QX_TOKEN   Auth token (auto-detected from server token file)""",
    )

    p.add_argument("--port", "-p", type=int, default=None, help="Server port")
    p.add_argument("--host", type=str, default=None, help="Server host")
    p.add_argument("--model", "-m", type=str, default=None, help="Model name")
    p.add_argument("--json", "-j", action="store_true", help="Force JSON output")
    p.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    p.add_argument("--raw", action="store_true", help="Raw response body only")
    p.add_argument("--token", type=str, default=None, help="Auth token")

    sub = p.add_subparsers(dest="command", metavar="COMMAND")

    sub.add_parser("status", help="Server health + model list")
    sub.add_parser("models", help="List all open models")

    sp = sub.add_parser("eval", help="Execute QGroovy script (sandboxed, pipe syntax, undo-wrapped)")
    sp.add_argument("script", nargs="?", default=None,
                    help="Groovy script (reads stdin if omitted)")

    sp = sub.add_parser("eval-unsafe", help="Execute raw Groovy (system-level, no sandbox)")
    sp.add_argument("script", nargs="?", default=None,
                    help="Groovy script (reads stdin if omitted)")

    sub.add_parser("plugins", help="List loaded groovy plugins")

    sp = sub.add_parser("reload-plugin", help="Reload a groovy plugin")
    sp.add_argument("plugin_id", help="Plugin ID or directory name")

    sub.add_parser("reload-all", help="Reload all groovy plugins (restarts server)")

    return p


COMMANDS = {
    "status": cmd_status, "models": cmd_models,
    "eval": cmd_eval, "eval-unsafe": cmd_eval_unsafe,
    "plugins": cmd_plugins, "reload-plugin": cmd_reload_plugin,
    "reload-all": cmd_reload_all,
}


def main():
    parser = build_parser()
    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(0)
    fn = COMMANDS.get(args.command)
    if fn is None:
        parser.print_help()
        sys.exit(1)
    ok = fn(args)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
