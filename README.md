# 🍏 Isaac Gmail ✉️

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-gmail/main/isaac-gmail.png" alt="isaac-gmail" style="margin-right: 20px; margin-bottom: 10px;">

Gmail comm for [Isaac](https://github.com/slagyr/isaac) — watch on INBOX, history
walk, thread-as-session routing; replies on thread. Built on
[isaac-google](https://github.com/slagyr/isaac-google).

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes the
`:isaac.comm.gmail` comm. Part of the Google Workspace comms epic (isaac-bv1l).

<br>

[![Gmail](https://github.com/slagyr/isaac-gmail/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-gmail/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Module (`isaac.comm.gmail.module/create-module`), manifest id `:isaac.comm.gmail`.
- INBOX watch, history walk, thread-as-session routing, replies on thread.
- Further work is planned in the beans under isaac-bv1l.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-google/
  isaac-gmail/   # this repo
```

```sh
bb hooks:install   # once, on a fresh checkout
bb spec
bb features
bb ci
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-gmail {:local/root "../isaac-gmail"}
;; or {:git/url "https://github.com/slagyr/isaac-gmail.git" :git/sha "..."}
```
