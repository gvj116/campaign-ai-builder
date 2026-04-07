# Campaign AI Builder

> **Turn a free-form conversation into a fully validated ad campaign payload — powered by LLM + Spring AI.**

![Java](https://img.shields.io/badge/Java-17-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen?logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-6DB33F?logo=spring)
![OpenAI](https://img.shields.io/badge/OpenAI-GPT--4o-412991?logo=openai)
![Ollama](https://img.shields.io/badge/Ollama-local-black)
![License](https://img.shields.io/badge/license-MIT-blue)

---

## What It Does

Ad campaign creation traditionally means navigating **60+ form fields** across multiple screens — buy types, placement strategies, delivery targets, creative types, tracking URLs, scheduling, targeting — all requiring deep knowledge of the system.

**Campaign AI Builder eliminates the form entirely.**

An operations or sales team member describes the campaign in plain English through a chat interface. The AI extracts every relevant field, a Java-based assembler builds the exact JSON payload the API expects, and the campaign is submitted — after a human review step.

```
User: "Create a Tiger campaign with 4 adsets, promo demand channel, non-guaranteed deal.
       Second adset is image type, iOS only, with a Facebook tracker.
       Remove the 4th adset. Start date Nov 30 2026."

AI Builder: [extracts fields, assembles payload, shows summary]

User: "confirm"

→ Campaign draft created in the ad platform ✓
```

**Built and deployed internally at Hotstar (Disney+ Hotstar) for the AdTech operations team.**

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Chat UI (vanilla HTML/JS)                 │
│   Session start → free-form chat → summary review → confirm      │
└────────────────────────────┬─────────────────────────────────────┘
                             │  POST /api/chat  (per message)
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                   CampaignChatController (REST)                   │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                   CampaignChatService                             │
│                                                                  │
│  State machine: COLLECTING → CONFIRMING → SUBMITTED              │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  LLM  (OpenAI GPT-4o  or  Ollama local)                 │    │
│  │  · Understands natural language                         │    │
│  │  · Extracts field paths + values from user message      │    │
│  │  · Detects intent: done / modify / delete / confirm     │    │
│  │  · Validates dates conversationally (rejects Nov 31)    │    │
│  │  Returns: { "updates": { "fieldPath": value }, ... }    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                             │                                    │
│              Accumulates into session.userValues                 │
│              (flat Map<String, Object>)                          │
└────────────────────────────┬─────────────────────────────────────┘
                             │  on "confirm"
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                   PayloadAssembler                               │
│                                                                  │
│  1. Deep-copy campaign-template.json  (60+ field skeleton)       │
│  2. Expand adSets / ads arrays to fit user-specified indices     │
│  3. Apply user values at exact JSON paths                        │
│  4. Handle adset deletions (adSets[N]: null markers)            │
│  5. Auto-fill names, ownerReferenceId, brandId                  │
│  6. Enforce required API fields (action, creativeRequest.type)  │
│  7. Validate + clamp invalid dates (Nov 31 → Nov 30)            │
└────────────────────────────┬─────────────────────────────────────┘
                             │  POST  /api/v2/campaign/workflow
                             ▼
                     Campaign Draft API
```

---

## Key Design Decisions

### AI does extraction. Java owns the payload.

The LLM never touches the final JSON structure. It only returns a **flat key-value map** of which field paths to update:

```json
{ "updates": { "name": "Tiger", "adSets[1].ads[0].adType": "IMAGE" } }
```

Java builds the full 200-line payload from a template, applies those values, and enforces every API constraint. This means:

- API contract failures are caught deterministically, not hoped-away
- The LLM can hallucinate a path — the assembler handles it gracefully
- Swapping LLM providers (OpenAI → Gemini → Ollama) requires zero Java changes

### Field registry drives everything

`campaign-fields.json` is a declarative registry of every user-facing field:

```json
{ "path": "adSets[0].deliveryDetail.buyType", "label": "Buy Type", "askUser": true,
  "options": [
    { "label": "Reserved",        "value": "RESERVED" },
    { "label": "Non-Guaranteed",  "value": "NON_GUARANTEE" },
    { "label": "SOV",             "value": "SOV" }
  ]
}
```

This single file:
- Generates the LLM's system prompt (field names, current values, valid options)
- Drives the summary display
- Maps human-readable labels to exact API enum values

**Adding a new campaign field = one line in JSON. Zero Java changes.**

### Dual LLM support (local dev + cloud prod)

| Profile | LLM | How to activate |
|---------|-----|-----------------|
| `local` | Ollama (any model, runs on your machine) | `export SPRING_PROFILES_ACTIVE=local` |
| `cloud` | OpenAI GPT-4o | `export SPRING_PROFILES_ACTIVE=cloud` |

Spring AI's unified abstraction makes both profiles share identical application code.

---

## Features

- **Natural language campaign creation** — no form fields, no dropdowns
- **Multi-adset / multi-ad** — "4 adsets, 2nd one image type" just works
- **Dynamic field extraction** — AI maps free text to exact API field paths
- **Human-in-the-loop** — full summary shown before any API call is made
- **Adset deletion** — "remove adset 3" removes it from the payload
- **Tracker support** — impression/click trackers at both adset and ad level
- **Date validation** — conversational rejection of impossible dates + server-side clamp as safety net
- **Session isolation** — concurrent users with zero cross-contamination
- **Enum label mapping** — user says "Pre-roll", AI sends `BUMPER` to the API
- **Auto-fill** — names, ownerReferenceId, required API fields always populated
- **Dual AI backend** — Ollama locally, OpenAI in cloud, same code

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 |
| AI Abstraction | Spring AI 1.0.0 |
| LLM (cloud) | OpenAI GPT-4o |
| LLM (local) | Ollama (qwen3:8b or any model) |
| JSON processing | Jackson (ObjectMapper, JsonNode) |
| HTTP client | Spring RestTemplate |
| Build | Maven |
| Frontend | Vanilla HTML / CSS / JS (no framework) |

---

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- One of:
  - [Ollama](https://ollama.com) installed locally (for `local` profile)
  - OpenAI API key (for `cloud` profile)
- A running campaign workflow API endpoint

### Local Setup (Ollama)

```bash
# 1. Pull a model
ollama pull qwen3:8b

# 2. Clone and build
git clone https://github.com/gvj116/campaign-ai-builder.git
cd campaign-ai-builder
mvn clean package -DskipTests

# 3. Set your campaign API base URL
export CAMPAIGN_API_BASE_URL=https://your-api-host

# 4. Run
java -jar target/campaign-ai-builder-*.jar
# or
mvn spring-boot:run
```

### Cloud Setup (OpenAI)

```bash
export SPRING_PROFILES_ACTIVE=cloud
export OPENAI_API_KEY=sk-...
export CAMPAIGN_API_BASE_URL=https://your-api-host

java -jar target/campaign-ai-builder-*.jar
```

### Chat UI

Open `campaign-chat-ui/index.html` directly in your browser (no server needed).

Configure in the Settings panel:
- **Business Account ID**
- **Ad Account ID**
- **Brand ID**
- **Authorization token** (for the campaign API)
- **Server URL** (default: `http://localhost:8080`)

---

## Project Structure

```
src/main/
├── java/com/hotstar/campaign/
│   ├── CampaignAiApplication.java         Entry point
│   ├── config/
│   │   ├── AiConfig.java                  Spring AI ChatClient setup with memory
│   │   ├── CampaignApiProperties.java     Externalized API config
│   │   └── CorsConfig.java                CORS for local UI dev
│   ├── controller/
│   │   └── CampaignChatController.java    REST endpoints (/api/chat, /api/session)
│   ├── model/
│   │   ├── AiExtraction.java              LLM response DTO { reply, updates, readyToSubmit }
│   │   ├── CampaignSession.java           Per-session state (userValues, state, auth)
│   │   ├── ChatRequest.java               Inbound chat message
│   │   ├── ChatResponse.java              Outbound response to UI
│   │   ├── FieldMeta.java                 Campaign field descriptor
│   │   └── OptionMeta.java                Enum option with label/value mapping
│   └── service/
│       ├── CampaignChatService.java       State machine + LLM orchestration
│       ├── CampaignApiClient.java         HTTP client for campaign workflow API
│       ├── FieldRegistry.java             Loads and serves campaign-fields.json
│       └── PayloadAssembler.java          Template + userValues → final API payload
└── resources/
    ├── application.properties             Shared config (profile selection, API base URL)
    ├── application-local.properties       Ollama config + OpenAI exclusions
    ├── application-cloud.properties       OpenAI config + Ollama exclusions
    ├── campaign-fields.json               Field registry (labels, paths, enum options)
    └── campaign-template.json             Default campaign JSON skeleton
```

---

## Customizing for Your API

The two files that make this campaign-platform-specific:

**`campaign-fields.json`** — declare which fields to collect, their API paths, and enum options:
```json
{ "path": "adSets[0].deliveryDetail.buyType", "label": "Buy Type", "askUser": true,
  "options": [{ "label": "Reserved", "value": "RESERVED" }] }
```

**`campaign-template.json`** — the default JSON skeleton your API expects, with all required fields pre-populated with safe defaults.

Everything else — AI orchestration, session management, payload assembly — is generic and reusable for **any JSON API with a complex form**.

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Send a message, get AI reply + updated state |
| `DELETE` | `/api/session/{sessionId}` | Clear session (used by "New Session" in UI) |

### Chat Request
```json
{
  "sessionId": "uuid",
  "message": "create a tiger campaign with 4 adsets",
  "businessAccountId": "123",
  "adAccountId": "456",
  "brandId": "789"
}
```

### Chat Response
```json
{
  "reply": "Created Tiger campaign with 4 adsets. What settings would you like?",
  "state": "COLLECTING",
  "summary": null
}
```

---

## How the AI Extracts Fields

The system prompt sent to the LLM on every turn contains:

1. **All field paths with current values** — so AI knows what's already set
2. **Valid enum options** with human-readable labels
3. **Rules** — exact path format, deletion syntax, date validation, tracker format

The LLM returns only a flat JSON diff:
```json
{
  "reply": "Done! Set to promo channel with non-guaranteed deal.",
  "updates": {
    "demandChannel": "HOTSTAR_PROMO",
    "dealType": "NON_GUARANTEE"
  },
  "readyToSubmit": false
}
```

Java accumulates these diffs into `session.userValues` across the full conversation. `PayloadAssembler` applies them all at once to the template when the user confirms.

---

## Extending This

| Goal | What to change |
|------|----------------|
| Add a new campaign field | One entry in `campaign-fields.json` |
| Support a new ad platform API | Replace `campaign-template.json` + `CampaignApiClient` |
| Use a different LLM | Add a new Spring profile + `application-{profile}.properties` |
| Persist sessions across restarts | Swap `ConcurrentHashMap` in `CampaignChatService` for Redis |
| Add field validation rules | Add a validator step in `PayloadAssembler.assemble()` |

---

## License

MIT — free to use, adapt, and build on.

---

*Built as an internal tool to replace complex campaign creation UI forms with a natural language interface. Reduced average campaign creation time from ~20 minutes to ~3 minutes.*
