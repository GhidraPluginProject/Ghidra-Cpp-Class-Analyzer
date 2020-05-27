package ghidra.app.plugin.prototype.typemgr.node;

import java.util.*;
import java.util.stream.Collectors;

import javax.swing.Icon;

import ghidra.program.model.address.Address;

import cppclassanalyzer.data.typeinfo.ArchivedClassTypeInfo;
import cppclassanalyzer.data.typeinfo.ClassTypeInfoDB;
import cppclassanalyzer.database.record.TypeInfoTreeNodeRecord;

import docking.widgets.tree.GTreeLazyNode;
import docking.widgets.tree.GTreeNode;
import resources.ResourceManager;

import static cppclassanalyzer.database.schema.fields.TypeInfoTreeNodeSchemaFields.*;

public final class TypeInfoNode extends GTreeLazyNode implements TypeInfoTreeNode {

	private final boolean isVirtual;
	private final TypeInfoTreeNodeRecord record;
	private NamespacePathNode nested;
	private ClassTypeInfoDB type;
	private ModifierType modifier;

	private TypeInfoNode(ClassTypeInfoDB type) {
		this(type, false);
	}

	private TypeInfoNode(ClassTypeInfoDB type, boolean isVirtual) {
		this.isVirtual = isVirtual;
		this.type = type;
		this.modifier = getModifier();
		this.record = null;
		this.nested = null;
	}

	TypeInfoNode(ClassTypeInfoDB type, TypeInfoTreeNodeRecord record) {
		this.isVirtual = false;
		this.record = record;
		this.type = type;
		this.modifier = getModifier();
		long[] kids = record.getLongArray(CHILDREN_KEYS);
		if (kids.length > 0) {
			this.nested = new NamespacePathNode(getManager(), record);
		}
	}

	TypeInfoNode(ClassTypeInfoDB type, NamespacePathNode nested) {
		this.isVirtual = false;
		this.nested = nested;
		this.record = nested.getRecord();
		this.type = type;
		this.modifier = getModifier();
		record.setByteValue(TYPE_ID, TypeInfoTreeNodeRecord.TYPEINFO_NODE);
		getManager().updateRecord(record);
	}

	private ModifierType getModifier() {
		if (type.isAbstract()) {
			return isVirtual ? ModifierType.VIRTUAL_ABSTRACT : ModifierType.ABSTRACT;
		}
		return isVirtual ? ModifierType.VIRTUAL : ModifierType.NORMAL;
	}

	@Override
	public void addNode(GTreeNode node) {
		if (nested == null) {
			nested = new NamespacePathNode(getManager(), record);
		}
		nested.addNode(node);
	}

	@Override
	public GTreeNode clone() {
		return this;
	}

	@Override
	public int compareTo(GTreeNode node) {
		if (node instanceof NamespacePathNode) {
			return 1;
		}
		return super.compareTo(node);
	}

	@Override
	protected List<GTreeNode> generateChildren() {
		List<GTreeNode> parents = Arrays.stream(type.getParentModels())
			.map(TypeInfoNode::new)
			.collect(Collectors.toList());
		Set<GTreeNode> vParents = type.getVirtualParents()
			.stream()
			.map(ClassTypeInfoDB.class::cast)
			.map(p -> new TypeInfoNode(p, true))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		vParents.addAll(parents);
		List<GTreeNode> result = new ArrayList<>(vParents);
		if (nested != null) {
			result.add(nested);
		}
		result.sort(null);
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o instanceof TypeInfoNode) {
			return type.equals(((TypeInfoNode) o).type);
		}
		return false;
	}

	@Override
	public String getName() {
		return type.getName();
	}

	@Override
	public Icon getIcon(boolean expanded) {
		return modifier.getIcon();
	}

	@Override
	public String getToolTip() {
		return modifier.getModifier() + " " + type.getName();
	}

	@Override
	public boolean isLeaf() {
		return !type.hasParent();
	}

	public ClassTypeInfoDB getType() {
		return type;
	}

	public void typeUpdated(ClassTypeInfoDB type) {
		if (getKey() != type.getKey()) {
			record.setLongValue(TYPE_KEY, type.getKey());
			getManager().updateRecord(record);
		}
		this.type = type;
		modifier = getModifier();
	}

	public Address getAddress() {
		if (type instanceof ArchivedClassTypeInfo) {
			return null;
		}
		return type.getAddress();
	}

	private static enum ModifierType {
		NORMAL,
		ABSTRACT,
		VIRTUAL,
		VIRTUAL_ABSTRACT;

		private static String[] MODIFIERS = new String[]{
			"class",
			"abstract class",
			"virtual base class",
			"virtual abstract base class"
		};

		private static Icon[] ICONS = new Icon[]{
			ResourceManager.loadImage("images/class.png"),
			ResourceManager.loadImage("images/abstract_class.png"),
			ResourceManager.loadImage("images/virtual_class.png"),
			ResourceManager.loadImage("images/virtual_abstract_class.png")
		};

		Icon getIcon() {
			return ICONS[ordinal()];
		}

		String getModifier() {
			return MODIFIERS[ordinal()];
		}
	}

	@Override
	public long getKey() {
		return record.getKey();
	}

	@Override
	public TypeInfoTreeNodeRecord getRecord() {
		return record;
	}

	@Override
	public TypeInfoTreeNodeManager getManager() {
		return type.getManager().getTreeNodeManager();
	}

	@Override
	public void setParent(long key) {
		record.setLongValue(PARENT_KEY, key);
		getManager().updateRecord(record);
	};

}