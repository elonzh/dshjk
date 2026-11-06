package com.elonzh.dshjk.protocol

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/** The exact case-sensitive spelling used by the native protocol. */
interface WireName { val wireName: String }

@Serializable(with = ContentTypeSerializer::class)
sealed interface ContentType : WireName {
    @Serializable
    enum class Builtin(override val wireName: String) : ContentType {
        @SerialName("text") TEXT("text"),
        @SerialName("reasoning") REASONING("reasoning"),
        @SerialName("image") IMAGE("image"),
        @SerialName("file") FILE("file"),
        @SerialName("tool-call") TOOL_CALL("tool-call"),
        @SerialName("tool-addition") TOOL_ADDITION("tool-addition"),
        @SerialName("tool-removal") TOOL_REMOVAL("tool-removal");
    }
    data class Extension(override val wireName: String) : ContentType {
        init { require(wireName.isNotEmpty()); require(Builtin.entries.none { it.wireName == wireName }) }
    }
    companion object {
        @JvmStatic fun fromWire(value: String): ContentType = Builtin.entries.firstOrNull { it.wireName == value } ?: Extension(value)
    }
}
object ContentTypeSerializer : WireNameSerializer<ContentType>("ContentType", ContentType::fromWire)

@Serializable(with = FinishReasonSerializer::class)
sealed interface FinishReason : WireName {
    @Serializable
    enum class Builtin(override val wireName: String) : FinishReason {
        @SerialName("completed") COMPLETED("completed"),
        @SerialName("aborted") ABORTED("aborted"),
        @SerialName("blocked") BLOCKED("blocked"),
        @SerialName("error") ERROR("error"),
        @SerialName("max-tokens") MAX_TOKENS("max-tokens"),
        @SerialName("interrupted") INTERRUPTED("interrupted"),
        @SerialName("forked") FORKED("forked");
    }
    data class Extension(override val wireName: String) : FinishReason {
        init { require(wireName.isNotEmpty()); require(Builtin.entries.none { it.wireName == wireName }) }
    }
    companion object {
        @JvmStatic fun fromWire(value: String): FinishReason = Builtin.entries.firstOrNull { it.wireName == value } ?: Extension(value)
    }
}
object FinishReasonSerializer : WireNameSerializer<FinishReason>("FinishReason", FinishReason::fromWire)

@Serializable(with = SubagentStopReasonSerializer::class)
sealed interface SubagentStopReason : WireName {
    @Serializable
    enum class Builtin(override val wireName: String) : SubagentStopReason {
        @SerialName("completed") COMPLETED("completed"),
        @SerialName("aborted") ABORTED("aborted"),
        @SerialName("error") ERROR("error"),
        @SerialName("max-tokens") MAX_TOKENS("max-tokens"),
        @SerialName("refusal") REFUSAL("refusal");
    }
    data class Extension(override val wireName: String) : SubagentStopReason {
        init { require(wireName.isNotEmpty()); require(Builtin.entries.none { it.wireName == wireName }) }
    }
    companion object {
        @JvmStatic fun fromWire(value: String): SubagentStopReason = Builtin.entries.firstOrNull { it.wireName == value } ?: Extension(value)
    }
}
object SubagentStopReasonSerializer : WireNameSerializer<SubagentStopReason>("SubagentStopReason", SubagentStopReason::fromWire)

@Serializable(with = NotificationMethodSerializer::class)
sealed interface NotificationMethod : WireName {
    @Serializable
    enum class Builtin(override val wireName: String) : NotificationMethod {
        @SerialName("session.event") SESSION_EVENT("session.event"),
        @SerialName("session.status") SESSION_STATUS("session.status"),
        @SerialName("session.query.completed") SESSION_QUERY_COMPLETED("session.query.completed"),
        @SerialName("subagent.started") SUBAGENT_STARTED("subagent.started"),
        @SerialName("subagent.finished") SUBAGENT_FINISHED("subagent.finished"),
        @SerialName("user-question.cancelled") USER_QUESTION_CANCELLED("user-question.cancelled");
    }
    data class Extension(override val wireName: String) : NotificationMethod {
        init { require(wireName.isNotEmpty()); require(Builtin.entries.none { it.wireName == wireName }) }
    }
    companion object {
        @JvmStatic fun fromWire(value: String): NotificationMethod = Builtin.entries.firstOrNull { it.wireName == value } ?: Extension(value)
    }
}
object NotificationMethodSerializer : WireNameSerializer<NotificationMethod>("NotificationMethod", NotificationMethod::fromWire)

