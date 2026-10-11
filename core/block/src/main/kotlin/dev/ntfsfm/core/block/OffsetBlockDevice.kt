package dev.ntfsfm.core.block

/** A window onto [base]: one partition seen as a whole device starting at sector 0. */
class OffsetBlockDevice(
    private val base: BlockDevice,
    private val startSector: Long,
    override val sectorCount: Long,
) : BlockDevice {
    override val sectorSize: Int get() = base.sectorSize

    override suspend fun readSectors(startSector: Long, sectorCount: Int, into: ByteArray, offset: Int) {
        require(startSector + sectorCount <= this.sectorCount) { "read past end of partition" }
        base.readSectors(this.startSector + startSector, sectorCount, into, offset)
    }

    override suspend fun writeSectors(startSector: Long, sectorCount: Int, from: ByteArray, offset: Int) {
        require(startSector + sectorCount <= this.sectorCount) { "write past end of partition" }
        base.writeSectors(this.startSector + startSector, sectorCount, from, offset)
    }

    override suspend fun flush() = base.flush()

    // The underlying device outlives its partitions; whoever opened it closes it.
    override suspend fun close() = Unit
}
