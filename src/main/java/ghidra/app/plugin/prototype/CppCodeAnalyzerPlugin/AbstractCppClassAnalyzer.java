package ghidra.app.plugin.prototype.CppCodeAnalyzerPlugin;

import java.util.*;

import ghidra.util.task.TaskMonitor;
import ghidra.framework.options.Options;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.plugin.core.analysis.ConstantPropagationContextEvaluator;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.program.model.address.Address;
import ghidra.program.util.SymbolicPropogator;
import ghidra.program.model.listing.*;
import ghidra.app.cmd.data.rtti.gcc.typeinfo.*;
import ghidra.util.ConsoleErrorDisplay;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.data.DataTypeComponent;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.Structure;
import ghidra.program.model.lang.Register;
import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.app.cmd.data.rtti.gcc.ClassTypeInfoUtils;

public abstract class AbstractCppClassAnalyzer extends AbstractAnalyzer {

    private static final String DESCRIPTION =
        "This analyzer analyzes RTTI metadata to recreate classes and their functions";

    private static final String OPTION_VTABLE_ANALYSIS_NAME = "Locate Constructors";
    private static final boolean OPTION_DEFAULT_VTABLE_ANALYSIS = false;
    private static final String OPTION_VTABLE_ANALYSIS_DESCRIPTION =
        "Turn on to search for Constructors/Destructors.";

    private static final String OPTION_FILLER_ANALYSIS_NAME = "Fill Class Fields";
    private static final boolean OPTION_DEFAULT_FILLER_ANALYSIS = false;
    private static final String OPTION_FILLER_ANALYSIS_DESCRIPTION =
        "Turn on to fill out the found class structures.";

    private boolean constructorAnalysisOption;
    private boolean fillClassFieldsOption;

    protected Program program;
    private TaskMonitor monitor;
    private SymbolicPropogator symProp;

    private List<ClassTypeInfo> classes;
    private ArrayList<Vtable> vftables;

    protected AbstractConstructorAnalysisCmd constructorAnalyzer;

    protected MessageLog log;