@Serializable(with = SessionEventTypeSerializer::class)
sealed interface SessionEventType : WireName {
    @Serializable
    enum class Builtin(override val wireName: String) : SessionEventType {
        @SerialName("agent-preset/selected") AGENT_PRESET_SELECTED("agent-preset/selected"),
        @SerialName("agent/inbox/spliced") AGENT_INBOX_SPLICED("agent/inbox/spliced"),
        @SerialName("approval/asked") APPROVAL_ASKED("approval/asked"),
        @SerialName("approval/decided") APPROVAL_DECIDED("approval/decided"),
        @SerialName("approval/policy") APPROVAL_POLICY("approval/policy"),
        @SerialName("assistant/attempt") ASSISTANT_ATTEMPT("assistant/attempt"),
        @SerialName("assistant/message") ASSISTANT_MESSAGE("assistant/message"),
        @SerialName("command/done") COMMAND_DONE("command/done"),
        @SerialName("command/run") COMMAND_RUN("command/run"),
        @SerialName("compaction/end") COMPACTION_END("compaction/end"),
        @SerialName("compaction/prune") COMPACTION_PRUNE("compaction/prune"),
        @SerialName("compaction/start") COMPACTION_START("compaction/start"),
        @SerialName("compaction/summary") COMPACTION_SUMMARY("compaction/summary"),
        @SerialName("deliverables/presented") DELIVERABLES_PRESENTED("deliverables/presented"),
        @SerialName("developer/message") DEVELOPER_MESSAGE("developer/message"),
        @SerialName("feedback/message-delete") FEEDBACK_MESSAGE_DELETE("feedback/message-delete"),
        @SerialName("feedback/message-put") FEEDBACK_MESSAGE_PUT("feedback/message-put"),
        @SerialName("feedback/record") FEEDBACK_RECORD("feedback/record"),
        @SerialName("goal/change") GOAL_CHANGE("goal/change"),
        @SerialName("hook/invoked") HOOK_INVOKED("hook/invoked"),
        @SerialName("hook/result") HOOK_RESULT("hook/result"),
        @SerialName("image/offload") IMAGE_OFFLOAD("image/offload"),
        @SerialName("job/registered") JOB_REGISTERED("job/registered"),
        @SerialName("job/settled") JOB_SETTLED("job/settled"),
        @SerialName("llm/retry") LLM_RETRY("llm/retry"),
        @SerialName("llm/retry-started") LLM_RETRY_STARTED("llm/retry-started"),
        @SerialName("model/selection") MODEL_SELECTION("model/selection"),
        @SerialName("permission/preset") PERMISSION_PRESET("permission/preset"),
        @SerialName("plan/mode") PLAN_MODE("plan/mode"),
        @SerialName("request/context") REQUEST_CONTEXT("request/context"),
        @SerialName("request/header") REQUEST_HEADER("request/header"),
        @SerialName("sandbox/mode") SANDBOX_MODE("sandbox/mode"),
        @SerialName("schedule/change") SCHEDULE_CHANGE("schedule/change"),
        @SerialName("session-log-deepseek/delivery-accepted") SESSION_LOG_DEEPSEEK_DELIVERY_ACCEPTED("session-log-deepseek/delivery-accepted"),
        @SerialName("session/end-seed") SESSION_END_SEED("session/end-seed"),
        @SerialName("session/title") SESSION_TITLE("session/title"),
        @SerialName("session/title-llm-request") SESSION_TITLE_LLM_REQUEST("session/title-llm-request"),
        @SerialName("step/end") STEP_END("step/end"),
        @SerialName("step/start") STEP_START("step/start"),
        @SerialName("subagent/catalog") SUBAGENT_CATALOG("subagent/catalog"),
        @SerialName("subagent/descriptor") SUBAGENT_DESCRIPTOR("subagent/descriptor"),
        @SerialName("subagent/model-selection-policy") SUBAGENT_MODEL_SELECTION_POLICY("subagent/model-selection-policy"),
        @SerialName("system/message") SYSTEM_MESSAGE("system/message"),
        @SerialName("team/member") TEAM_MEMBER("team/member"),
        @SerialName("team/message/delivered") TEAM_MESSAGE_DELIVERED("team/message/delivered"),
        @SerialName("team/message/queued") TEAM_MESSAGE_QUEUED("team/message/queued"),
        @SerialName("team/mode") TEAM_MODE("team/mode"),
        @SerialName("team/task") TEAM_TASK("team/task"),
        @SerialName("todo/write") TODO_WRITE("todo/write"),
        @SerialName("tool-workflow/agent-end") TOOL_WORKFLOW_AGENT_END("tool-workflow/agent-end"),
        @SerialName("tool-workflow/agent-start") TOOL_WORKFLOW_AGENT_START("tool-workflow/agent-start"),
        @SerialName("tool-workflow/run-end") TOOL_WORKFLOW_RUN_END("tool-workflow/run-end"),
        @SerialName("tool-workflow/run-start") TOOL_WORKFLOW_RUN_START("tool-workflow/run-start"),
        @SerialName("tool/call") TOOL_CALL("tool/call"),
        @SerialName("tool/ptc-dispatch") TOOL_PTC_DISPATCH("tool/ptc-dispatch"),
        @SerialName("tool/ptc-dispatch-start") TOOL_PTC_DISPATCH_START("tool/ptc-dispatch-start"),
        @SerialName("tool/result") TOOL_RESULT("tool/result"),
        @SerialName("turn/end") TURN_END("turn/end"),
        @SerialName("turn/start") TURN_START("turn/start"),
        @SerialName("user/message") USER_MESSAGE("user/message"),
        @SerialName("web/deepseek-search-llm-request") WEB_DEEPSEEK_SEARCH_LLM_REQUEST("web/deepseek-search-llm-request"),
        @SerialName("working-directory/change") WORKING_DIRECTORY_CHANGE("working-directory/change"),
        @SerialName("workspace/changes") WORKSPACE_CHANGES("workspace/changes");
    }
    data class Extension(override val wireName: String) : SessionEventType {
        init { require(wireName.isNotEmpty()); require(Builtin.entries.none { it.wireName == wireName }) }
    }
    companion object {
        @JvmStatic fun fromWire(value: String): SessionEventType = Builtin.entries.firstOrNull { it.wireName == value } ?: Extension(value)
    }
}
object SessionEventTypeSerializer : WireNameSerializer<SessionEventType>("SessionEventType", SessionEventType::fromWire)

