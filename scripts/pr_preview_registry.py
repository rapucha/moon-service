"""Strict GHCR access for Moon Service pull-request preview images."""

import base64
import os
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urljoin, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import inspect_pr_preview_oci as oci


REGISTRY = "ghcr.io"
REPOSITORY = "rapucha/moon-service"
CHANNEL = "preview"
PR_ANNOTATION = "dev.moon-service.preview.pr-number"
RUN_ANNOTATION = "dev.moon-service.preview.run-id"
RUN_NUMBER_ANNOTATION = "dev.moon-service.preview.run-number"
ATTEMPT_ANNOTATION = "dev.moon-service.preview.run-attempt"


def positive(value, label):
    text = str(value)
    oci.require(
        oci.INTEGER_RE.fullmatch(text),
        f"{label} must be a positive canonical integer",
    )
    return int(text)


class SafeRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        redirected = super().redirect_request(
            request,
            fp,
            code,
            message,
            headers,
            new_url,
        )
        if redirected is None:
            return None
        old = urlsplit(request.full_url)
        new = urlsplit(new_url)
        oci.require(new.scheme == "https", "registry redirect must use HTTPS")
        old_origin = (old.scheme, old.hostname, old.port)
        new_origin = (new.scheme, new.hostname, new.port)
        if old_origin != new_origin:
            redirected.remove_header("Authorization")
        return redirected


class Registry:
    def __init__(self, push=False, allowed_tags=()):
        self.opener = build_opener(SafeRedirects())
        scope = f"repository:{REPOSITORY}:pull"
        if push:
            scope += ",push"
        query = urlencode({"service": REGISTRY, "scope": scope})
        headers = {}
        if push:
            actor = os.environ.get("GITHUB_ACTOR", "")
            token = os.environ.get("GITHUB_TOKEN", "")
            oci.require(
                actor and token,
                "GITHUB_ACTOR and GITHUB_TOKEN are required for publication",
            )
            credential = base64.b64encode(f"{actor}:{token}".encode()).decode()
            headers["Authorization"] = "Basic " + credential
        body, _, _ = self.request(
            "GET",
            f"https://{REGISTRY}/token?{query}",
            headers=headers,
            auth=False,
        )
        token_data = oci.json_bytes(body, "registry token", 64 * 1024)
        self.token = token_data.get("token") or token_data.get("access_token")
        oci.require(
            isinstance(self.token, str) and self.token,
            "registry did not issue a bearer token",
        )
        self.allowed_tags = set(allowed_tags)

    def request(
        self,
        method,
        url,
        headers=None,
        data=None,
        auth=True,
        missing=False,
        limit=oci.MAX_JSON,
    ):
        parts = urlsplit(url)
        oci.require(
            parts.scheme == "https"
            and not parts.username
            and not parts.password
            and not parts.fragment,
            "unsafe registry URL",
        )
        request_headers = dict(headers or {})
        if parts.hostname != REGISTRY:
            request_headers.pop("Authorization", None)
        if auth and parts.hostname == REGISTRY and hasattr(self, "token"):
            request_headers["Authorization"] = "Bearer " + self.token
        request = Request(
            url,
            data=data,
            headers=request_headers,
            method=method,
        )
        try:
            response = self.opener.open(request, timeout=60)
        except HTTPError as error:
            if missing and error.code == 404:
                error.close()
                return None
            code = error.code
            error.close()
            oci.fail(f"registry {method} failed with HTTP {code}")
        with response:
            body = response.read(limit + 1)
            oci.require(
                len(body) <= limit,
                "registry response exceeded its size limit",
            )
            return body, response.headers, response.status

    def manifest(self, reference, missing=False):
        accept = f"{oci.OCI_INDEX}, {oci.OCI_MANIFEST}"
        result = self.request(
            "GET",
            self.url("manifests", reference),
            {"Accept": accept},
            missing=missing,
        )
        if result is None:
            return None
        body, headers, _ = result
        media_type = headers.get_content_type()
        digest = oci.sha256_bytes(body)
        oci.require(
            media_type in {oci.OCI_INDEX, oci.OCI_MANIFEST}
            and headers.get("Docker-Content-Digest") == digest,
            "registry returned an invalid manifest",
        )
        return body, media_type, digest

    def blob(self, digest, size, limit):
        body, _, _ = self.request(
            "GET",
            self.url("blobs", digest),
            limit=limit,
        )
        oci.require(
            len(body) == size and oci.sha256_bytes(body) == digest,
            f"registry blob does not match {digest}",
        )
        return body

    def has_blob(self, digest, size):
        result = self.request(
            "HEAD",
            self.url("blobs", digest),
            missing=True,
            limit=0,
        )
        if result is None:
            return False
        _, headers, _ = result
        oci.require(
            headers.get("Docker-Content-Digest") == digest
            and int(headers.get("Content-Length", "-1")) == size,
            f"registry blob metadata does not match {digest}",
        )
        return True

    def upload_blob(self, digest, path):
        size = path.stat().st_size
        if self.has_blob(digest, size):
            return
        _, headers, _ = self.request(
            "POST",
            f"https://{REGISTRY}/v2/{REPOSITORY}/blobs/uploads/",
            limit=64 * 1024,
        )
        location = urljoin(f"https://{REGISTRY}/", headers.get("Location", ""))
        parts = urlsplit(location)
        query = parts.query
        if query:
            query += "&"
        query += urlencode({"digest": digest})
        target = urlunsplit(
            (parts.scheme, parts.netloc, parts.path, query, "")
        )
        headers = {
            "Content-Type": "application/octet-stream",
            "Content-Length": str(size),
        }
        with path.open("rb") as content:
            self.request(
                "PUT",
                target,
                headers,
                content,
                limit=64 * 1024,
            )
        oci.require(
            self.has_blob(digest, size),
            f"registry did not retain blob {digest}",
        )

    def put_manifest(self, reference, media_type, body):
        oci.require(
            oci.DIGEST_RE.fullmatch(reference) or reference in self.allowed_tags,
            f"refusing to write unreserved tag: {reference}",
        )
        headers = {
            "Content-Type": media_type,
            "Content-Length": str(len(body)),
        }
        _, response_headers, _ = self.request(
            "PUT",
            self.url("manifests", reference),
            headers,
            body,
        )
        oci.require(
            response_headers.get("Docker-Content-Digest")
            == oci.sha256_bytes(body),
            "registry reported a different published digest",
        )

    @staticmethod
    def url(kind, reference):
        quoted_reference = quote(reference, safe=":")
        return f"https://{REGISTRY}/v2/{REPOSITORY}/{kind}/{quoted_reference}"


