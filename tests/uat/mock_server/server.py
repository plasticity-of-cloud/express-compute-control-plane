"""
Lightweight mock HTTP server for CLI UAT.

Records all incoming requests and serves pre-configured responses.
Uses only Python stdlib — no extra dependencies.
"""
import json
import re
import socketserver
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Optional


class _Request:
    def __init__(self, method: str, path: str, body: str, headers: dict):
        self.method = method
        self.path = path
        self.body = body
        self.headers = headers


class _Handler(BaseHTTPRequestHandler):
    """Dispatches requests to the owning MockServer instance."""

    def log_message(self, fmt, *args):
        pass  # silence default access log

    def _dispatch(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length else ""
        req = _Request(self.command, self.path, body, dict(self.headers))
        self.server.owner.record(req)
        status, resp_body = self.server.owner.match_response(self.command, self.path)
        encoded = resp_body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _dispatch


class MockServer:
    """
    Thread-safe configurable mock HTTP server.

    Usage::

        server = MockServer()
        port = server.start()
        server.set_response("GET", "/clusters/my-cluster", 200, '{"state":"ready"}')
        # ... run CLI ...
        assert server.get_request_count("GET", "/clusters/my-cluster") == 1
        server.stop()
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._responses: list[tuple[str, str, int, str]] = []  # (method, pattern, status, body)
        self._requests: list[_Request] = []
        self._server: Optional[HTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self, port: int = 0) -> int:
        """Start the server. Returns the bound port."""
        self._server = HTTPServer(("127.0.0.1", port), _Handler)
        self._server.owner = self
        actual_port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return actual_port

    def stop(self):
        if self._server:
            self._server.shutdown()
            self._server = None
        self._thread = None

    # ------------------------------------------------------------------
    # Response configuration
    # ------------------------------------------------------------------

    def set_response(self, method: str, path_pattern: str, status_code: int, body: str):
        """
        Register a response for the given HTTP method and path pattern.
        path_pattern is a regex matched against the full request path.
        Later registrations take priority (first-match from the end).
        """
        with self._lock:
            self._responses.append((method.upper(), path_pattern, int(status_code), body))

    def reset_responses(self):
        with self._lock:
            self._responses.clear()

    def match_response(self, method: str, path: str) -> tuple[int, str]:
        """Return (status, body) for the last matching rule, or 404 if none."""
        with self._lock:
            for m, pattern, status, body in reversed(self._responses):
                if m == method.upper() and re.fullmatch(pattern, path):
                    return status, body
        return 404, json.dumps({"__type": "NotFoundException", "message": f"No mock for {method} {path}"})

    # ------------------------------------------------------------------
    # Request recording
    # ------------------------------------------------------------------

    def record(self, req: _Request):
        with self._lock:
            self._requests.append(req)

    def clear_requests(self):
        with self._lock:
            self._requests.clear()

    def get_request_count(self, method: str, path_pattern: str) -> int:
        with self._lock:
            return sum(
                1 for r in self._requests
                if r.method == method.upper() and re.fullmatch(path_pattern, r.path)
            )

    def get_last_request_body(self, method: str, path_pattern: str) -> Optional[str]:
        with self._lock:
            matches = [
                r.body for r in self._requests
                if r.method == method.upper() and re.fullmatch(path_pattern, r.path)
            ]
        return matches[-1] if matches else None

    def get_all_requests(self) -> list[dict]:
        with self._lock:
            return [{"method": r.method, "path": r.path, "body": r.body} for r in self._requests]
