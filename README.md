# PMD Failure Bot

An AI-powered Slack bot that transforms how the Pod Migration and Decommission Team analyzes infrastructure failures through natural language queries and automated log analysis.

## Overview

The PMD Failure Bot is a Retrieval-Augmented Generation (RAG) application that combines Large Language Models with structured data retrieval to provide accurate, context-aware answers about Caffeine step failures. Built as a Slack integration, it eliminates the need for manual log analysis and enables engineers to query failure data using plain English.

## Purpose

The Pod Migration and Decommission Team manages critical infrastructure operations, moving Salesforce customers across data centers using Caffeine's automated deployment steps. When these complex operations fail, engineers previously had to:

- Manually download and decompress log attachments from GUS
- Sift through thousands of lines of technical output
- Rely on specialized expertise to interpret error patterns
- Spend hours on analysis that could delay migrations

The PMD Failure Bot addresses these challenges by providing three core capabilities:

1. **Import Logs** - Automated retrieval and storage of failure logs from GUS
2. **Discover Trends** - Statistical analysis and pattern recognition across failures
3. **Explain Failures** - Detailed root cause analysis with quoted error messages

## Architecture

### Technology Stack
- **Backend**: Spring Boot with Java 17+, Maven build system
- **Database**: PostgreSQL
- **AI Integration**: Salesforce LLM Gateway with structured JSON outputs and function calling
- **Messaging**: Slack Bolt SDK with Socket Mode for real-time events
- **Data Compression**: GZIP compression for log content storage
- **External APIs**: Salesforce Enterprise API with OAuth2 authentication

### Core Service Layer Architecture

#### 1. Natural Language Processing Service (`NaturalLanguageProcessingService`)
**Purpose**: Extracts structured parameters from natural language queries using LLM Gateway structured outputs.

**Key Methods**:
- `extractParameters(String query, String context)`: Returns `ParameterExtractionResult`
- `buildParameterExtractionSchema()`: Defines JSON schema for structured LLM responses

**Schema Output**:
```json
{
  "record_id": "string|null",
  "work_id": "string|null", 
  "case_number": "integer|null",
  "step_name": "string|null",
  "attachment_id": "string|null",
  "datacenter": "string|null",
  "report_date": "YYYY-MM-DD|null",
  "query": "string",
  "intent": "import|metrics|analysis",
  "confidence": "0.0-1.0",
  "is_relevant": "boolean",
  "irrelevant_reason": "string|null"
}
```

#### 2. Database Query Service (`DatabaseQueryService`)
**Purpose**: Converts natural language to SQL using LLM function calling with two specialized tools.

**Function Tools**:
- `generate_metrics_query`: For COUNT, GROUP BY, and aggregation queries
- `generate_analysis_query`: For detailed record retrieval with content analysis

**Key Methods**:
- `processQuery(QueryRequest request)`: Routes to metrics or analysis based on intent
- `executeMetricsQuery()`: Handles statistical queries with formatted results
- `executeAnalysisQuery()`: Handles detailed failure analysis with content extraction

#### 3. Slack Integration Service (`SlackService`)
**Purpose**: Manages Slack bot interactions with Socket Mode and progress tracking.

**Key Features**:
- Real-time emoji reactions for progress feedback (👀 → ✅/❌)
- Conversation context management and deduplication
- Thread-aware responses for organized conversations
- Confidence threshold handling (warns users below 0.7 confidence)

**Message Flow**:
1. `@SlackMessageEvent` → `handleMessageEvent()`
2. Context extraction and deduplication check
3. Parameter extraction via `NaturalLanguageProcessingService`
4. Intent-based routing (import vs. query)
5. Response formatting and Slack delivery

#### 4. Log Import Service (`LogImportService`)
**Purpose**: Handles Salesforce API integration for log retrieval and storage.

**Key Methods**:
- `importLogsByCase(Integer caseNumber)`: Imports all attachments for a case
- `importLogsByStep(String stepName)`: Imports logs filtered by step name
- `processAttachment()`: Downloads, decompresses, and stores log content

**Storage Schema** (`pmd_failure_logs` table):
```sql
CREATE TABLE pmd_failure_logs (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(18) NOT NULL,
    work_id VARCHAR(18) NOT NULL,
    case_number INTEGER NOT NULL,
    step_name VARCHAR(255) NOT NULL,
    attachment_id VARCHAR(18) NOT NULL,
    datacenter VARCHAR(50),
    report_date DATE NOT NULL,
    content BYTEA NOT NULL  -- GZIP compressed log content
);
```

### AI Service Architecture

#### LLM Gateway Integration (`LlmGatewayService`)
**Purpose**: Interfaces with Salesforce LLM Gateway for both structured parameter extraction and function calling.

