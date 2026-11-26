package com.github.nyabanban.fuji.emulator.runtime.fixture

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentDescriptor
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.ComponentFactory
import com.github.nyabanban.fuji.emulator.api.HardwareComponent
import com.github.nyabanban.fuji.emulator.api.Setting
import com.github.nyabanban.fuji.emulator.api.ValueKind
import jakarta.inject.Inject
import jakarta.inject.Named
import java.nio.file.Files

class PowerFactory @Inject constructor(context: ComponentContext) :
    ProbeFactory("power", context, emptyMap())

class BusFactory @Inject constructor(context: ComponentContext, @Named("power") power: HardwareComponent) :
    ProbeFactory("bus", context, mapOf("power" to power))

class ClientFactory @Inject constructor(
    context: ComponentContext,
    @Named("bus") bus: HardwareComponent,
    @Named("power") power: HardwareComponent,
) : ProbeFactory("client", context, mapOf("bus" to bus, "power" to power))

class PlainZeroArgFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor("plain-zero-arg", "1")
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        context.events.publish(ComponentEvent(descriptor.id, "create", 0, emptyMap()))
        return ProbeComponent(descriptor.id, context, emptyMap())
    }
}

class LiveSettingFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor(
        id = "live-setting",
        version = "1",
        settings = listOf(Setting("level", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 100.0, live = true)),
    )
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        context.events.publish(ComponentEvent(descriptor.id, "create", 0, emptyMap()))
        return ProbeComponent(descriptor.id, context, emptyMap())
    }
}

class GuiceFieldInjectedFactory : ComponentFactory {
    @com.google.inject.Inject
    lateinit var injectedContext: ComponentContext

    override val descriptor = ComponentDescriptor("guice-field", "1")
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        check(::injectedContext.isInitialized && injectedContext === context) { "Guice field injection did not run" }
        context.events.publish(ComponentEvent(descriptor.id, "create", 0, emptyMap()))
        return ProbeComponent(descriptor.id, context, emptyMap())
    }
}

abstract class ProbeFactory(
    id: String,
    private val injectedContext: ComponentContext,
    private val dependencies: Map<String, HardwareComponent>,
) : ComponentFactory {
    final override val descriptor = ComponentDescriptor(id, "1")

    init {
        injectedContext.events.publish(ComponentEvent(id, "factory", 0, emptyMap()))
    }

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        check(context === injectedContext)
        check(context.directory.fileName.toString() == descriptor.id)
        val component = ProbeComponent(descriptor.id, context, dependencies)
        context.ports.register(component.id, component.id, HardwareComponent::class.java, component)
        context.events.publish(ComponentEvent(component.id, "create", 0, emptyMap()))
        check(!Files.exists(context.dataPath("fail-create"))) { "probe create failed" }
        return component
    }
}

class ProbeComponent(
    override val id: String,
    private val context: ComponentContext,
    private val dependencies: Map<String, HardwareComponent>,
) : HardwareComponent {
    override fun start() {
        context.events.publish(ComponentEvent(id, "start", 0, emptyMap()))
        check(!Files.exists(context.dataPath("fail-start"))) { "probe start failed" }
    }

    override fun close() = context.events.publish(ComponentEvent(id, "close", 0, emptyMap()))
    override fun state() = dependencies
    override fun applySettings(settings: Map<String, Any>) = Unit
}
