'use strict';

const fs = require('node:fs');
const path = require('node:path');
const assets = path.resolve(__dirname, '../app/src/main/assets/webmount');

// Keep the exact production injection order in one asset, shared with Kotlin.
function loadBootstrap(readAsset = name => fs.readFileSync(path.join(assets, name), 'utf8')) {
  const sources = JSON.parse(readAsset('bootstrap-sources.json'));
  return '(function () {\n' + sources.map(readAsset).join('\n;\n') + '\n})();';
}

module.exports = { loadBootstrap };
