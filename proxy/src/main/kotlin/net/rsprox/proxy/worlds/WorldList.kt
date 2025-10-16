package net.rsprox.proxy.worlds

import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import net.rsprot.buffer.JagByteBuf
import net.rsprot.buffer.extensions.toJagByteBuf
import net.rsprox.proxy.target.ProxyTargetConfig
import java.io.IOException
import java.net.URL

public data class WorldList(
    public val worlds: List<World>,
) : List<World> by worlds {
    public constructor(
        proxyTargetConfig: ProxyTargetConfig,
        url: URL,
    ) : this(
        parseWorlds(proxyTargetConfig, url),
    )

    public fun encodeLocalized(allocator: ByteBufAllocator): JagByteBuf {
        val capacity = estimateBufferCapacity()
        val buffer = allocator.buffer(capacity).toJagByteBuf()
        val offset = buffer.writerIndex()
        buffer.p4(capacity - Int.SIZE_BYTES)
        buffer.p2(worlds.size)
        for (world in worlds) {
            buffer.p2(world.id)
            buffer.p4(world.properties)
            buffer.pjstr(world.localHostAddress.toString())
            buffer.pjstr(world.activity)
            buffer.p1(world.location)
            buffer.p2(world.population)
        }
        check(buffer.writerIndex() - offset == capacity) {
            "Invalid buffer capacity estimate"
        }
        return buffer
    }

    public fun encode(allocator: ByteBufAllocator): JagByteBuf {
        val buffer = allocator.buffer().toJagByteBuf()
        val pos = buffer.writerIndex()
        buffer.p4(0)
        buffer.p2(worlds.size)
        for (world in worlds) {
            buffer.p2(world.id)
            buffer.p4(world.properties)
            buffer.pjstr(world.host)
            buffer.pjstr(world.activity)
            buffer.p1(world.location)
            buffer.p2(world.population)
        }
        val end = buffer.writerIndex()
        buffer.writerIndex(pos)
        buffer.p4(end - pos - Int.SIZE_BYTES)
        buffer.writerIndex(end)
        return buffer
    }

    public fun toRuneLiteWorldResult(): RuneLiteWorldResult {
        return RuneLiteWorldResult(
            buildList {
                for (world in worlds) {
                    add(
                        RuneLiteWorld(
                            world.id,
                            RuneLiteWorldType.fromMask(world.properties),
                            world.localHostAddress.toString(),
                            world.activity,
                            world.location,
                            world.population,
                        ),
                    )
                }
            },
        )
    }

    public fun getWorld(address: LocalHostAddress): World? {
        return worlds.firstOrNull { world ->
            world.localHostAddress == address
        }
    }

    public fun getTargetWorld(address: String): World? {
        return worlds.firstOrNull { world ->
            world.host == address
        }
    }

    private fun estimateBufferCapacity(): Int {
        // Header consists of payload size (4 bytes) + world count (2 bytes)
        return Int.SIZE_BYTES + Short.SIZE_BYTES + worlds.sumOf(::worldBufferCapacity)
    }

    private companion object {
        @Throws(IOException::class)
        private fun parseWorlds(
            config: ProxyTargetConfig,
            url: URL,
        ): List<World> {
            val bytes = url.readBytes()
            val buffer = Unpooled.wrappedBuffer(bytes).toJagByteBuf()
            return decode(
                config,
                buffer,
            )
        }

        private fun decode(
            config: ProxyTargetConfig,
            buffer: JagByteBuf,
        ): List<World> {
            val payloadSize = buffer.g4()
            val payloadStartIndex = buffer.readerIndex()
            val expectedPayloadEnd = payloadStartIndex + payloadSize
            val count = buffer.g2()
            val worldList =
                buildList {
                    repeat(count) {
                        val id = buffer.g2()
                        val properties = buffer.g4()
                        val host = buffer.gjstr()
                        val activity = buffer.gjstr()
                        val location = buffer.g1()
                        val population = buffer.g2s()
                        add(World(config, id, properties, population, location, host, activity))
                    }
                }
            val actualReaderIndex = buffer.readerIndex()
            when {
                actualReaderIndex == expectedPayloadEnd -> {
                    // Expected state, no-op.
                }
                actualReaderIndex < expectedPayloadEnd -> {
                    val unreadBytes = expectedPayloadEnd - actualReaderIndex
                    logger.debug {
                        "World list payload declared $payloadSize bytes but $unreadBytes bytes were left unread; skipping trailing data"
                    }
                    if (expectedPayloadEnd <= buffer.writerIndex()) {
                        buffer.readerIndex(expectedPayloadEnd)
                    } else {
                        logger.warn {
                            "World list payload declared $payloadSize bytes but only ${buffer.writerIndex() - payloadStartIndex} bytes were available"
                        }
                    }
                }
                else -> {
                    throw IllegalStateException("World list format invalid (read past payload)")
                }
            }
            return worldList
        }

        private fun worldBufferCapacity(world: World): Int {
            // Id + properties + location + population + 2 string terminators + 2 string lengths
            return Short.SIZE_BYTES +
                Int.SIZE_BYTES +
                Byte.SIZE_BYTES +
                Short.SIZE_BYTES +
                Short.SIZE_BYTES +
                world.localHostAddress.toString().length +
                world.activity.length
        }

        private val logger = KotlinLogging.logger {}
    }
}
