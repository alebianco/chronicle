---
id: decision-6
title: Every reliability port lands with tests
date: '2026-07-13'
status: accepted
---

## Context

Test coverage is ~zero; reliability ports are the riskiest changes.

## Decision

Each reliability port ships with tests (fabiogermann Robolectric patterns as reference); fixture-backed where networked ([[cu-16]]).

## Consequences

Slower per-port but the only affordable way to build coverage; enforced by the CI ratchet ([[cu-3]]).
