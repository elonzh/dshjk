package com.elonzh.dshjk.spring.boot

import org.springframework.ai.chat.model.ChatModel

/** Return null when a ChatModel's public configuration cannot describe a native connection. */
fun interface SpringAiModelConfigurationExtractor {
    fun extract(model: ChatModel): SpringAiModelConfiguration?
}