abstract class WireNameSerializer<T : WireName>(name: String, private val parse: (String) -> T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.wireName)
}

@Serializable
enum class AgentStatus(override val wireName: String) : WireName {
    @SerialName("idle") IDLE("idle"),
    @SerialName("running") RUNNING("running");
}

@Serializable
enum class RunStatus(override val wireName: String) : WireName {
    @SerialName("ok") OK("ok"),
    @SerialName("error") ERROR("error");
}

@Serializable
enum class CommandStatus(override val wireName: String) : WireName {
    @SerialName("success") SUCCESS("success"),
    @SerialName("error") ERROR("error");
}

@Serializable
enum class MessageRole(override val wireName: String) : WireName {
    @SerialName("system") SYSTEM("system"),
    @SerialName("developer") DEVELOPER("developer"),
    @SerialName("user") USER("user"),
    @SerialName("assistant") ASSISTANT("assistant"),
    @SerialName("tool") TOOL("tool");
}

@Serializable
enum class InboxTarget(override val wireName: String) : WireName {
    @SerialName("next-turn") NEXT_TURN("next-turn"),
    @SerialName("next-step") NEXT_STEP("next-step");
}

@Serializable
enum class InboxOutcome(override val wireName: String) : WireName {
    @SerialName("canceled") CANCELED("canceled");
}

@Serializable
enum class RequestHeaderReason(override val wireName: String) : WireName {
    @SerialName("initial") INITIAL("initial"),
    @SerialName("resume") RESUME("resume"),
    @SerialName("change") CHANGE("change"),
    @SerialName("series") SERIES("series");
}

@Serializable
enum class SystemPromptUpdate(override val wireName: String) : WireName {
    @SerialName("in-history") IN_HISTORY("in-history");
}

@Serializable
enum class ApprovalPolicy(override val wireName: String) : WireName {
    @SerialName("ask") ASK("ask"),
    @SerialName("never") NEVER("never");
}

