package ghidra.program.database.data.rtti.vtable;

import java.nio.ByteBuffer;
import java.util.Arrays;

import ghidra.app.cmd.data.rtti.VbTable;
import ghidra.app.cmd.data.rtti.gcc.VtableModel;
import ghidra.program.database.DBObjectCache;
import ghidra.program.database.data.rtti.ClassTypeInfoManagerDB;
import ghidra.program.database.data.rtti.DataBaseUtils;
import ghidra.program.database.data.rtti.DataBaseUtils.ByteConvertable;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;

public final class VtableModelDB extends AbstractVtableDB implements VbTable {
	
	private final VtableModelPrefixRecord[] records;

	public VtableModelDB(ClassTypeInfoManagerDB manager, DBObjectCache<AbstractVtableDB> cache, long key) {
		super(manager, cache, key);
		ByteBuffer buf = ByteBuffer.wrap(getModelData());
		this.records = new VtableModelPrefixRecord[buf.getInt()];
		for (int i = 0; i < records.length; i++) {
			records[i] = new VtableModelPrefixRecord(buf);
		}
	}

	public VtableModelDB(ClassTypeInfoManagerDB manager, DBObjectCache<AbstractVtableDB> cache,
			VtableModel vtable, db.Record record) {
		super(manager, cache, vtable, record);
		this.records = new VtableModelPrefixRecord[vtable.getElementCount()];
		long[] addresses = Arrays.stream(vtable.getTableAddresses())
								 .mapToLong(manager::encodeAddress)
								 .toArray();
		Function[][] table = vtable.getFunctionTables();
		for (int i = 0; i < records.length; i++) {
			records[i] = new VtableModelPrefixRecord(
				addresses[i], vtable.getBaseOffsetArray(i), table[i]);
		}
		int size = Arrays.stream(records)
						 .mapToInt(VtableModelPrefixRecord::getSize)
						 .sum();
		ByteBuffer buf = ByteBuffer.allocate(size + Integer.BYTES);
		DataBaseUtils.putObjectArray(buf, records);
		record.setBinaryData(SchemaOrdinals.RECORDS.ordinal(), buf.array());
		manager.updateRecord(record);
	}

	@Override
	public long getOffset(int index, int ordinal) {
		return records[index].offsets[ordinal];
	}

	@Override
	public long[] getBaseOffsetArray() {
		return records[0].offsets;
	}

	@Override
	public long[] getBaseOffsetArray(int index) {
		return records[index].offsets;
	}

	@Override
	public Address[] getTableAddresses() {
		return Arrays.stream(records)
					 .map(VtableModelPrefixRecord::getAddress)
					 .toArray(Address[]::new);
	}

	@Override
	public Function[][] getFunctionTables() {
		return Arrays.stream(records)
					 .map(VtableModelPrefixRecord::getFunctions)
					 .toArray(Function[][]::new);
	}

	private class VtableModelPrefixRecord implements ByteConvertable {
		private final long   address;
		private final long[] offsets;
		private final long[] functions;
		
		VtableModelPrefixRecord(ByteBuffer buf) {
			this.address = buf.getLong();
			this.offsets = DataBaseUtils.getLongArray(buf);
			this.functions = DataBaseUtils.getLongArray(buf);
		}
		
		VtableModelPrefixRecord(long address, long[] offsets, Function[] functions) {
			this.address = address;
			this.offsets = offsets;
			this.functions = Arrays.stream(functions)
								   .map(VtableModelDB::getEntryPoint)
								   .mapToLong(manager::encodeAddress)
								   .toArray();
		}
		
		int getSize() {
			return Long.BYTES
				+ Integer.BYTES * 2
				+ offsets.length * Long.BYTES
				+ functions.length * Long.BYTES;
		}
		
		@Override
		public byte[] toBytes() {
			ByteBuffer buf = ByteBuffer.allocate(getSize());
			buf.putLong(address);
			DataBaseUtils.putLongArray(buf, offsets);
			DataBaseUtils.putLongArray(buf, functions);
			return buf.array();
		}
		
		Address getAddress() {
			return manager.decodeAddress(address);
		}
		
		Function[] getFunctions() {
			Listing listing = getProgram().getListing();
			return Arrays.stream(functions)
						 .mapToObj(manager::decodeAddress)
						 .map(listing::getFunctionAt)
						 .toArray(Function[]::new);
		}
	}

}