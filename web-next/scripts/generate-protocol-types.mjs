import { readFile, readdir, mkdir, unlink, writeFile } from "node:fs/promises"
import { createHash } from "node:crypto"
import { fileURLToPath } from "node:url"
import path from "node:path"

import { compileFromFile, compile } from "json-schema-to-typescript"

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const webRoot = path.resolve(scriptDir, "..")
const repositoryRoot = path.resolve(webRoot, "..")
const checkOnly = process.argv.includes("--check")
const contracts = [
  { contractDir: path.join(repositoryRoot, "contracts", "protocol", "1.1"),
    generatedDir: path.join(webRoot, "src", "generated", "protocol", "v1_1") },
  { contractDir: path.join(repositoryRoot, "contracts", "runtime-workflows", "1.2"),
    generatedDir: path.join(webRoot, "src", "generated", "runtime-workflows", "v1_2") },
  { contractDir: path.join(repositoryRoot, "contracts", "remote-desktop", "1.0"),
    generatedDir: path.join(webRoot, "src", "generated", "remote-desktop", "v1_0"),
    includeIngress: true },
]

for (const { contractDir, generatedDir, includeIngress = false } of contracts) {
  const manifest = JSON.parse(await readFile(path.join(contractDir, "manifest.json"), "utf8"))
  const artifacts = manifest.artifacts.filter(
    (artifact) => includeIngress || artifact.direction !== "ingress",
  )
  const contractVersion = manifest.contractVersion ?? manifest.protocolVersion
  const expected = new Map()

  for (const artifact of artifacts) {
    const schemaBytes = await readFile(path.join(contractDir, artifact.path))
    if (schemaHash(schemaBytes) !== artifact.sha256) {
      throw new Error(`${contractDir}/${artifact.path} does not match the manifest checksum`)
    }
    const output = contractDir.includes("remote-desktop")
      ? await compileBundledSchema(contractDir, artifact, {
          bannerComment: [
            "/* eslint-disable */",
            "/**",
            ` * Generated from contract ${contractVersion} artifact ${artifact.slug}.`,
            " * Do not edit by hand; run `yarn protocol:generate`.",
            " */",
          ].join("\n"),
          enableConstEnums: false,
          style: {
            bracketSpacing: true,
            printWidth: 120,
            semi: false,
            singleQuote: false,
            trailingComma: "all",
          },
          unknownAny: true,
        })
      : await compileFromFile(path.join(contractDir, artifact.path), {
          bannerComment: [
            "/* eslint-disable */",
            "/**",
            ` * Generated from contract ${contractVersion} artifact ${artifact.slug}.`,
            " * Do not edit by hand; run `yarn protocol:generate`.",
            " */",
          ].join("\n"),
          cwd: contractDir,
          enableConstEnums: false,
          style: {
            bracketSpacing: true,
            printWidth: 120,
            semi: false,
            singleQuote: false,
            trailingComma: "all",
          },
          unknownAny: true,
        })
    expected.set(`${artifact.slug}.ts`, output)
  }

  await mkdir(generatedDir, { recursive: true })
  const currentFiles = (await readdir(generatedDir)).filter((file) => file.endsWith(".ts"))

  if (checkOnly) {
    const problems = []
    for (const [file, output] of expected) {
      try {
        const current = await readFile(path.join(generatedDir, file), "utf8")
        if (current !== output) problems.push(`${file} is stale`)
      } catch {
        problems.push(`${file} is missing`)
      }
    }
    for (const file of currentFiles) {
      if (!expected.has(file)) problems.push(`${file} is not in the protocol manifest`)
    }
    if (problems.length > 0) {
      console.error(problems.join("\n"))
      process.exitCode = 1
    }
  } else {
    for (const [file, output] of expected) {
      await writeFile(path.join(generatedDir, file), output)
    }
    for (const file of currentFiles) {
      if (!expected.has(file)) await unlink(path.join(generatedDir, file))
    }
  }
}

// The remote-desktop contract refers to other schemas by URN ($id). The
// generator works on files, so inline those cross-schema references first;
// local #/$defs references are left for json-schema-to-typescript.
async function compileBundledSchema(contractDir, artifact, options) {
  const schemaDir = path.join(contractDir, "schemas")
  const byId = new Map()
  for (const file of await readdir(schemaDir)) {
    if (!file.endsWith(".schema.json")) continue
    const schema = JSON.parse(await readFile(path.join(schemaDir, file), "utf8"))
    byId.set(schema.$id, schema)
  }
  const root = JSON.parse(await readFile(path.join(contractDir, artifact.path), "utf8"))
  const resolvePointer = (document, ref) =>
    ref
      .replace(/^#\//, "")
      .split("/")
      .filter(Boolean)
      .reduce((node, token) => node?.[decodeURIComponent(token)], document)
  const inline = (node, document) => {
    if (Array.isArray(node)) return node.map((item) => inline(item, document))
    if (!node || typeof node !== "object") return node
    if (typeof node.$ref === "string") {
      const siblings = Object.fromEntries(Object.entries(node).filter(([key]) => key !== "$ref"))
      if (node.$ref.startsWith("#")) {
        const target = resolvePointer(document, node.$ref)
        if (!target) throw new Error(`unresolved $ref ${node.$ref} in ${artifact.path}`)
        return inline({ ...target, ...siblings }, document)
      }
      const target = byId.get(node.$ref)
      if (!target) throw new Error(`unresolved $ref ${node.$ref} in ${artifact.path}`)
      return inline({ ...target, ...siblings }, target)
    }
    return Object.fromEntries(
      Object.entries(node).map(([key, value]) => [key, inline(value, document)]),
    )
  }
  return compile(inline(root, root), artifact.model, options)
}

function schemaHash(bytes) {
  // Manifests are committed with LF bytes; a Windows checkout may have CRLF.
  const normalized = bytes.toString("utf8").replace(/\r\n/g, "\n")
  return createHash('sha256').update(normalized, 'utf8').digest('hex')
}
