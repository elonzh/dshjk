package com.elonzh.dshjk.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** The native built-in payload vocabulary. Each known event has a concrete type. */
sealed interface SessionEventData {
    val type: SessionEventType
    @Serializable data class AgentPresetSelected(val agentPreset: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.AGENT_PRESET_SELECTED
    }
    @Serializable data class InboxSpliced(val target: InboxTarget, val start: Int, val inserted: List<Message>, val removedCount: Int? = null, val outcome: InboxOutcome? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.AGENT_INBOX_SPLICED
    }
    @Serializable data class ApprovalAsked(val id: String, val toolName: String, val callId: String? = null, val reason: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.APPROVAL_ASKED
    }
    @Serializable data class ApprovalDecided(val id: String, val outcome: RecordedApprovalOutcome) : SessionEventData {
        override val type get() = SessionEventType.Builtin.APPROVAL_DECIDED
    }
    @Serializable data class ApprovalPolicyChanged(val policy: ApprovalPolicy, val source: DelegationSource? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.APPROVAL_POLICY
    }
    @Serializable data class AssistantAttempt(val turn: Long, val step: Long, val stream: List<AssistantStreamRecord>) : SessionEventData {
        override val type get() = SessionEventType.Builtin.ASSISTANT_ATTEMPT
    }
    @Serializable data class AssistantMessage(val turn: Long, val step: Long, val message: Message, val stream: List<AssistantStreamRecord>, val usage: TokenUsage? = null, val interrupted: Boolean? = null) : SessionEventData {
        init { require(message.role == MessageRole.ASSISTANT) { "Assistant event requires assistant role" } }
        override val type get() = SessionEventType.Builtin.ASSISTANT_MESSAGE
    }
    @Serializable data class CommandDone(val commandId: String, val kind: CommandStatus, val text: String? = null, val sourceEventSeq: Long? = null) : SessionEventData {
        init { require(sourceEventSeq == null || sourceEventSeq in 0..9007199254740991L) { "Invalid sourceEventSeq" } }
        override val type get() = SessionEventType.Builtin.COMMAND_DONE
    }
    @Serializable data class CommandRun(val commandId: String, val name: String, val source: CommandSource, val args: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.COMMAND_RUN
    }
    @Serializable data class CompactionEnded(val compactionId: String, val turn: Long?, val sourceCommandId: String? = null, val error: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.COMPACTION_END
    }
    @Serializable data class CompactionPruned(val shadowedRange: SequenceRange, val shadowedSeqs: List<Long>, val shadowedTokenCount: Long) : SessionEventData {
        override val type get() = SessionEventType.Builtin.COMPACTION_PRUNE
    }
    @Serializable data class CompactionStarted(val compactionId: String, val turn: Long?, val sourceCommandId: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.COMPACTION_START
    }
    @Serializable data class CompactionSummary(val compactionId: String, val summary: List<ContentBlock>, val shadowedRange: SequenceRange, val shadowedSeqs: List<Long>, val shadowedTokenCount: Long, val provider: String, val model: String, val sourceCommandId: String? = null, val maxTokens: Int? = null, val usage: TokenUsage? = null, val rawOutput: List<ContentBlock>? = null, val llmStreamCall: Boolean? = null) : SessionEventData {
        init { require(llmStreamCall != true || rawOutput != null) { "A stream compaction must retain rawOutput" } }
        override val type get() = SessionEventType.Builtin.COMPACTION_SUMMARY
    }
    @Serializable data class DeliverablesPresented(val turn: Long, val callId: String, val files: List<PresentedFile>) : SessionEventData {
        override val type get() = SessionEventType.Builtin.DELIVERABLES_PRESENTED
    }
    @Serializable data class DeveloperMessage(val turn: Long, val step: Long, val message: Message, val headerSeq: Long? = null) : SessionEventData {
        init { require(message.role == MessageRole.DEVELOPER) { "Developer event requires developer role" } }
        override val type get() = SessionEventType.Builtin.DEVELOPER_MESSAGE
    }
    @Serializable data class MessageFeedbackDeleted(val sessionId: String, val messageId: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.FEEDBACK_MESSAGE_DELETE
    }
    @Serializable data class MessageFeedbackPut(val sessionId: String, val item: MessageFeedback) : SessionEventData {
        override val type get() = SessionEventType.Builtin.FEEDBACK_MESSAGE_PUT
    }
    @Serializable data class FeedbackRecorded(val text: String? = null, val category: FeedbackCategory? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.FEEDBACK_RECORD
    }
    @Serializable data class GoalChanged(val version: Int, val operation: GoalChangeOperation, val goal: GoalSnapshot? = null, val roundsStarted: Long? = null, val createdAt: Long? = null, val updatedAt: Long? = null, val cleared: GoalRef? = null, val clearedAt: Long? = null) : SessionEventData {
        init { if (operation == GoalChangeOperation.CLEAR) {
                require(cleared != null && clearedAt != null && goal == null) { "Clear requires a goal reference and timestamp" }
            } else {
                require(goal != null && roundsStarted != null && createdAt != null && updatedAt != null && cleared == null) { "Goal mutation requires its complete snapshot" }
            } }
        override val type get() = SessionEventType.Builtin.GOAL_CHANGE
    }
    @Serializable data class HookInvoked(val turn: Long, val point: String, val dialect: HookDialect, val handlerId: String, val matcher: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.HOOK_INVOKED
    }
    @Serializable data class HookResult(val turn: Long, val point: String, val handlerId: String, val decision: String, val durationMs: Double, val exitCode: Int? = null, val stderrSummary: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.HOOK_RESULT
    }
    @Serializable data class ImagesOffloaded(val targets: List<ImageOffloadTarget>) : SessionEventData {
        override val type get() = SessionEventType.Builtin.IMAGE_OFFLOAD
    }
    @Serializable data class JobRegistered(val jobId: String, val owner: String, val kind: String, val label: String, val startedAt: Long, val toolCall: JobToolCall? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.JOB_REGISTERED
    }
    @Serializable data class JobSettled(val jobId: String, val owner: String, val kind: String, val label: String, val startedAt: Long, val finishedAt: Long, val status: JobStatus, val cause: JobSettleCause, val producerReported: Boolean, val toolCall: JobToolCall? = null, val detail: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.JOB_SETTLED
    }
    @Serializable data class LlmRetry(val retryId: String, val turn: Long, val step: Long, val provider: String, val mode: RetryMode, val policyKey: String, val retry: Int, val delayMs: Double, val failure: LlmFailure, val maxRetries: Int? = null) : SessionEventData {
        init { require(mode != RetryMode.NORMAL || maxRetries != null) { "Normal retry requires maxRetries" } }
        override val type get() = SessionEventType.Builtin.LLM_RETRY
    }
    @Serializable data class LlmRetryStarted(val retryId: String, val turn: Long, val step: Long, val retry: Int) : SessionEventData {
        override val type get() = SessionEventType.Builtin.LLM_RETRY_STARTED
    }
    @Serializable data class ModelSelected(val provider: String, val model: String, val reasoningEffort: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.MODEL_SELECTION
    }
    @Serializable data class PermissionPresetChanged(val preset: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.PERMISSION_PRESET
    }
    @Serializable data class PlanModeChanged(val active: Boolean) : SessionEventData {
        override val type get() = SessionEventType.Builtin.PLAN_MODE
    }
    @Serializable data class RequestContext(val provider: String, val model: String, val contextWindow: Long? = null, val systemPromptUpdate: SystemPromptUpdate? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.REQUEST_CONTEXT
    }
    @Serializable data class RequestHeaderChanged(val header: RequestHeader, val reason: RequestHeaderReason, val startsSeries: Boolean? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.REQUEST_HEADER
    }
    @Serializable data class SandboxModeChanged(val mode: SandboxMode, val source: DelegationSource? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SANDBOX_MODE
    }
    @Serializable data class ScheduleChanged(val version: Int, val operation: ScheduleOperation, val schedule: ScheduleRecord? = null, val id: String? = null, val acceptedAt: String? = null) : SessionEventData {
        init { if (operation == ScheduleOperation.CREATE) require(schedule != null && id == null) { "Create requires a schedule" }
            else require(id != null && schedule == null) { "Schedule operation requires its id" } }
        override val type get() = SessionEventType.Builtin.SCHEDULE_CHANGE
    }
    @Serializable data class DeliveryAccepted(val sessionId: String, val throughSeq: Long, val sessionFormatVersion: Int? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SESSION_LOG_DEEPSEEK_DELIVERY_ACCEPTED
    }
    @Serializable data class SeedEnded(val inherited: Boolean? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SESSION_END_SEED
    }
    @Serializable data class TitleChanged(val title: String, val messageSeqs: List<Long>, val source: SessionTitleSource) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SESSION_TITLE
    }
    @Serializable data class TitleLlmRequest(val titleProvider: String, val messageSeqs: List<Long>, val route: ModelSelection, val system: String, val messages: List<Message>, val maxTokens: Int, val reasoningEffort: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SESSION_TITLE_LLM_REQUEST
    }
    @Serializable data class SystemMessage(val turn: Long, val step: Long, val message: Message) : SessionEventData {
        init { require(message.role == MessageRole.SYSTEM) { "System event requires system role" } }
        override val type get() = SessionEventType.Builtin.SYSTEM_MESSAGE
    }
    @Serializable data class StepEnded(val turn: Long, val step: Long) : SessionEventData {
        override val type get() = SessionEventType.Builtin.STEP_END
    }
    @Serializable data class StepStarted(val turn: Long, val step: Long) : SessionEventData {
        override val type get() = SessionEventType.Builtin.STEP_START
    }
    @Serializable data class SubagentCatalog(val childId: String, val childCreatedAt: Long, val version: Int, val mode: SubagentCatalogMode, val label: String? = null, val name: String? = null) : SessionEventData {
        init { require(mode != SubagentCatalogMode.CONTINUABLE || label != null) { "Continuable catalog entry requires a label" } }
        override val type get() = SessionEventType.Builtin.SUBAGENT_CATALOG
    }
    @Serializable data class SubagentDescriptor(val version: Int, val mode: SubagentDescriptorMode, val provider: String, val label: String? = null, val agentProvider: String? = null, val agentModel: String? = null, val agentReasoningEffort: String? = null, val persona: String? = null, val toolFilter: ToolRestriction? = null) : SessionEventData {
        init { require(mode != SubagentDescriptorMode.CONTINUABLE || label != null) { "Continuable descriptor requires a label" } }
        override val type get() = SessionEventType.Builtin.SUBAGENT_DESCRIPTOR
    }
    @Serializable data class SubagentModelSelectionPolicy(val allowedModels: List<AllowedModelRoute>) : SessionEventData {
        override val type get() = SessionEventType.Builtin.SUBAGENT_MODEL_SELECTION_POLICY
    }
    @Serializable data class TeamMemberChanged(val version: Int, val teamId: String, val member: TeamMember) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TEAM_MEMBER
    }
    @Serializable data class TeamMessageDelivered(val version: Int, val teamId: String, val messageId: String, val targetId: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TEAM_MESSAGE_DELIVERED
    }
    @Serializable data class TeamMessageQueued(val version: Int, val teamId: String, val message: TeamMessage) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TEAM_MESSAGE_QUEUED
    }
    @Serializable data class TeamModeChanged(val enabled: Boolean) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TEAM_MODE
    }
    @Serializable data class TeamTaskChanged(val version: Int, val teamId: String, val task: TeamTask) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TEAM_TASK
    }
    @Serializable data class TodosWritten(val todos: List<TodoItem>) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TODO_WRITE
    }
    @Serializable data class WorkflowAgentEnded(val runId: String, val seq: Long, val outcome: WorkflowAgentOutcome) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_WORKFLOW_AGENT_END
    }
    @Serializable data class WorkflowAgentStarted(val runId: String, val seq: Long, val label: String, val childId: String, val phase: String? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_WORKFLOW_AGENT_START
    }
    @Serializable data class WorkflowRunEnded(val runId: String, val stopReason: WorkflowStopReason) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_WORKFLOW_RUN_END
    }
    @Serializable data class WorkflowRunStarted(val runId: String, val name: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_WORKFLOW_RUN_START
    }
    @Serializable data class ToolCalled(val turn: Long, val step: Long, val callId: String, val name: String, val arguments: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_CALL
    }
    @Serializable data class PtcDispatched(val rootCallId: String, val parentCallId: String, val subCallId: String, val name: String, val arguments: JsonElement, val isError: Boolean, val content: List<ContentBlock>, val meta: JsonElement? = null, val error: ToolFailure? = null) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_PTC_DISPATCH
    }
    @Serializable data class PtcDispatchStarted(val rootCallId: String, val parentCallId: String, val subCallId: String, val name: String, val arguments: JsonElement) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TOOL_PTC_DISPATCH_START
    }
    @Serializable data class ToolResult(val turn: Long, val step: Long, val message: Message, val error: ToolFailure? = null, val meta: JsonElement? = null) : SessionEventData {
        init { require(message.role == MessageRole.TOOL && message.toolCallId != null) { "Tool result requires tool role and call identity" } }
        override val type get() = SessionEventType.Builtin.TOOL_RESULT
    }
    @Serializable data class TurnEnded(val turn: Long, val reason: TurnEndReason) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TURN_END
    }
    @Serializable data class TurnStarted(val turn: Long) : SessionEventData {
        override val type get() = SessionEventType.Builtin.TURN_START
    }
    @Serializable data class UserMessage(val id: String, val role: MessageRole, val content: List<ContentBlock>, val source: JsonObject) : SessionEventData {
        init { require(role == MessageRole.USER) { "User event requires user role" } }
        override val type get() = SessionEventType.Builtin.USER_MESSAGE
    }
    @Serializable data class SearchLlmRequest(val endpoint: String, val apiVersion: String, val body: SearchLlmBody) : SessionEventData {
        override val type get() = SessionEventType.Builtin.WEB_DEEPSEEK_SEARCH_LLM_REQUEST
    }
    @Serializable data class WorkingDirectoryChanged(val cwd: String) : SessionEventData {
        override val type get() = SessionEventType.Builtin.WORKING_DIRECTORY_CHANGE
    }
    @Serializable data class WorkspaceChanged(val turn: Long) : SessionEventData {
        override val type get() = SessionEventType.Builtin.WORKSPACE_CHANGES
    }
    data class Extension(override val type: SessionEventType.Extension, val value: JsonElement) : SessionEventData
}

