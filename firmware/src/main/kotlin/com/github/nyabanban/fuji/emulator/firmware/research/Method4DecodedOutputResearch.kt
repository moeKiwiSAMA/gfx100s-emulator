package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.firmware.Method4
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** Export one already-qualified DAT stream; this does not execute or classify its decoded payload. */
object Method4DecodedOutputResearch {
    private const val OFFSET=0x1900274L
    private const val PACKED=0x354dd3
    private const val UNPACKED=0x67b8a8
    private const val PACKED_SHA="16ef54fc26bdd759ae9d772d9a2d32e50483c6ffae6f68dd7434fc0348217a68"
    private const val OUTPUT_SHA="8ed6e7215a101961e2fd87401a8fafcf6f6f86a1db63fa589adf695ee710044d"
    private fun sha(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2) { "Method4DecodedOutputResearch DAT NEW_OUTPUT_DIRECTORY" }
        val input=Path.of(args[0]).toRealPath()
        val output=Path.of(args[1]).toAbsolutePath().normalize()
        require(Files.isRegularFile(input) && Files.size(input)>=OFFSET+PACKED && !Files.exists(output))
        val packed=ByteArray(PACKED)
        Files.newByteChannel(input).use { channel ->
            channel.position(OFFSET)
            val buffer=ByteBuffer.wrap(packed)
            while(buffer.hasRemaining())check(channel.read(buffer)>0) { "Truncated selected DAT stream" }
        }
        for(i in packed.indices)packed[i]=(packed[i].toInt() xor 255).toByte()
        check(sha(packed)==PACKED_SHA) { "Selected xor-decoded container differs from the qualified stream" }
        val header=ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        val words=List(5){header.int}
        check(words==listOf(UNPACKED,PACKED,0xd6,0x4000,4))
        val decoded=Method4.decode(packed)
        check(decoded.size==UNPACKED && sha(decoded)==OUTPUT_SHA) { "Decoded output differs from the qualified oracle" }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        Files.write(output.resolve("decoded.bin"),decoded,CREATE_NEW)
        Formats.writeJson(output.resolve("receipt.generated.json"),linkedMapOf(
            "input" to input.toString(),"selectedDatOffset" to OFFSET,"selectedDatBytes" to PACKED,
            "transform" to "xor-ff then existing Method4.decode","containerHeaderWords" to words,
            "containerSha256AfterXor" to PACKED_SHA,"decodedBytes" to decoded.size,"decodedSha256" to OUTPUT_SHA,
            "first64BytesHex" to java.util.HexFormat.of().formatHex(decoded.copyOfRange(0,64)),
            "wholeDatRehashed" to false,"guestExecuted" to false,"payloadKind" to "UNKNOWN",
            "scope" to "Derived DAT output, not an independent camera capture. Exact existing oracle identity; no runtime, input, ready/IRQ, memory-map or controller behavior changes."))
        println("Exported ${decoded.size} verified decoded bytes; payload kind remains UNKNOWN")
    }
}
