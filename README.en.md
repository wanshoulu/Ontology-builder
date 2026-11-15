<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

<div align="center">

# 🧩 Ontology Builder

**AI-native ontology modeling — extraction, editing, reasoning, validation, instantiation, and graph exploration in one workbench**

[![CI](https://github.com/wanshoulu/Ontology-builder/actions/workflows/ci.yml/badge.svg)](https://github.com/wanshoulu/Ontology-builder/actions/workflows/ci.yml)
[![Frontend](https://img.shields.io/badge/frontend-React%2018%20%2B%20AntD%20%2B%20G6%20v5-61dafb)](web/)
[![Server](https://img.shields.io/badge/server-Spring%20Boot%203%20%2B%20Jena%20%2B%20Pellet-009688)](server/)
[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)

</div>

---

### What is this

**Ontology Builder** is an AI-native ontology modeling workbench: feed documents or table schemas to an **AI extraction wizard** that drafts a W3C OWL2 model, refine it in a **Protégé-style editor** (class hierarchy tree, property editor, TTL source viewer), enforce standards with **SHACL constraints and SKOS vocabularies**, encode logic as **SWRL rules** (visual composer, custom builtins like `SwrlAbs` / `SwrlStrContains` / `MultiArgSum`), run **consistency reasoning** (Pellet/Openllet + a custom consistency checker), instantiate data via **RML mapping**, and explore instances as an interactive graph (**SPARQL + G6 v5 / vis-network**).

Versioned end to end: every model change is tracked with **version lists and diffs**, and ontologies import/export as **OWL/TTL**.

> Full Chinese documentation: [README.md](README.md).

## ✨ Highlights

- **AI extraction wizard** — Markdown/document → OWL2 draft (classes, properties, rules, individuals)
- **Protégé-style editing** — class hierarchy, properties, annotations, TTL round-trip
- **Standards built-in** — SHACL shapes, SKOS vocabularies, SWRL logic rules with a visual composer
- **Real reasoning** — Pellet/Openllet + custom SWRL builtins + ontology consistency checker
- **Versioning** — version list with structural diff between any two versions
- **Instantiation pipeline** — datasource → RML mapping → RDF instances → SPARQL browsing
- **Graph exploration** — G6 v5 interactive visualization of the class/link-type graph

## 🚀 Quick Start

```bash
# Server (Java 17 + Maven + PostgreSQL + Fuseki/TDB2)
cd server && ./mvnw spring-boot:run     # :8080, API under /api/v1

# Web (React 18 + Vite)
cd web && npm install && npm run dev    # :5173, proxies /api → :8080
```

Open `http://localhost:5173/ontology/modeling` and start modeling.

## 📚 Docs

| Doc | Content |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Design decisions & trade-offs, known limitations |
| [README.md](README.md) | Full Chinese documentation |

## 🙏 Credits & License

Built on [Apache Jena](https://jena.apache.org/), [Openllet](https://github.com/Galigator/openllet), [HermiT](http://www.hermit-reasoner.com/), and [AntV G6](https://g6.antv.antgroup.com/). This repo: Apache-2.0.
