# Canonical Paths Stay Out of Request Targets

## Status

Accepted

## Context

Canonical filesystem paths are private authenticated values. A request target can be retained in browser history, proxy logs, diagnostics, and network telemetry more readily than an authenticated request body.

## Decision

Authenticated API responses may provide canonical paths where the WebUI must display them. Any storage operation that accepts a canonical path sends it in a typed JSON request body, never in a URL path or query string. The WebUI never stores canonical paths in browser URLs or history state and does not routinely log them.

## Consequences

The storage children, approve-destination, and remove-destination endpoints use body-only path requests. Callers retain the backend as canonicalization and validation authority; this decision does not introduce aliases or opaque destination identifiers.
