import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

export function createAdbRunner(adb) {
	return (...args) => {
		const command = args.join(' ');
		const forbidden = [
			new RegExp(['pm', 'grant'].join('\\s+'), 'i'),
			new RegExp(['app', 'ops'].join('\\s*'), 'i')
		];
		assert(forbidden.every((pattern) => !pattern.test(command)), 'Permission shell grants are forbidden.');
		return execFileSync(adb, args, {
			encoding: 'utf8',
			stdio: ['ignore', 'pipe', 'pipe'],
			timeout: 30_000,
			killSignal: 'SIGKILL'
		}).trim();
	};
}

export async function waitFor(check, description, timeoutMs = 30_000) {
	const deadline = Date.now() + timeoutMs;
	let lastError;
	while (Date.now() < deadline) {
		try {
			const result = await check();
			if (result) return result;
		} catch (error) {
			lastError = error;
		}
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
	}
	throw new Error(`Timed out waiting for ${description}`, { cause: lastError });
}
