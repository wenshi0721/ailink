#!/usr/bin/env node
// 模块边界校验：检查 Kotlin 源码的 import 与 build.gradle.kts 的依赖声明是否符合单向依赖约束。
// 用法：node tools/dev/check-module-deps.mjs        （通过返回 0，违规返回 1）
//       node tools/dev/check-module-deps.mjs -v     （同时打印每个模块的统计）

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { resolve, dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const ANDROID = join(ROOT, 'android');
const VERBOSE = process.argv.includes('-v');

// 依赖方向（单向，禁止回边）。值为该模块「允许 import 的模块」集合，
// 已把 api 传递暴露的也算进去（例如 :ui 只声明了 :assembly，但能用到 core/data/engine）。
const ALLOWED = {
  app: ['core', 'data', 'engine', 'assembly', 'ui'],
  ui: ['assembly', 'core', 'data', 'engine'],
  assembly: ['core', 'data', 'engine'],
  engine: ['core', 'data'],
  data: ['core'],
  core: [],
};

// 直接声明的 Gradle 依赖（与 ALLOWED 不同，这里只看声明，api 传递由 ALLOWED 承担）
const DECLARED = {
  app: ['ui', 'assembly'],
  ui: ['assembly'],
  assembly: ['core', 'data', 'engine'],
  engine: ['core', 'data'],
  data: ['core'],
  core: [],
};

const PKG = (m) => `com.jiuguan.ailink.${m}`;

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.kt')) out.push(p);
  }
  return out;
}

const errors = [];
const stats = [];

for (const mod of Object.keys(ALLOWED)) {
  const srcDir = join(ANDROID, mod, 'src/main/java');
  const files = walk(srcDir);
  const allowedPkgs = ALLOWED[mod].map(PKG);
  let importCount = 0;

  for (const file of files) {
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    const text = readFileSync(file, 'utf8');
    for (const line of text.split('\n')) {
      const m = /^\s*import\s+([\w.]+)/.exec(line);
      if (!m) continue;
      const fq = m[1];
      if (!fq.startsWith('com.jiuguan.ailink.')) continue;
      importCount++;
      if (fq.startsWith(PKG(mod) + '.') || fq === PKG(mod)) continue;
      const target = Object.keys(ALLOWED).find((t) => t !== mod && fq.startsWith(PKG(t) + '.'));
      if (!target) continue; // 其他合法位置（如 app 模块的 AiLinkApp 自身包）
      if (!allowedPkgs.some((p) => fq.startsWith(p + '.') || fq === p)) {
        errors.push(`[越层/反向依赖] ${rel}\n    import ${fq}\n    :${mod} 不允许依赖 :${target}`);
      }
    }
  }

  // 校验 build.gradle.kts 的声明
  const gradleFile = join(ANDROID, mod, 'build.gradle.kts');
  if (existsSync(gradleFile)) {
    const g = readFileSync(gradleFile, 'utf8');
    const declared = [...g.matchAll(/project\(":(\w+)"\)/g)].map((x) => x[1]);
    const expect = DECLARED[mod];
    for (const d of declared) {
      if (!expect.includes(d)) {
        errors.push(`[未声明的依赖] :${mod} 的 build.gradle.kts 依赖了 :${d}，不在允许列表 ${JSON.stringify(expect)}`);
      }
    }
    for (const e of expect) {
      if (!declared.includes(e)) {
        errors.push(`[缺失的依赖] :${mod} 应依赖 :${e}，但 build.gradle.kts 未声明`);
      }
    }
  } else {
    errors.push(`[缺失构建文件] android/${mod}/build.gradle.kts 不存在`);
  }

  stats.push({ mod, files: files.length, imports: importCount });
}

if (VERBOSE) {
  console.log('模块统计：');
  for (const s of stats) {
    console.log(`  :${s.mod.padEnd(9)} ${String(s.files).padStart(3)} 个文件  ${String(s.imports).padStart(4)} 条跨包 import`);
  }
  console.log('');
}

if (errors.length === 0) {
  console.log(`✓ 模块边界校验通过：6 个模块之间无反向 / 越层依赖`);
  process.exit(0);
} else {
  console.log(`✗ 发现 ${errors.length} 处模块边界违规：\n`);
  for (const e of errors) console.log(e + '\n');
  process.exit(1);
}
