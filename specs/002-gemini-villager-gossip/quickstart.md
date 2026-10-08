# Quickstart & Verification Guide: Gemini Villager Gossip

## Prerequisites

1. Access to the EconomyCraft source repository.
2. Valid Gemini API key (set in `config.json` under `gemini_gossip.api_key` or in server environment as `GEMINI_API_KEY`).
3. Running test server or dev environment with Fabric Loom.

---

## 1. Unit & Mock Testing (No Live Key Required)

Run the test suite verifying JSON serialization, anonymizer logic, prompt building, and fallback handling:

```sh
cd <EconomyCraft repository>
./gradlew :common:test --tests "com.reazip.economycraft.gossip.*"
```

Expected Outcome:

- `TransactionAnonymizerTest`: Asserts player names are correctly substituted with archetypes.
- `GeminiResponseParsingTest`: Asserts valid and malformed JSON payloads from Gemini parse cleanly without exceptions.
- `CooldownTrackerTest`: Asserts cooldown expiry and pruning logic.
- `CircuitBreakerTest`: Asserts breaker trips after 3 failures and resets after cool-off.

---

## 2. Integration Verification on Dedicated Server (`find`)

Follow the deployment procedure documented by the server operator. Keep host-specific deployment commands and details in that operator documentation.

### Step 2.1: Build the mod jar

```sh
cd <EconomyCraft repository>
./gradlew :fabric:build -x test
```

### Step 2.2: Deploy to the stopped server

Use the server operator's deployment procedure. Do not deploy a jar to a running server.

### Step 2.3: Verify Boot Log Signals

Check the server logs:

```sh
<server log command> | grep -E "EconomyCraft|Gemini"
```

Expected log signals:

- `[EconomyCraft] Gemini Villager Gossip initialized (model: gemini-3.8-flash, interval: 20m)`
- Zero `ERROR`, zero `ZipException`, clean `Done (...)s!` boot line.

---

## 3. In-Game Functional Verification

1. **Trade Open Rumor**:
   - Log into the game.
   - Right-click an active villager (e.g. Farmer or Weaponsmith).
   - Expected: You receive a private, colored chat greeting from the villager containing a humorous economic rumor, and the trading GUI opens normally.
2. **Cooldown Verification**:
   - Close the trading GUI and immediately right-click the same villager.
   - Expected: The trade GUI opens immediately, but no rumor message is sent.
3. **Privacy Verification**:
   - Confirm that the rumor mentions archetypes (e.g. "a wealthy tycoon", "a shadowy rebel") and never literal player names.
4. **Resilience Verification (Key Unset / Offline)**:
   - Clear the API key and restart.
   - Right-click the villager.
   - Expected: Villager opens trade GUI normally in complete silence. Zero error messages in player chat or server console.