internal fun SessionEventType.Builtin.payloadSerializer(): KSerializer<out SessionEventData> = when (this) {
    SessionEventType.Builtin.AGENT_PRESET_SELECTED -> SessionEventData.AgentPresetSelected.serializer()
    SessionEventType.Builtin.AGENT_INBOX_SPLICED -> SessionEventData.InboxSpliced.serializer()
    SessionEventType.Builtin.APPROVAL_ASKED -> SessionEventData.ApprovalAsked.serializer()
    SessionEventType.Builtin.APPROVAL_DECIDED -> SessionEventData.ApprovalDecided.serializer()
    SessionEventType.Builtin.APPROVAL_POLICY -> SessionEventData.ApprovalPolicyChanged.serializer()
    SessionEventType.Builtin.ASSISTANT_ATTEMPT -> SessionEventData.AssistantAttempt.serializer()
    SessionEventType.Builtin.ASSISTANT_MESSAGE -> SessionEventData.AssistantMessage.serializer()
    SessionEventType.Builtin.COMMAND_DONE -> SessionEventData.CommandDone.serializer()
    SessionEventType.Builtin.COMMAND_RUN -> SessionEventData.CommandRun.serializer()
    SessionEventType.Builtin.COMPACTION_END -> SessionEventData.CompactionEnded.serializer()
    SessionEventType.Builtin.COMPACTION_PRUNE -> SessionEventData.CompactionPruned.serializer()
    SessionEventType.Builtin.COMPACTION_START -> SessionEventData.CompactionStarted.serializer()
    SessionEventType.Builtin.COMPACTION_SUMMARY -> SessionEventData.CompactionSummary.serializer()
    SessionEventType.Builtin.DELIVERABLES_PRESENTED -> SessionEventData.DeliverablesPresented.serializer()
    SessionEventType.Builtin.DEVELOPER_MESSAGE -> SessionEventData.DeveloperMessage.serializer()
    SessionEventType.Builtin.FEEDBACK_MESSAGE_DELETE -> SessionEventData.MessageFeedbackDeleted.serializer()
    SessionEventType.Builtin.FEEDBACK_MESSAGE_PUT -> SessionEventData.MessageFeedbackPut.serializer()
    SessionEventType.Builtin.FEEDBACK_RECORD -> SessionEventData.FeedbackRecorded.serializer()
    SessionEventType.Builtin.GOAL_CHANGE -> SessionEventData.GoalChanged.serializer()
    SessionEventType.Builtin.HOOK_INVOKED -> SessionEventData.HookInvoked.serializer()
    SessionEventType.Builtin.HOOK_RESULT -> SessionEventData.HookResult.serializer()
    SessionEventType.Builtin.IMAGE_OFFLOAD -> SessionEventData.ImagesOffloaded.serializer()
    SessionEventType.Builtin.JOB_REGISTERED -> SessionEventData.JobRegistered.serializer()
    SessionEventType.Builtin.JOB_SETTLED -> SessionEventData.JobSettled.serializer()
    SessionEventType.Builtin.LLM_RETRY -> SessionEventData.LlmRetry.serializer()
    SessionEventType.Builtin.LLM_RETRY_STARTED -> SessionEventData.LlmRetryStarted.serializer()
    SessionEventType.Builtin.MODEL_SELECTION -> SessionEventData.ModelSelected.serializer()
    SessionEventType.Builtin.PERMISSION_PRESET -> SessionEventData.PermissionPresetChanged.serializer()
    SessionEventType.Builtin.PLAN_MODE -> SessionEventData.PlanModeChanged.serializer()
    SessionEventType.Builtin.REQUEST_CONTEXT -> SessionEventData.RequestContext.serializer()
    SessionEventType.Builtin.REQUEST_HEADER -> SessionEventData.RequestHeaderChanged.serializer()
    SessionEventType.Builtin.SANDBOX_MODE -> SessionEventData.SandboxModeChanged.serializer()
    SessionEventType.Builtin.SCHEDULE_CHANGE -> SessionEventData.ScheduleChanged.serializer()
    SessionEventType.Builtin.SESSION_LOG_DEEPSEEK_DELIVERY_ACCEPTED -> SessionEventData.DeliveryAccepted.serializer()
    SessionEventType.Builtin.SESSION_END_SEED -> SessionEventData.SeedEnded.serializer()
    SessionEventType.Builtin.SESSION_TITLE -> SessionEventData.TitleChanged.serializer()
    SessionEventType.Builtin.SESSION_TITLE_LLM_REQUEST -> SessionEventData.TitleLlmRequest.serializer()
    SessionEventType.Builtin.SYSTEM_MESSAGE -> SessionEventData.SystemMessage.serializer()
    SessionEventType.Builtin.STEP_END -> SessionEventData.StepEnded.serializer()
    SessionEventType.Builtin.STEP_START -> SessionEventData.StepStarted.serializer()
    SessionEventType.Builtin.SUBAGENT_CATALOG -> SessionEventData.SubagentCatalog.serializer()
    SessionEventType.Builtin.SUBAGENT_DESCRIPTOR -> SessionEventData.SubagentDescriptor.serializer()
    SessionEventType.Builtin.SUBAGENT_MODEL_SELECTION_POLICY -> SessionEventData.SubagentModelSelectionPolicy.serializer()
    SessionEventType.Builtin.TEAM_MEMBER -> SessionEventData.TeamMemberChanged.serializer()
    SessionEventType.Builtin.TEAM_MESSAGE_DELIVERED -> SessionEventData.TeamMessageDelivered.serializer()
    SessionEventType.Builtin.TEAM_MESSAGE_QUEUED -> SessionEventData.TeamMessageQueued.serializer()
    SessionEventType.Builtin.TEAM_MODE -> SessionEventData.TeamModeChanged.serializer()
    SessionEventType.Builtin.TEAM_TASK -> SessionEventData.TeamTaskChanged.serializer()
    SessionEventType.Builtin.TODO_WRITE -> SessionEventData.TodosWritten.serializer()
    SessionEventType.Builtin.TOOL_WORKFLOW_AGENT_END -> SessionEventData.WorkflowAgentEnded.serializer()
    SessionEventType.Builtin.TOOL_WORKFLOW_AGENT_START -> SessionEventData.WorkflowAgentStarted.serializer()
    SessionEventType.Builtin.TOOL_WORKFLOW_RUN_END -> SessionEventData.WorkflowRunEnded.serializer()
    SessionEventType.Builtin.TOOL_WORKFLOW_RUN_START -> SessionEventData.WorkflowRunStarted.serializer()
    SessionEventType.Builtin.TOOL_CALL -> SessionEventData.ToolCalled.serializer()
    SessionEventType.Builtin.TOOL_PTC_DISPATCH -> SessionEventData.PtcDispatched.serializer()
    SessionEventType.Builtin.TOOL_PTC_DISPATCH_START -> SessionEventData.PtcDispatchStarted.serializer()
    SessionEventType.Builtin.TOOL_RESULT -> SessionEventData.ToolResult.serializer()
    SessionEventType.Builtin.TURN_END -> SessionEventData.TurnEnded.serializer()
    SessionEventType.Builtin.TURN_START -> SessionEventData.TurnStarted.serializer()
    SessionEventType.Builtin.USER_MESSAGE -> SessionEventData.UserMessage.serializer()
    SessionEventType.Builtin.WEB_DEEPSEEK_SEARCH_LLM_REQUEST -> SessionEventData.SearchLlmRequest.serializer()
    SessionEventType.Builtin.WORKING_DIRECTORY_CHANGE -> SessionEventData.WorkingDirectoryChanged.serializer()
    SessionEventType.Builtin.WORKSPACE_CHANGES -> SessionEventData.WorkspaceChanged.serializer()
}
