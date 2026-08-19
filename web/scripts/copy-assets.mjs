import { cp, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const webDirectory = path.resolve(scriptDirectory, '..');
const buildDirectory = path.join(webDirectory, 'build');
const generatedAssets = path.join(buildDirectory, '_app');
const packagedAssets = path.join(buildDirectory, 'app-build');
const androidAssets = path.resolve(webDirectory, '../app/src/main/assets/www');
const indexPath = path.join(buildDirectory, 'index.html');

await rm(packagedAssets, { recursive: true, force: true });
await rename(generatedAssets, packagedAssets);
const index = await readFile(indexPath, 'utf8');
await writeFile(indexPath, index.replaceAll('/_app/', '/app-build/'));
await rm(androidAssets, { recursive: true, force: true });
await mkdir(androidAssets, { recursive: true });
await cp(buildDirectory, androidAssets, { recursive: true });
