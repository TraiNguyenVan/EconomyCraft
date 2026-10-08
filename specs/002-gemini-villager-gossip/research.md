# Research & Architecture Decisions: Gemini-Powered Villager Gossip

## Decision 1: HTTP Client & Serialization Framework

- **Decision**: Standard Java 21 `java.net.http.HttpClient` combined with Minecraft's bundled Google `Gson`.
- **Rationale**:
  - Java 21's native `HttpClient` supports HTTP/2, asynchronous non-blocking futures (`CompletableFuture`), custom timeouts, and zero external jar dependencies.
  - Minecraft already ships with `com.google.gson.*`.
  - Adding external dependencies (like OkHttp, Apache HttpClient, or the official Google Cloud Java SDK) introduces jar shading complexity, classloader conflicts, and bloats the final mod jar.
- **Alternatives Considered**:
  - *Official Google GenAI Java SDK*: Rejected because it bundles gRPC, Protobuf, and Netty versions that conflict with Minecraft's internal networking stack.
  - *Apache HttpClient / OkHttp*: Rejected because native Java 21 `HttpClient` provides identical functionality without extra dependencies.

---

## Decision 2: Gemini API Integration & Model Selection

- **Decision**: REST API endpoint `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}` using default model `gemini-3.8-flash` (configurable in `config.json`).
- **Rationale**:
  - `gemini-3.8-flash` offers near-instant latency (<1s response), high rate limits, and low cost.
  - Supports strict JSON schema enforcement via `generationConfig.response_schema` and `response_mime_type: "application/json"`.
  - Direct REST payload matches the official Google GenAI v1beta schema.
- **Alternatives Considered**:
  - *Gemini 1.5 Pro*: Rejected as default due to higher latency and token cost; unnecessary for short humorous one-liners.
  - *Local small language model (Ollama / ONNX)*: Rejected due to high host CPU/RAM demands on the game server.

---

## Decision 3: Villager Interaction & Delivery Hook

- **Decision**: Intercept villager trade interactions using Fabric's `UseEntityCallback.EVENT` on the server side.
- **Rationale**:
  - `UseEntityCallback` fires when a `ServerPlayer` interacts with an `Entity` before the merchant inventory/screen is displayed.
  - Allows checking entity type (`instanceof Villager`), reading profession (`villager.getVillagerData().getProfession()`), checking the cooldown, and delivering a private text component directly to the interacting player.
  - Non-blocking: Returns `InteractionResult.PASS` so vanilla trading continues uninterrupted.
- **Alternatives Considered**:
  - *Mixin to `Villager#updateSpecialPrices`*: Too deeply tied to trading math, fires multiple times during restocks.
  - *Action Bar display*: Less readable for multi-clause humorous gossip; private chat message (`player.sendSystemMessage`) is the standard pattern for RPG dialogue.

---

## Decision 4: Concurrency & Cache Strategy

- **Decision**: Dedicated single-thread daemon `ScheduledExecutorService` (`EconomyCraft-Gemini-Worker`) + lock-free `AtomicReference<GossipPool>`.
- **Rationale**:
  - Background digest runs every 20 minutes (configurable).
  - Background thread handles file I/O (`TransactionLogReader`), prompt construction, HTTP networking, and JSON parsing.
  - When new rumors are ready, `AtomicReference<GossipPool>` is swapped in an atomic $O(1)$ pointer update.
  - The Minecraft game tick thread only reads the current `GossipPool` in memory; it never performs network or disk I/O.
- **Alternatives Considered**:
  - *ForkJoinPool.commonPool()*: Can starve or be starved by other Minecraft async tasks. A dedicated daemon thread ensures isolation and clear naming in JVM thread dumps.

---

## Decision 5: Error Resilience & Circuit Breaker

- **Decision**: 3-failure consecutive threshold with a 30-minute quiet cool-off; silent degradation in-game.
- **Rationale**:
  - If the API key is missing, network is down, or quota is exhausted (HTTP 429), villagers simply stay silent.
  - A circuit breaker prevents log spamming and avoid hammering the Google API while the server is in a failed state.
  - Normal gameplay and trading are 100% unaffected.
- **Alternatives Considered**:
  - *Fallback static pre-written lines*: If the operator did not configure Gemini, having villagers repeat the same 5 static lines can feel artificial and confusing. Silent vanilla behavior is cleaner and unobtrusive.

---

## Decision 6: Authentication via Header vs. URL Query Param

- **Decision**: Transmit the API key via the `x-goog-api-key: <key>` HTTP request header, omitting `?key=...` from the URL.
- **Rationale**:
  - As verified in official Google AI for Developers documentation, passing credentials in HTTP headers prevents sensitive API keys from being logged in proxy access logs, web caches, or diagnostic stack traces.
- **Alternatives Considered**:
  - *Query Parameter (`?key=...`)*: Deprecated for security-sensitive environments due to exposure in URL access logs.

---

## Decision 7: Safety Thresholds & Low-Latency Thinking Budget

- **Decision**: Explicitly configure all harm categories (`HARASSMENT`, `HATE_SPEECH`, `SEXUALLY_EXPLICIT`, `DANGEROUS_CONTENT`) to `BLOCK_ONLY_HIGH`, and set `thinkingConfig.thinkingBudget: 0`.
- **Rationale**:
  - Minecraft economy discussions frequently involve terms like "smuggling", "war", "looting", "taxes", and "monopoly" which can trigger aggressive default content moderation filters. Setting thresholds to `BLOCK_ONLY_HIGH` avoids false-positive rejections.
  - Setting `thinkingBudget: 0` instructs `gemini-3.8-flash` to skip reasoning tokens, delivering sub-second response times and minimal token consumption for short one-liner villager gossip.
- **Alternatives Considered**:
  - *Default safety thresholds*: Can cause sporadic null candidates or empty responses on benign economic banter.
  - *Dynamic thinking tokens*: Unnecessary latency and token overhead for simple one-sentence satirical comments.