def remote_index(registry, reference, revision=None, pr_number=None, missing=False):
    result = registry.manifest(reference, missing=missing)
    if result is None:
        return None
    raw, media_type, digest = result
    oci.require(
        media_type == oci.OCI_INDEX,
        f"{reference} must resolve to an OCI index",
    )
    index = oci.json_bytes(raw, "trusted preview index")
    expected_fields = {"schemaVersion", "mediaType", "manifests", "annotations"}
    oci.require(
        set(index) == expected_fields
        and index.get("schemaVersion") == 2
        and index.get("mediaType") == oci.OCI_INDEX,
        "trusted preview index has unexpected fields",
    )
    annotations = oci.annotations(index["annotations"], "preview index")
    expected_annotations = {
        "org.opencontainers.image.source",
        "org.opencontainers.image.revision",
        PR_ANNOTATION,
        RUN_ANNOTATION,
        RUN_NUMBER_ANNOTATION,
        ATTEMPT_ANNOTATION,
    }
    oci.require(
        set(annotations) == expected_annotations,
        "trusted preview index annotations are incomplete",
    )
    actual_revision = annotations["org.opencontainers.image.revision"]
    actual_pr = positive(annotations[PR_ANNOTATION], "preview PR number")
    oci.require(
        annotations["org.opencontainers.image.source"] == oci.SOURCE
        and oci.SHA_RE.fullmatch(actual_revision),
        "trusted preview index source or revision is invalid",
    )
    identity_matches = (
        (revision is None or actual_revision == revision)
        and (pr_number is None or actual_pr == pr_number)
    )
    oci.require(
        identity_matches,
        "immutable preview identity does not match the request",
    )
    manifests = index.get("manifests")
    oci.require(
        isinstance(manifests, list) and len(manifests) == 1,
        "trusted preview index must contain one image",
    )
    child_descriptor = oci.descriptor(
        manifests[0],
        "preview child",
        oci.OCI_MANIFEST,
        platform=True,
    )
    child_result = registry.manifest(child_descriptor["digest"])
    oci.require(
        child_result[1] == oci.OCI_MANIFEST
        and len(child_result[0]) == child_descriptor["size"]
        and child_result[2] == child_descriptor["digest"],
        "trusted preview child does not match its descriptor",
    )
    child = oci.validate_child(
        child_result[0],
        registry.blob,
        oci.SOURCE,
        actual_revision,
    )
    return {
        "raw": raw,
        "digest": digest,
        "revision": actual_revision,
        "pr_number": actual_pr,
        "run_id": positive(annotations[RUN_ANNOTATION], "producer run ID"),
        "run_number": positive(
            annotations[RUN_NUMBER_ANNOTATION],
            "producer run number",
        ),
        "run_attempt": positive(
            annotations[ATTEMPT_ANNOTATION],
            "producer run attempt",
        ),
        "child_digest": child_descriptor["digest"],
        "child_size": child_descriptor["size"],
        "config_digest": child["config_digest"],
    }
