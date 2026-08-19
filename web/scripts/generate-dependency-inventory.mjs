import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const webDirectory = path.resolve(scriptDirectory, '..');
const lockPath = path.join(webDirectory, 'package-lock.json');
const outputPath = path.resolve(webDirectory, '../third_party/web-dependencies.json');
const lock = JSON.parse(await readFile(lockPath, 'utf8'));

const packages = Object.entries(lock.packages)
	.filter(([packagePath]) => packagePath.includes('node_modules/'))
	.map(([packagePath, metadata]) => ({
		path: packagePath,
		name: packagePath.slice(packagePath.lastIndexOf('node_modules/') + 'node_modules/'.length),
		version: metadata.version,
		license: metadata.license,
		developmentOnly: metadata.dev === true,
		optional: metadata.optional === true,
		resolved: metadata.resolved
	}))
	.sort((left, right) => left.path.localeCompare(right.path));

if (packages.some((entry) => !entry.version || !entry.license)) {
	throw new Error('Every resolved npm package must declare a version and license.');
}

const output = `${JSON.stringify({
	schemaVersion: 1,
	source: 'web/package-lock.json',
	lockfileVersion: lock.lockfileVersion,
	packages
}, null, 2)}\n`;

if (process.argv.includes('--check')) {
	const existing = await readFile(outputPath, 'utf8').catch(() => '');
	if (existing !== output) {
		console.error('third_party/web-dependencies.json is stale; run npm run licenses:update.');
		process.exitCode = 1;
	}
} else {
	await writeFile(outputPath, output);
	console.log(`Wrote ${packages.length} resolved packages to ${outputPath}`);
}
