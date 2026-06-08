#!/usr/bin/env python3
import hashlib
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", 8080))


class EchoHandler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} [{self.log_date_time_string()}] {fmt % args}\n")

    def handle_request(self):
        parsed = urlparse(self.path)

        delay_ms = int(self.headers.get("x-delay", 0) or 0)
        if delay_ms > 0:
            time.sleep(delay_ms / 1000.0)

        content_length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(content_length) if content_length > 0 else b""

        response = {
            "method":  self.command,
            "path":    parsed.path,
            "query":   parsed.query if parsed.query else None,
            "headers": dict(self.headers),
        }
        if body:
            response["payload_hash"] = "sha256:" + hashlib.sha256(body).hexdigest()

        data = json.dumps(response, indent=2).encode()

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):     self.handle_request()
    def do_POST(self):    self.handle_request()
    def do_PUT(self):     self.handle_request()
    def do_PATCH(self):   self.handle_request()
    def do_DELETE(self):  self.handle_request()
    def do_HEAD(self):    self.handle_request()
    def do_OPTIONS(self): self.handle_request()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), EchoHandler)
    sys.stderr.write(f"echo server listening on :{PORT}\n")
    server.serve_forever()
