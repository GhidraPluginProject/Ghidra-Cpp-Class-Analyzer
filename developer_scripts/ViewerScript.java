//@category CppClassAnalyzer
import java.io.File;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.script.GhidraScript;
import ghidra.program.database.data.rtti.ArchiveClassTypeInfoManager;
import ghidra.program.database.data.rtti.ClassTypeInfoManager;
import ghidra.program.model.data.DataType;

public class ViewerScript extends GhidraScript{

	@Override
	public void run() throws Exception {
		File f = new File("C:\\Users\\astre\\Desktop\\test.gdb");
		ArchiveClassTypeInfoManager aman = ArchiveClassTypeInfoManager.open(f);
		println(String.format("Archive has %d types", aman.getTypeCount()));
		monitor.setMessage("Archived Types:");
		monitor.initialize(aman.getTypeCount());
		for (ClassTypeInfo type : aman.getTypes()) {
			monitor.checkCanceled();
			DataType dt = type.getClassDataType();
			println(dt != null ? dt.getName() : "null");
			monitor.incrementProgress(1);
		}
		aman.close();
	}
}