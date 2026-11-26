package com.github.nyabanban.fuji.emulator.catalog

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.yaml.snakeyaml.LoaderOptions
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object CatalogIO {
    const val MAX_BYTES=16*1024*1024
    val json=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val yaml=ObjectMapper(YAMLFactory.builder().loaderOptions(LoaderOptions().apply {maxAliasesForCollections=0;codePointLimit=16*1024*1024}).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    fun sha256(bytes:ByteArray):String=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun canonical(value:Any?):ByteArray=(json.writeValueAsString(value)+"\n").toByteArray(StandardCharsets.UTF_8)
    fun readText(bytes:ByteArray):String {
        require(bytes.isNotEmpty()&&bytes.last()==10.toByte()&&bytes.none{it==13.toByte()}) {"catalog text requires final LF and forbids CR"}
        require(!(bytes.size>=3&&bytes[0]==0xef.toByte()&&bytes[1]==0xbb.toByte()&&bytes[2]==0xbf.toByte())){"UTF-8 BOM forbidden"}
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun readYaml(path:Path):Map<String,Any?> {require(Files.size(path)<=16*1024*1024);return obj(yaml.readValue(readText(Files.readAllBytes(path)),Map::class.java))}
    fun writeJson(path:Path,value:Any?) {Files.createDirectories(path.toAbsolutePath().parent);Files.write(path,canonical(value))}
    fun writeText(path:Path,text:String) {require(!text.contains('\r')&&!text.startsWith('\uFEFF'));Files.createDirectories(path.toAbsolutePath().parent);Files.writeString(path,text.trimEnd('\n')+"\n",StandardCharsets.UTF_8)}
    fun readCompiled(bytes:ByteArray):AddressCatalog {
        require(bytes.size<=MAX_BYTES){"compiled dictionary exceeds limit"}
        val envelope=obj(json.readValue(readText(bytes),Map::class.java));require(envelope.keys==setOf("schema","payloadSha256","data")&&valueLong(envelope["schema"])==1L)
        val data=obj(envelope["data"]);require(sha256(canonical(data))==envelope["payloadSha256"]){"compiled dictionary digest differs"}
        CatalogCompiler.validate(data)
        return AddressCatalog(data,envelope.text("payloadSha256"))
    }
    fun compiledBytes(catalog:AddressCatalog):ByteArray=canonical(linkedMapOf("schema" to 1,"payloadSha256" to catalog.artifactSha256,"data" to catalog.data))
}

/** The compiler implements the closed JSON-schema vocabulary used by catalog/schema. */
object CatalogSchema {
    private val patternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun validate(value:Any?,schema:Map<String,Any?>,path:String="root") {
        val type=schema["type"]
        val valid=when(type){"object"->value is Map<*,*>;"array"->value is List<*>;"string"->value is String;"integer"->value is Byte||value is Short||value is Int||value is Long;"boolean"->value is Boolean;"null"->value==null;null->true;else->error("unsupported schema type: "+type)}
        require(valid){path+": type differs"}
        schema["enum"]?.let{require(value in (it as List<*>)){path+": enum differs"}}
        if(value is Map<*,*>) {
            val row=obj(value);val properties=obj(schema["properties"]?:emptyMap<String,Any?>())
            (schema["required"] as? List<*>)?.forEach{require(it in row){path+": missing "+it}}
            if(schema["additionalProperties"]==false)require((row.keys-properties.keys).isEmpty()){path+": unknown keys "+(row.keys-properties.keys)}
            schema["minProperties"]?.let { require(row.size >= valueLong(it)) { path+": too few properties" } }
            schema["maxProperties"]?.let { require(row.size <= valueLong(it)) { path+": too many properties" } }
            schema["propertyNames"]?.let { names -> row.keys.forEach { validate(it, obj(names), path+" property name") } }
            val additional = schema["additionalProperties"]
            if (additional is Map<*, *>) (row.keys-properties.keys).forEach { key -> validate(row[key], obj(additional), path+"."+key) }
            properties.forEach{(key,child)->if(key in row)validate(row[key],obj(child),path+"."+key)}
        }
        if(value is List<*>) {
            schema["minItems"]?.let { require(value.size >= valueLong(it)) { path+": too few items" } }
            schema["maxItems"]?.let { require(value.size <= valueLong(it)) { path+": too many items" } }
            if (schema["uniqueItems"] == true) require(value.distinct().size == value.size) { path+": duplicate items" }
            schema["items"]?.let { item -> value.forEachIndexed { i,v -> validate(v,obj(item),path+"["+i+"]") } }
        }
        if(value is String) schema["pattern"]?.let{val regex=patternCache.computeIfAbsent(it as String, ::Regex); require(regex.containsMatchIn(value)){path+": pattern differs"}}
        if(value is Number) {schema["minimum"]?.let{require(valueLong(value)>=valueLong(it)){path+": below minimum"}};schema["maximum"]?.let{require(valueLong(value)<=valueLong(it)){path+": above maximum"}}}
    }
}
