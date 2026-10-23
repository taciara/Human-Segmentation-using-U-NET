#!/usr/bin/env python3
"""
🚀 Servidor simples para testar V2 localmente

Uso:
    python serve.py

Depois acesse:
    http://localhost:8000/
"""

import http.server
import socketserver
import os
from pathlib import Path

PORT = 8000
DIRECTORY = Path(__file__).parent

class MyHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DIRECTORY), **kwargs)

    def end_headers(self):
        # CORS headers
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        super().end_headers()

    def log_message(self, format, *args):
        # Log customizado
        print(f"[{self.client_address[0]}] {format % args}")

if __name__ == '__main__':
    os.chdir(DIRECTORY)

    with socketserver.TCPServer(("", PORT), MyHTTPRequestHandler) as httpd:
        print(f"""
╔════════════════════════════════════════╗
║   🎃 Photobooth V2 - Servidor Local    ║
╚════════════════════════════════════════╝

📍 URL: http://localhost:{PORT}
📁 Pasta: {DIRECTORY}

Press CTRL+C to stop
""")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n✋ Servidor parado.")
