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

export function shellSingleQuote(value) {
	return `'${value.replaceAll("'", "'\\''")}'`;
}

export function seedRunAsFile(adbArgs, packageName, relativePath, content) {
	const encoded = Buffer.from(content).toString('base64');
	const parent = relativePath.split('/').slice(0, -1).join('/');
	adbArgs(
		'exec-out', 'run-as', packageName, 'sh', '-c',
		`mkdir -p ${shellSingleQuote(parent)}; echo ${shellSingleQuote(encoded)} | base64 -d > ${shellSingleQuote(relativePath)}`
	);
}

function escapeRegExp(value) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function nodeCenter(node, description) {
	const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
	assert(bounds, `${description} UI node has no bounds.`);
	return [
		Math.floor((Number(bounds[1]) + Number(bounds[3])) / 2),
		Math.floor((Number(bounds[2]) + Number(bounds[4])) / 2)
	];
}

export async function tapVisibleAndroidAction(androidUi, label, attempts = 8) {
	const pattern = new RegExp(`<node[^>]*text="${escapeRegExp(label)}"[^>]*enabled="true"[^>]*>`);
	for (let attempt = 0; attempt < attempts; attempt += 1) {
		const hierarchy = await androidUi.hierarchy();
		const node = hierarchy.match(pattern)?.[0];
		if (node) {
			const [x, y] = nodeCenter(node, label);
			androidUi.run('shell', 'input', 'tap', String(x), String(y));
			return;
		}
		androidUi.run('shell', 'input', 'swipe', '540', '1900', '540', '650', '250');
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
	}
	assert.fail(`${label} was not visible and enabled in the Android UI.`);
}

export async function replaceVisibleAndroidTextField(androidUi, value, attempts = 8) {
	for (let attempt = 0; attempt < attempts; attempt += 1) {
		const hierarchy = await androidUi.hierarchy();
		const node = hierarchy.match(/<node[^>]*class="android\.widget\.EditText"[^>]*enabled="true"[^>]*>/)?.[0];
		if (node) {
			const [x, y] = nodeCenter(node, 'editable text field');
			androidUi.run('shell', 'input', 'tap', String(x), String(y));
			androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END');
			for (let index = 0; index < 8; index += 1) androidUi.run('shell', 'input', 'keyevent', 'KEYCODE_DEL');
			if (value) androidUi.run('shell', 'input', 'text', value);
			return;
		}
		androidUi.run('shell', 'input', 'swipe', '540', '1900', '540', '650', '250');
		await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
	}
	assert.fail('No enabled Android text field became visible.');
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
