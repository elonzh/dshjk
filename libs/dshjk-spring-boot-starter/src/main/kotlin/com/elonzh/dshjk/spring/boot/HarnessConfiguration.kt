package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.protocol.ApprovalHandler
import com.elonzh.dshjk.protocol.UserQuestionHandler
import org.springframework.beans.factory.ObjectProvider
import java.util.UUID

internal fun harnessConfiguration(
    properties: HarnessProperties,
    customizers: ObjectProvider<HarnessConfigCustomizer>,
    approval: ObjectProvider<ApprovalHandler>,
    questions: ObjectProvider<UserQuestionHandler>,
    springAi: ObjectProvider<SpringAiModelConfiguration>,
): HarnessConfig {
    val derived = if (properties.hasExplicitModelConfiguration()) null else springAi.ifAvailable
    val credentialReference = derived?.apiKey?.let { "DSH_SPRING_AI_API_KEY_${UUID.randomUUID().toString().replace("-", "")}" }
    val environment = credentialReference?.let { mapOf(it to checkNotNull(derived.apiKey)) }.orEmpty() + properties.environment
    val generated = buildList {
        if (derived != null) {
            val connection = derived.connection.copy(apiKeyEnv = credentialReference ?: derived.connection.apiKeyEnv)
            add(HarnessPatch.providers(mapOf(derived.provider to connection), properties.profile == "sdk-minimal"))
        } else if (properties.providers.isNotEmpty()) {
            add(HarnessPatch.providers(properties.providers, properties.profile == "sdk-minimal"))
        }
        if (properties.mcp.isNotEmpty()) add(HarnessPatch.mcpServers(properties.mcp))
        addAll(properties.patches)
    }
    val builder = HarnessConfig.builder().apply {
        derived?.let {
            provider(it.provider)
            model(it.model)
            it.maxTokens?.let(::maxTokens)
        }
        properties.dshHome?.let(::dshHome)
        properties.cwd?.let(::cwd)
        properties.processCwd?.let(::processCwd)
        properties.profile?.let(::profile)
        properties.provider?.let(::provider)
        properties.model?.let(::model)
        properties.reasoningEffort?.let(::reasoningEffort)
        properties.maxTokens?.let(::maxTokens)
        properties.inheritEnvironment?.let(::inheritEnvironment)
        properties.initializeTimeout?.let(::initializeTimeout)
        properties.requestTimeout?.let(::requestTimeout)
        properties.shutdownTimeout?.let(::shutdownTimeout)
        properties.eofGrace?.let(::eofGrace)
        properties.terminateGrace?.let(::terminateGrace)
        properties.notificationBufferCapacity?.let(::notificationBufferCapacity)
        environment(environment)
        patches(generated)
        patchFiles(properties.patchFiles)
        val runtime = properties.runtime
        require(runtime.cli == null || runtime.executable == null) { "Set either dshjk.runtime.cli or executable" }
        require(runtime.node == null || runtime.cli != null) { "dshjk.runtime.node requires cli" }
        when {
            runtime.cli != null -> runtime(RuntimeCommand.node(runtime.cli, runtime.node ?: "node"))
            runtime.executable != null -> runtime(RuntimeCommand.executable(runtime.executable))
        }
        approval.ifAvailable?.let(::approvalHandler)
        questions.ifAvailable?.let(::userQuestionHandler)
    }
    customizers.orderedStream().use { it.forEach { customizer -> customizer.customize(builder) } }
    return builder.build()
}