**Configuration**:
- Model: OpenAI model for function calling capabilities
- Temperature: <0.5 for consistent outputs

**Function Calling Schema**:
```json
{
  "type": "function",
  "function": {
    "name": "generate_metrics_query|generate_analysis_query",
    "parameters": {
      "type": "object",
      "properties": {
        "sql_query": {"type": "string"},
        "explanation": {"type": "string"}
      },
      "required": ["sql_query", "explanation"]
    }
  }
}
```

### Data Flow Architecture

The system processes user requests through a multi-stage pipeline with two distinct execution paths based on intent classification:

#### 1. Initial Processing Stage
When a user sends a message mentioning the bot, `SlackService` receives the event via Socket Mode and immediately provides visual feedback by adding an "eyes" emoji reaction (👀) to indicate the message has been seen. The service extracts conversation context from the Slack thread and passes the message to `NaturalLanguageProcessingService`, which sends the query and context to the LLM Gateway using a structured JSON schema to extract parameters including case numbers, step names, dates, datacenters, and most importantly, the user's intent.

#### 2. Intent-Based Routing
Based on the extracted intent, the system routes to one of two specialized processing paths:

**Import Path** (intent: "import"):
- `LogImportService` receives the extracted parameters and authenticates with Salesforce API using OAuth2
- Downloads and decompresses log attachments matching the specified criteria (case number, step name, etc.)
- Stores the compressed log content in PostgreSQL's `pmd_failure_logs` table using GZIP compression
- Provides progress updates via emoji reactions and returns import statistics to the user

**Query Path** (intent: "metrics" or "analysis"):
- `DatabaseQueryService` receives the extracted parameters and determines the appropriate function tool based on intent
- For metrics queries: Uses `generate_metrics_query` function calling to create SQL with COUNT, GROUP BY, and aggregation operations
- For analysis queries: Uses `generate_analysis_query` function calling to create SQL for detailed record retrieval with content decompression
- `SqlValidator` ensures all generated SQL is read-only and properly limited
- Executes the validated SQL against PostgreSQL and formats results appropriately
- Returns formatted data to LLM Gateway for natural language response generation

#### 3. Response Generation and Delivery
For query requests, the raw SQL results are sent back to the LLM Gateway along with the original user question using specialized prompt templates (`nlSummary` for metrics, `nlErrorSummary` for analysis). The LLM generates a natural language response that interprets the data and provides actionable insights. Finally, `SlackService` delivers the response to the user, updates the emoji reaction to indicate completion (✅ for success, ❌ for errors), and maintains conversation context for potential follow-up interactions.

### Configuration Management

#### Key Configuration Classes
- `SlackConfig`: Validates required Slack tokens and initializes Socket Mode
- `DatabaseConfig`: Configures HikariCP connection pool and JPA settings
- `LlmGatewayConfig`: Sets up HTTP client with timeouts and retry logic

## Current Setup

- Spring Boot development server
- Local PostgreSQL database with Docker Compose
- Environment-based configuration
- Comprehensive logging and error handling
- Slack Socket Mode for real-time event handling
- Compressed content storage to minimize database footprint

### Key Features
- **Real-time Progress Feedback**: Emoji reactions show processing status
- **Confidence Indicators**: Alerts users when query interpretation is uncertain
- **Parameter Transparency**: Shows extracted filters for low-confidence queries
- **Conversation Deduplication**: Prevents duplicate processing of the same message
- **Security Validation**: All generated SQL is validated as read-only with proper limits

## Usage Examples

### Log Import
```
@pmd-bot Import logs for case 123456
@pmd-bot Pull logs from SSH_TO_ALL_HOSTS step
```

### Trend Analysis
```
Which datacenter has the most SSH_TO_ALL_HOSTS failures?
```

### Failure Explanation
```
Explain what happened in case 456789
What caused the SSH_TO_ALL_HOSTS failures yesterday?
Analyze issues in datacenter am3
```

## Future Development Roadmap

### 1. Multi-Interaction Conversations
**Objective**: Transform the bot from a single-query tool into a true analytical partner.

**Implementation**:
- Maintain conversation context across multiple queries
- Enable follow-up questions like "Show me more details about those failures"
- Support contextual queries such as "What happened the day before that failure?"
- Implement conversation memory

**Technical Requirements**:
- Conversation state management in database
- Context-aware prompt engineering for LLM queries
- Session handling for Slack thread interactions
- Memory optimization to prevent context window overflow

### 2. Enhanced Integration
**Objective**: Create seamless, automated integration with existing infrastructure systems.

**Implementation**:
- **Real-time Caffeine Integration**: Automatically detect new step failures and ingest logs without manual requests
- **GUS Workflow Integration**: Direct case linking and automatic status updates
- **Proactive Monitoring**: Transform from reactive analysis to continuous failure surveillance
- **Event-driven Architecture**: Webhook-based notifications for immediate failure response

