package ghidra.app.cmd.data.rtti.gcc;

import java.util.Map;

import ghidra.app.cmd.data.rtti.AbstractCppClassBuilder;
import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.program.database.data.rtti.typeinfo.AbstractClassTypeInfoDB;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeComponent;

import static ghidra.program.model.data.Undefined.isUndefined;

public class GccCppClassBuilder extends AbstractCppClassBuilder {

	private static final String VPTR = "_vptr";

	public GccCppClassBuilder(ClassTypeInfo type) {
		super(type);
	}

	@Override
	protected AbstractCppClassBuilder getParentBuilder(ClassTypeInfo parent) {
		return new GccCppClassBuilder(parent);
	}

	@Override
	protected void addVptr() {
		if (getType().getVtable() == Vtable.NO_VTABLE) {
			return;
		}
		DataType vptr = ClassTypeInfoUtils.getVptrDataType(getProgram(), getType(), getPath());
		DataTypeComponent comp = struct.getComponentAt(0);
		if (comp == null || isUndefined(comp.getDataType())) {
			if (vptr != null) {
				clearComponent(struct, getProgram().getDefaultPointerSize(), 0);
				struct.insertAtOffset(0, vptr, getProgram().getDefaultPointerSize(), VPTR, null);
			}
		} else if (comp.getFieldName() == null || !comp.getFieldName().startsWith(SUPER)) {
			clearComponent(struct, getProgram().getDefaultPointerSize(), 0);
			struct.insertAtOffset(0, vptr, getProgram().getDefaultPointerSize(), VPTR, null);
		}
	}

	@Override
	protected Map<ClassTypeInfo, Integer> getBaseOffsets() {
		ClassTypeInfo type = getType();
		if (type instanceof AbstractClassTypeInfoDB) {
			return ((AbstractClassTypeInfoDB) type).getBaseOffsets();
		}
		return ClassTypeInfoUtils.getBaseOffsets(type);
	}
}
