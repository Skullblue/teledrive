// Builds the single-file web app: web/index.src.html + engine bundle -> web/index.html
import fs from 'node:fs';
const bundle = fs.readFileSync('engine/dist/engine.bundle.js', 'utf8').replace(/<\/script/gi, '<\\/script');
const src = fs.readFileSync('web/index.src.html', 'utf8');
fs.writeFileSync('web/index.html', src.replace('<!--ENGINE-->', () => bundle));
console.log('web/index.html', (fs.statSync('web/index.html').size / 1024).toFixed(0) + ' KB');
