const fs = require('fs');

function readJsonNoBom(path) {
  let raw = fs.readFileSync(path, 'utf8');
  if (raw.charCodeAt(0) === 0xFEFF) raw = raw.slice(1);
  return JSON.parse(raw);
}

function writeJsonWithBom(path, obj) {
  const json = JSON.stringify(obj, null, 2) + '\n';
  // Write UTF-8 with BOM to match original encoding
  const bom = Buffer.from([0xEF, 0xBB, 0xBF]);
  const content = Buffer.concat([bom, Buffer.from(json, 'utf8')]);
  fs.writeFileSync(path, content);
}

const zhPath = 'C:/Dev/Projects/Baafoo/web/src/locales/zh-CN.json';
const enPath = 'C:/Dev/Projects/Baafoo/web/src/locales/en.json';

const zh = readJsonNoBom(zhPath);
const en = readJsonNoBom(enPath);

delete zh.rules.templates.hint;
delete en.rules.templates.hint;

writeJsonWithBom(zhPath, zh);
writeJsonWithBom(enPath, en);

console.log('zh templates keys:', Object.keys(zh.rules.templates));
console.log('en templates keys:', Object.keys(en.rules.templates));
