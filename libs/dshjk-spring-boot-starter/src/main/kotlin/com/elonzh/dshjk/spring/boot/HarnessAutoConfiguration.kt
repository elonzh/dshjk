package com.elonzh.dshjk.spring.boot

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.protocol.ApprovalHandler
import com.elonzh.dshjk.protocol.UserQuestionHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** One lazily launched, context-owned native Harness runtime. */
@AutoConfiguration
@ConditionalOnClass(DeepSeekHarness::class)
@ConditionalOnProperty(prefix = "dshjk", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HarnessProperties::class)
class HarnessAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun deepSeekHarness(config: HarnessConfig): DeepSeekHarness = DeepSeekHarness(config)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(HarnessConfig::class, DeepSeekHarness::class)
    internal class DefaultConfiguration {
        @Bean(name = ["harnessConfig"])
        internal fun harnessConfig(
            properties: HarnessProperties,
            customizers: ObjectProvider<HarnessConfigCustomizer>,
            approval: ObjectProvider<ApprovalHandler>,
            questions: ObjectProvider<UserQuestionHandler>,
            springAi: ObjectProvider<SpringAiModelConfiguration>,
        ): HarnessConfig = harnessConfiguration(properties, customizers, approval, questions, springAi)
    }
}