**Technical Requirements**:
- Caffeine integration for failure event streaming
- GUS REST API integration for case management
- Event queue system for reliable processing
- Database schema updates for automated workflows

### 3. Agentic Capabilities
**Objective**: Evolve from analysis to autonomous action and remediation.

**Implementation**:
- **Automatic Ticket Creation**: Generate detailed incident reports with failure analysis
- **Smart Routing**: Notify appropriate parties based on failure type and severity
- **Remediation Workflows**: Trigger automated fixes for known failure patterns
- **Escalation Logic**: Intelligent escalation based on failure impact and resolution time

**Technical Requirements**:
- Integration with GUS for ticketing
- Workflow orchestration framework
- Safety mechanisms and approval processes for automated actions

## Getting Started

### Prerequisites
- Java 17+
- PostgreSQL 12+
- Docker and Docker Compose
- Slack workspace with bot permissions

### Local Development
```bash
# Start database
docker-compose up -d

# Run application
./mvnw spring-boot:run

# Run with test profile
./mvnw spring-boot:run -Dspring.profiles.active=test
```

### Slack App Configuration

#### Prerequisites
- Admin access to your Slack workspace
- Ability to create and configure Slack apps

#### Step 1: Create Slack App
1. Go to [api.slack.com/apps](https://api.slack.com/apps)
2. Click "Create New App" → "From an app manifest"
3. Select your workspace
4. Paste the following app manifest:

```json
{
    "display_information": {
        "name": "PMD Failure Bot",
        "description": "An intelligent Slack assistant that analyzes PMD (Pod Migration and Decommission) failure logs.",
        "background_color": "#004492",
        "long_description": "- Pull failure logs from Salesforce into your analysis database \r\n- Uncover patterns and trends across failures\r\n- Transform noisy log entries into clear, actionable explanations"
    },
    "features": {
        "app_home": {
            "home_tab_enabled": false,
            "messages_tab_enabled": true,
            "messages_tab_read_only_enabled": false
        },
        "bot_user": {
            "display_name": "PMD Failure Bot",
            "always_online": true
        }
    },
    "oauth_config": {
        "scopes": {
            "bot": [
                "app_mentions:read",
                "channels:history", 
                "channels:read",
                "chat:write",
                "im:history",
                "im:read",
                "im:write",
                "reactions:read",
                "reactions:write"
            ]
        }
    },
    "settings": {
        "event_subscriptions": {
            "bot_events": [
                "app_mention",
                "assistant_thread_started",
                "message.im"
            ]
        },
        "interactivity": {
            "is_enabled": true
        },
        "org_deploy_enabled": false,
        "socket_mode_enabled": true,
        "token_rotation_enabled": false
    }
}
```

#### Step 2: Configure Bot Permissions
The app manifest includes all necessary scopes, but verify these bot token scopes are enabled:

- **`app_mentions:read`** - View messages that mention @PMD Failure Bot
- **`channels:history`** - View messages in public channels the bot is added to  
- **`channels:read`** - View basic channel information
- **`chat:write`** - Send messages as @PMD Failure Bot
- **`im:history`** - View direct message content
- **`im:read`** - View basic direct message information
- **`im:write`** - Start direct messages with users
- **`reactions:read`** - View emoji reactions in conversations
- **`reactions:write`** - Add and edit emoji reactions

#### Step 3: Enable Socket Mode
1. Go to **Socket Mode** in your app settings
2. Toggle "Enable Socket Mode" to **On**
3. Under "App Level Tokens", click "Generate Token and Scopes"
4. Name it "PMD Bot Socket Token"
5. Add scope: `connections:write`
6. Click "Generate"
7. **Copy and save the App-Level Token** (starts with `xapp-`)

#### Step 4: Configure Event Subscriptions
Verify these event subscriptions are enabled (should be set by manifest):

- **`app_mention`** - Subscribe to messages mentioning the bot
- **`assistant_thread_started`** - For Agent/Assistant mode compatibility  
- **`message.im`** - Subscribe to direct messages

#### Step 5: Install App to Workspace
1. Go to **Install App** in your app settings
2. Click "Install to Workspace"
3. Review permissions and click "Allow"
4. **Copy and save the Bot User OAuth Token** (starts with `xoxb-`)

#### Step 6: Configure Direct Messages
1. Go to **App Home** in your app settings
2. Under "Show Tabs", ensure:
   - **Messages Tab** is enabled
   - **"Allow users to send Slash commands and messages from the messages tab"** is checked
3. This enables direct message functionality

### Environment Configuration
Set the following environment variables:

```bash
# Slack Configuration
SLACK_BOT_TOKEN=xoxb-your-bot-token-here
SLACK_APP_TOKEN=xapp-your-app-level-token-here

# Salesforce Configuration
SALESFORCE_USERNAME=your-salesforce-username
SALESFORCE_PASSWORD=your-salesforce-password
SALESFORCE_SECURITY_TOKEN=your-security-token

# LLM Gateway Configuration
LLM_GATEWAY_URL=your-llm-gateway-url-here
LLM_GATEWAY_ORG_ID=your-org-id-here
LLM_GATEWAY_PROVIDER=OpenAI
LLM_GATEWAY_MODEL=llmgateway__OpenAIGPT5Mini
LLM_GATEWAY_AUTH_TOKEN=your-auth-token-here
LLM_GATEWAY_TENANT_ID=your-tenant-id-here
LLM_GATEWAY_CLIENT_FEATURE_ID=your-client-feature-id

# Database Configuration
DATABASE_URL=postgresql://username:password@localhost:5432/pmd_failure_bot
```

#### Obtaining Salesforce Credentials
1. **Username/Password**: Your standard Salesforce login credentials
2. **Security Token**: 
   - Log into Salesforce
   - Go to Setup → My Personal Information → Reset My Security Token
   - Click "Reset Security Token"
   - Check your email for the new security token
   - The token gets appended to your password in API calls

#### Obtaining LLM Gateway Credentials

The PMD Failure Bot integrates with Salesforce's internal LLM Gateway for AI capabilities. Follow these steps to obtain access:

**Step 1: Request API Key**
1. Join the Slack channel: `#genai-nonprod-api-key-requests`
2. Request an LLM Gateway API key for internal testing and development
3. Keys are automatically approved for internal use

**Step 2: Verify Your Key**
Test your API key with this curl command:

```bash
curl --location "https://bot-svc-llm.sfproxy.einstein.aws-dev4-uswest2.aws.sfdc.cl/v1.0/generations" \
--header "Content-Type: application/json" \
--header "Authorization: API_KEY $GATEWAY_API_KEY" \
--header 'x-client-feature-id: EinsteinDocsAnswers' \
--header 'x-sfdc-app-context: EinsteinGPT' \
--header 'x-sfdc-core-tenant-id: core/prod1/00DDu0000008cuqMAA' \
--data '{
    "model": "llmgateway__OpenAIGPT35Turbo",
    "prompt": "Invent 3 fun names for donuts"
}'
```

**Step 3: Explore Available Options**
Use the Postman collection to explore available models and providers:

1. Import the [Postman Collection](https://git.soma.salesforce.com/pages/tech-enablement/einstein/docs/gateway/access-methods/send-request-postman/)
2. Use the `/models` endpoint to see available models
3. Use the `/llm-providers` endpoint to see available providers
4. Choose appropriate model and provider based on your needs (function calling capabilities required)

**Step 4: Configure Environment**
Configure all the required LLM Gateway environment variables:

```bash
# LLM Gateway Configuration
LLM_GATEWAY_URL=your-llm-gateway-url-here
LLM_GATEWAY_ORG_ID=your-org-id-here
LLM_GATEWAY_PROVIDER=your-chosen-provider
LLM_GATEWAY_MODEL=your-chosen-model  
LLM_GATEWAY_AUTH_TOKEN=your-auth-token-here
LLM_GATEWAY_TENANT_ID=your-tenant-id-here
LLM_GATEWAY_CLIENT_FEATURE_ID=your-client-feature-id
```

**Environment Variable Descriptions:**
- `LLM_GATEWAY_URL`: The base URL for the LLM Gateway service (obtain from following documentation)
- `LLM_GATEWAY_ORG_ID`: Your Salesforce organization identifier (obtain from following documentation)
- `LLM_GATEWAY_PROVIDER`: AI provider (explore options via `/llm-providers` endpoint)
- `LLM_GATEWAY_MODEL`: Specific model version (explore options via `/models` endpoint)
- `LLM_GATEWAY_AUTH_TOKEN`: Authentication token from the API key request
- `LLM_GATEWAY_TENANT_ID`: Tenant identifier (obtain from following documentation)
- `LLM_GATEWAY_CLIENT_FEATURE_ID`: Feature identifier for usage tracking (obtain from following documentation)

**Documentation:**
- [LLM Gateway Get Started Guide](https://git.soma.salesforce.com/pages/tech-enablement/einstein/docs/gateway/get-started)
- [Authentication Documentation](https://git.soma.salesforce.com/pages/tech-enablement/einstein/docs/gateway/get-started/auth/)
- [Access Methods Documentation](https://git.soma.salesforce.com/pages/tech-enablement/einstein/docs/gateway/access-methods/access-connectapi-locally/)
- [Postman Collection](https://git.soma.salesforce.com/pages/tech-enablement/einstein/docs/gateway/access-methods/send-request-postman/)