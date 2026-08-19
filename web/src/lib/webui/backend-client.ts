/** A failed authenticated backend request with its HTTP status retained for curated UI recovery. */
export class BackendRequestError extends Error {
	constructor(readonly status: number, message: string) {
		super(message);
		this.name = 'BackendRequestError';
	}
}

/** Browser transport used only by the WebUI coordinator. */
export class BrowserWebUiBackendClient {
	async fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
		const response = await fetch(url, options);
		if (!response.ok) {
			const body = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
			throw new BackendRequestError(response.status, body.error || `HTTP ${response.status}`);
		}
		return response.json() as Promise<T>;
	}

	request(url: string, options?: RequestInit): Promise<Response> {
		return fetch(url, options);
	}

	openProgressSocket(): WebSocket {
		const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
		return new WebSocket(`${protocol}://${location.host}/ws/progress`);
	}
}
