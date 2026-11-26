package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FormatsTextTest {
    @Test fun failedStreamingSerializationPreservesPreviousFileAndRemovesTemporary(@TempDir root: Path) {
        class Broken
        val module = com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(Broken::class.java,
            object : com.fasterxml.jackson.databind.JsonSerializer<Broken>() {
                override fun serialize(value: Broken, generator: com.fasterxml.jackson.core.JsonGenerator,
                                       provider: com.fasterxml.jackson.databind.SerializerProvider) {
                    generator.writeStartArray()
                    repeat(10000) { generator.writeString("part-$it") }
                    throw java.io.IOException("intentional serialization failure")
                }
            })
        Formats.json.registerModule(module)
        val path = root.resolve("state.json")
        Files.writeString(path, "previous\n")
        assertFails { Formats.writeJson(path, Broken()) }
        assertEquals("previous\n", Files.readString(path))
        Files.list(root).use { assertEquals(listOf(path), it.toList()) }
    }

    @Test fun allMaintainedJsonYamlAndMultilineTextUseLfWithoutChangingValues(@TempDir root: Path) {
        val value = mapOf("中文" to mapOf("text" to "a\r\nb", "items" to listOf(1, 2)))
        val encoded = Formats.jsonText(value)
        assertFalse(encoded.contains('\r')); assertTrue(encoded.endsWith("\n")); assertFalse(encoded.startsWith("\uFEFF"))
        assertEquals(value, Formats.json.readValue(encoded, Map::class.java))
        val json = root.resolve("data.json"); Formats.writeJson(json, value)
        assertEquals(encoded, Files.readString(json))
        val yaml = root.resolve("data.yaml"); Formats.atomicYaml(yaml, value)
        val yamlText = Files.readString(yaml); assertFalse(yamlText.contains('\r')); assertTrue(yamlText.endsWith("\n"))
        assertEquals(value, Formats.yaml.readValue(yamlText, Map::class.java))
        assertEquals("x\ny\nz\n", Formats.lf("\uFEFFx\r\ny\rz"))
    }
}