    /**
     * Constructs an AbstractCppClassAnalyzer.
     */
    public AbstractCppClassAnalyzer(String name) {
        super(name, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
        setSupportsOneTimeAnalysis();
        setDefaultEnablement(true);
        setPrototype();
    }

    protected abstract boolean hasVtt();
    protected abstract List<ClassTypeInfo> getClassTypeInfoList();
    protected abstract AbstractConstructorAnalysisCmd getConstructorAnalyzer();
    protected abstract boolean analyzeVftable(ClassTypeInfo type);
    protected abstract boolean analyzeConstructor(ClassTypeInfo type);
    protected abstract boolean isDestructor(Function function);

    @Override
    @SuppressWarnings("hiding")
    public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
            throws CancelledException {
        this.program = program;
        this.monitor = monitor;
        this.log = log;
        this.constructorAnalyzer = getConstructorAnalyzer();

        classes = getClassTypeInfoList();

        try {
            setupVftables();
            analyzeVftables();
            repairInheritance();
            if (fillClassFieldsOption) {
                fillStructures();
            }
            return true;
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            log.appendException(e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("hiding")
    public void analysisEnded(Program program) {
        classes = null;
        vftables = null;
        symProp = null;
        constructorAnalyzer = null;
        super.analysisEnded(program);
    }

    private void setupVftables() throws CancelledException, InvalidDataTypeException {
        vftables = new ArrayList<>(classes.size());
        monitor.initialize(classes.size());
        monitor.setMessage("Locating vftables...");
        for (ClassTypeInfo type : classes) {
            monitor.checkCanceled();
            Vtable vftable = type.getVtable();
            try {
                vftable.validate();
                vftables.add(vftable);
            } catch (InvalidDataTypeException e) {}
            monitor.incrementProgress(1);
        }
    }

    private void repairInheritance() throws CancelledException, InvalidDataTypeException {
        monitor.initialize(classes.size());
        monitor.setMessage("Fixing Class Inheritance...");
        for (ClassTypeInfo type : classes) {
            monitor.checkCanceled();
            if (type.getName().contains(TypeInfoModel.STRUCTURE_NAME)) {
                // this works for both vs and gcc
                continue;
            }
            try {
                type.getClassDataType(true);
            } catch (IndexOutOfBoundsException e) {
                Msg.trace(this, e);
            } catch (InvalidDataTypeException e) {
                Msg.info(this, "Unable to resolve inheritance model for "+type.getName());
            }
            monitor.incrementProgress(1);
        }
    }

    private void fillStructures() throws Exception {
        repairInheritance();
        if (fillClassFieldsOption) {
            symProp = new SymbolicPropogator(program);
            monitor.initialize(vftables.size());
            monitor.setMessage("Filling Class Structures...");
            for (Vtable vtable : vftables) {
                ClassTypeInfo type = vtable.getTypeInfo();
                if (type.getName().contains(TypeInfoModel.STRUCTURE_NAME)) {
                    continue;
                }
                Function[][] fTable = vtable.getFunctionTables();
                if (fTable.length > 0 && fTable[0].length > 0) {
                    Function destructor = fTable[0][0];
                    if (destructor != null && isDestructor(destructor)) {
                        analyzeDestructor(type, destructor);
                    }
                }
                monitor.incrementProgress(1);
            }
        }
    }

    private Structure getMemberDataType(Function function) {
        Parameter auto = function.getParameter(0);
        if (auto.getDataType() instanceof Pointer) {
            Pointer pointer = (Pointer) auto.getDataType();
            if (pointer.getDataType() instanceof Structure) {
                return (Structure) pointer.getDataType();   
            }
        }
        return VariableUtilities.findExistingClassStruct(
            (GhidraClass) function.getParentNamespace(), program.getDataTypeManager());
    }

    private void clearComponent(Structure struct, int length, int offset) {
        if (offset >= struct.getLength()) {
            return;
        }
        for (int size = 0; size < length;) {
            DataTypeComponent comp = struct.getComponentAt(offset);
            if (comp!= null) {
                size += comp.getLength();
            } else {
                size++;
            }
            struct.deleteAtOffset(offset);
        }
    }

    private void propagateConstants(Function function, ClassTypeInfo type) 
        throws CancelledException {
            Parameter auto = function.getParameter(0);
            try {
                symProp.setRegister(type.getVtable().getTableAddresses()[0], auto.getRegister());
            } catch (InvalidDataTypeException e) {}
            ConstantPropagationContextEvaluator eval =
                    new ConstantPropagationContextEvaluator(true);
            symProp.flowConstants(
                function.getEntryPoint(), function.getBody(), eval, true, monitor);
    }

    private void analyzeDestructor(ClassTypeInfo type, Function destructor) throws Exception {
        InstructionIterator instructions = program.getListing().getInstructions(
            destructor.getBody(), true);
        propagateConstants(destructor, type);
        Register thisRegister = destructor.getParameter(0).getRegister();
        for (Instruction inst : instructions) {
            monitor.checkCanceled();
            if (inst.getFlowType().isCall() && !inst.getFlowType().isComputed()) {
                Function function = getFunction(inst.getFlows()[0]);
                if (function == null || !isDestructor(function)) {
                    continue;
                }
                int delayDepth = inst.getDelaySlotDepth();
                for (int i = 0; i <= delayDepth; i++) {
                    inst = inst.getNext();
                }
                SymbolicPropogator.Value value = symProp.getRegisterValue(
                    inst.getAddress(), thisRegister);
                if (value != null && value.getValue() > 0) {
                    Structure struct = type.getClassDataType();
                    DataTypeComponent comp = struct.getComponentAt((int) value.getValue());
                    Structure member = getMemberDataType(function);
                    if (comp != null) {
                        if (comp.getDataType() instanceof Structure) {
                            continue;
                        }
                        clearComponent(struct, member.getLength(), (int) value.getValue());
                    }
                    struct.insertAtOffset((int) value.getValue(), member, member.getLength());
                }
            }
        }
    }

    private Function getFunction(Address address) {
        Listing listing = program.getListing();
        if (listing.getInstructionAt(address) == null) {
            DisassembleCommand cmd = new DisassembleCommand(address, null, true);
            if (!cmd.applyTo(program)) {
                return null;
            }
        }
        FunctionManager manager = program.getFunctionManager();
        Function function = manager.getFunctionContaining(address);
        if (function == null) {
            CreateFunctionCmd cmd = new CreateFunctionCmd(address, true);
            if (cmd.applyTo(program)) {
                return cmd.getFunction();
            }
        }
        return function;
    }

    

    protected void analyzeVftables() throws Exception {
        List<ClassTypeInfo> namespaces = new ArrayList<>(vftables.size());
        monitor.initialize(vftables.size());
        monitor.setMessage("Setting up namespaces");
        for (Vtable vtable : vftables) {
            monitor.checkCanceled();
            ClassTypeInfo type = vtable.getTypeInfo();
            try {
                type.validate();
            } catch (InvalidDataTypeException e) {
                continue;
            }
            namespaces.add(type);
            monitor.incrementProgress(1);
        }
        ClassTypeInfoUtils.sortByMostDerived(program, namespaces);
        monitor.initialize(vftables.size());
        monitor.setMessage("Analyzing Vftables");
        for (ClassTypeInfo type : namespaces) {
            monitor.checkCanceled();
            analyzeVftable(type);
            monitor.incrementProgress(1);
        }
        Collections.reverse(namespaces);
        if (constructorAnalysisOption) {
            analyzeConstructors(namespaces);
        }
    }

    protected boolean shouldAnalyzeConstructors() {
        return constructorAnalysisOption;
    }

    protected void analyzeConstructors(List<ClassTypeInfo> namespaces) throws Exception {
        monitor.initialize(namespaces.size());
        monitor.setMessage("Creating Constructors");
        for (ClassTypeInfo type : namespaces) {
            monitor.checkCanceled();
            analyzeConstructor(type);
            monitor.incrementProgress(1);
        }
    }
    
    @SuppressWarnings("hiding")
    @Override
	public void optionsChanged(Options options, Program program) {
        super.optionsChanged(options, program);
        options.registerOption(OPTION_VTABLE_ANALYSIS_NAME, OPTION_DEFAULT_VTABLE_ANALYSIS, null,
            OPTION_VTABLE_ANALYSIS_DESCRIPTION);
        options.registerOption(OPTION_FILLER_ANALYSIS_NAME, OPTION_DEFAULT_FILLER_ANALYSIS, null,
            OPTION_FILLER_ANALYSIS_DESCRIPTION);

        constructorAnalysisOption =
            options.getBoolean(OPTION_VTABLE_ANALYSIS_NAME, OPTION_DEFAULT_VTABLE_ANALYSIS);
        fillClassFieldsOption =
            options.getBoolean(OPTION_FILLER_ANALYSIS_NAME, OPTION_DEFAULT_FILLER_ANALYSIS);
    }
}