@Serializable
enum class RecordedApprovalOutcome(override val wireName: String) : WireName {
    @SerialName("allowed-once") ALLOWED_ONCE("allowed-once"),
    @SerialName("rejected") REJECTED("rejected"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("unavailable") UNAVAILABLE("unavailable");
}

@Serializable
enum class TodoStatus(override val wireName: String) : WireName {
    @SerialName("pending") PENDING("pending"),
    @SerialName("in_progress") IN_PROGRESS("in_progress"),
    @SerialName("completed") COMPLETED("completed");
}

@Serializable
enum class GoalPhase(override val wireName: String) : WireName {
    @SerialName("active") ACTIVE("active"),
    @SerialName("paused") PAUSED("paused"),
    @SerialName("blocked") BLOCKED("blocked"),
    @SerialName("complete") COMPLETE("complete");
}

@Serializable
enum class JobStatus(override val wireName: String) : WireName {
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("killed") KILLED("killed"),
    @SerialName("failed") FAILED("failed");
}

@Serializable
enum class JobSettleCause(override val wireName: String) : WireName {
    @SerialName("producer") PRODUCER("producer"),
    @SerialName("kill") KILL("kill"),
    @SerialName("teardown") TEARDOWN("teardown");
}

@Serializable
enum class TeamMemberPhase(override val wireName: String) : WireName {
    @SerialName("provisioning") PROVISIONING("provisioning"),
    @SerialName("active") ACTIVE("active"),
    @SerialName("failed") FAILED("failed");
}

@Serializable
enum class TeamContext(override val wireName: String) : WireName {
    @SerialName("fresh") FRESH("fresh"),
    @SerialName("fork") FORK("fork");
}

@Serializable
enum class TeamTaskStatus(override val wireName: String) : WireName {
    @SerialName("pending") PENDING("pending"),
    @SerialName("in_progress") IN_PROGRESS("in_progress"),
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("deleted") DELETED("deleted");
}

@Serializable
enum class SandboxMode(override val wireName: String) : WireName {
    @SerialName("read-only") READ_ONLY("read-only"),
    @SerialName("workspace-write") WORKSPACE_WRITE("workspace-write"),
    @SerialName("danger-full-access") DANGER_FULL_ACCESS("danger-full-access");
}

@Serializable
enum class DelegationSource(override val wireName: String) : WireName {
    @SerialName("delegation") DELEGATION("delegation");
}

@Serializable
enum class SubagentDescriptorMode(override val wireName: String) : WireName {
    @SerialName("one-shot") ONE_SHOT("one-shot"),
    @SerialName("continuable") CONTINUABLE("continuable");
}

@Serializable
enum class SubagentCatalogMode(override val wireName: String) : WireName {
    @SerialName("one-shot") ONE_SHOT("one-shot"),
    @SerialName("continuable") CONTINUABLE("continuable"),
    @SerialName("external") EXTERNAL("external"),
    @SerialName("unknown") UNKNOWN("unknown");
}

@Serializable
enum class WorkflowAgentOutcome(override val wireName: String) : WireName {
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("cancelled") CANCELLED("cancelled");
}

@Serializable
enum class WorkflowStopReason(override val wireName: String) : WireName {
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("error") ERROR("error");
}

@Serializable
enum class HookDialect(override val wireName: String) : WireName {
    @SerialName("claude-code") CLAUDE_CODE("claude-code"),
    @SerialName("codex") CODEX("codex");
}

@Serializable
enum class FeedbackCategory(override val wireName: String) : WireName {
    @SerialName("task-result") TASK_RESULT("task-result"),
    @SerialName("instruction-following") INSTRUCTION_FOLLOWING("instruction-following"),
    @SerialName("product-interaction") PRODUCT_INTERACTION("product-interaction"),
    @SerialName("service-stability") SERVICE_STABILITY("service-stability"),
    @SerialName("resource-cost") RESOURCE_COST("resource-cost"),
    @SerialName("security-privacy-permission") SECURITY_PRIVACY_PERMISSION("security-privacy-permission"),
    @SerialName("other") OTHER("other");
}

@Serializable
enum class MessageFeedbackRating(override val wireName: String) : WireName {
    @SerialName("positive") POSITIVE("positive"),
    @SerialName("negative") NEGATIVE("negative");
}

@Serializable
enum class RetryMode(override val wireName: String) : WireName {
    @SerialName("normal") NORMAL("normal"),
    @SerialName("always") ALWAYS("always");
}

@Serializable
enum class SearchToolType(override val wireName: String) : WireName {
    @SerialName("web_search_20250305") WEB_SEARCH_20250305("web_search_20250305");
}

@Serializable
enum class SearchToolName(override val wireName: String) : WireName {
    @SerialName("web_search") WEB_SEARCH("web_search");
}

@Serializable
enum class SearchThinkingType(override val wireName: String) : WireName {
    @SerialName("disabled") DISABLED("disabled");
}

@Serializable
enum class ImageMediaType(override val wireName: String) : WireName {
    @SerialName("image/png") IMAGE_PNG("image/png"),
    @SerialName("image/jpeg") IMAGE_JPEG("image/jpeg"),
    @SerialName("image/webp") IMAGE_WEBP("image/webp"),
    @SerialName("image/gif") IMAGE_GIF("image/gif");
}

@Serializable
enum class SdkRequestMethod(override val wireName: String) : WireName {
    @SerialName("initialize") INITIALIZE("initialize"),
    @SerialName("session/prompt") SESSION_PROMPT("session/prompt"),
    @SerialName("session/query") SESSION_QUERY("session/query"),
    @SerialName("session/resume") SESSION_RESUME("session/resume"),
    @SerialName("session/wait") SESSION_WAIT("session/wait"),
    @SerialName("session/working-directory/get") SESSION_WORKING_DIRECTORY_GET("session/working-directory/get"),
    @SerialName("session/working-directory/set") SESSION_WORKING_DIRECTORY_SET("session/working-directory/set"),
    @SerialName("shutdown") SHUTDOWN("shutdown");
}

@Serializable
enum class SdkHostMethod(override val wireName: String) : WireName {
    @SerialName("approval/request") APPROVAL_REQUEST("approval/request"),
    @SerialName("user-question/ask") USER_QUESTION_ASK("user-question/ask");
}

