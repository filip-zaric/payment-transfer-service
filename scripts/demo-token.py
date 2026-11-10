#!/usr/bin/env python3
"""Issue a short-lived local development token; never install this as a service endpoint."""
import base64
import hashlib
import hmac
import json
import os
import sys
import time


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=")


secret = os.environ.get("JWT_SECRET", "").encode()
if len(secret) < 32:
    sys.exit("Set JWT_SECRET to at least 32 bytes before generating a demo token.")
subject = sys.argv[1] if len(sys.argv) > 1 else "alice"
if not subject.strip() or len(subject) > 128:
    sys.exit("Subject must contain 1–128 characters.")
now = int(time.time())
header = encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
payload = encode(json.dumps({
    "sub": subject,
    "iss": os.environ.get("JWT_ISSUER", "payment-issuer"),
    "aud": os.environ.get("JWT_AUDIENCE", "payment-transfer"),
    "iat": now,
    "exp": now + 900,
}).encode())
message = header + b"." + payload
signature = encode(hmac.new(secret, message, hashlib.sha256).digest())
print((message + b"." + signature).decode())
