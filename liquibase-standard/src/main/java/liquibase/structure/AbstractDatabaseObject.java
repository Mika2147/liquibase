package liquibase.structure;

import liquibase.GlobalConfiguration;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.parser.core.ParsedNode;
import liquibase.parser.core.ParsedNodeException;
import liquibase.resource.ResourceAccessor;
import liquibase.serializer.LiquibaseSerializable;
import liquibase.structure.core.Column;
import liquibase.structure.core.Schema;
import liquibase.util.ISODateFormat;
import liquibase.util.ObjectUtil;
import liquibase.util.StringUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template class for all types of database objects can be manipulated using ChangeSets. Objects represented by
 * subclasses are not specific to any RDBMS and thus only contain "high-level" properties that can be found in most
 * DBMS. Examples for things that are represented are {@link liquibase.structure.core.Table},
 * {@link liquibase.structure.core.PrimaryKey} and {@link liquibase.structure.core.Column}.
 * <p/>
 * Core features of this class include the functionality for the attributes collection ( {@link #getAttributes()} }
 * and the ability to load an object from a serialised form {@link #load(ParsedNode, ResourceAccessor)} .
 */
public abstract class AbstractDatabaseObject implements DatabaseObject {

    private final Map<String, Object> attributes = new HashMap<>();
    private static final String CURLY_BRACKET_REGEX = "(.*)!\\{(.*)\\}";
    public static final Pattern CURLY_BRACKET_PATTERN = Pattern.compile(CURLY_BRACKET_REGEX);

    private String snapshotId;

    @Override
    public String getObjectTypeName() {
        return StringUtil.lowerCaseFirst(getClass().getSimpleName());
    }

    @Override
    public String getSnapshotId() {
        return snapshotId;
    }

    @Override
    public void setSnapshotId(String snapshotId) {
        if (this.snapshotId != null) {
            throw new UnexpectedLiquibaseException("snapshotId already set");
        }
        this.snapshotId = snapshotId;
    }

    @Override
    public boolean snapshotByDefault() {
        return true;
    }

    @Override
    public int compareTo(Object o) {
        AbstractDatabaseObject that = (AbstractDatabaseObject) o;
        if ((this.getSchema() != null) && (that.getSchema() != null)) {
            if (shouldIncludeCatalogInSpecification()) {
                String thisCatalogName = this.getSchema().getCatalogName();
                String thatCatalogName = that.getSchema().getCatalogName();

                if (thisCatalogName != null && thatCatalogName != null) {
                    int compare = thisCatalogName.compareToIgnoreCase(thatCatalogName);
                    if (compare != 0) {
                        return compare;
                    }
                } else if (thisCatalogName != null) {
                    return 1;
                } else if (thatCatalogName != null) {
                    return -1;
                } // if they are both null, it will continue with rest
            }
            // now compare schema name
            int compare = StringUtil.trimToEmpty(this.getSchema().getName()).compareToIgnoreCase(StringUtil.trimToEmpty(that.getSchema().getName()));
            if (compare != 0) {
                return compare;
            }
        }

        String thisName = this.getName();
        String thatName = that.getName();
        if (thisName != null && thatName != null) {
            return thisName.compareTo(thatName);
        } else if (thisName != null) {
            return 1;
        } else if (thatName != null) {
            return -1;
        }
        return 0;
    }

    @Override
    public Set<String> getAttributes() {
        return attributes.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String attribute, Class<T> type) {
        return (T) attributes.get(attribute);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String attribute, T defaultValue) {
        T value = (T) attributes.get(attribute);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

    @Override
    public DatabaseObject setAttribute(String attribute, Object value) {
        if (value == null) {
            attributes.remove(attribute);
        } else {
            attributes.put(attribute, value);
        }
        return this;
    }

    @Override
    public String getSerializedObjectName() {
        return getObjectTypeName();
    }

    @Override
    public String getSerializedObjectNamespace() {
        return STANDARD_SNAPSHOT_NAMESPACE;
    }

    @Override
    public String getSerializableFieldNamespace(String field) {
        return getSerializedObjectNamespace();
    }

    @Override
    public Set<String> getSerializableFields() {
        TreeSet<String> fields = new TreeSet<>(attributes.keySet());
        fields.add("snapshotId");
        return fields;
    }

    @Override
    public Object getSerializableFieldValue(String field) {
        if ("snapshotId".equals(field)) {
            return snapshotId;
        }
        if (!attributes.containsKey(field)) {
            throw new UnexpectedLiquibaseException("Unknown field " + field);
        }
        Object value = attributes.get(field);
        try {
            if (value instanceof Schema) {
                Schema clone = new Schema(((Schema) value).getCatalogName(), ((Schema) value).getName());
                clone.setSnapshotId(((DatabaseObject) value).getSnapshotId());
                return clone;
            } else if (value instanceof DatabaseObject) {
                DatabaseObject clone = (DatabaseObject) value.getClass().getConstructor().newInstance();
                clone.setName(((DatabaseObject) value).getName());
                clone.setSnapshotId(((DatabaseObject) value).getSnapshotId());
                return clone;
            }
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        }

        return value;
    }

    @Override
    public LiquibaseSerializable.SerializationType getSerializableFieldType(String field) {
        return LiquibaseSerializable.SerializationType.NAMED_FIELD;
    }

    @Override
    public void load(ParsedNode parsedNode, ResourceAccessor resourceAccessor) throws ParsedNodeException {
        for (ParsedNode child : parsedNode.getChildren()) {
            String name = child.getName();
            if ("snapshotId".equals(name)) {
                this.snapshotId = child.getValue(String.class);
                continue;
            }

            Class propertyType = ObjectUtil.getPropertyType(this, name);
            if ((propertyType != null) && Collection.class.isAssignableFrom(propertyType) && !(child.getValue()
                instanceof Collection)) {
                if (this.attributes.get(name) == null) {
                    this.setAttribute(name, new ArrayList<Column>());
                }
                this.getAttribute(name, List.class).add(child.getValue());
            } else {
                Object childValue = child.getValue();
                if ((childValue instanceof String)) {
                    Matcher matcher = CURLY_BRACKET_PATTERN.matcher((String) childValue);
                    if (matcher.matches()) {
                        String stringValue = matcher.group(1);
                        try {
                            Class<?> aClass = Class.forName(matcher.group(2));
                            if (Date.class.isAssignableFrom(aClass)) {
                                Date date = new ISODateFormat().parse(stringValue);
                                childValue = aClass.getConstructor(long.class).newInstance(date.getTime());
                            } else if (Enum.class.isAssignableFrom(aClass)) {
                                childValue = Enum.valueOf((Class<? extends Enum>) aClass, stringValue);
                            } else {
                                childValue = aClass.getConstructor(String.class).newInstance(stringValue);
                            }
                        } catch (Exception e) {
                            throw new UnexpectedLiquibaseException(e);
                        }
                    }
                }

                this.attributes.put(name, childValue);
            }
        }
    }

    @Override
    public ParsedNode serialize() {
        throw new RuntimeException("TODO");
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Returns a boolean value indicating whether the object types should include the catalog name in their specification.
     * This method checks the current value of the {@code INCLUDE_CATALOG_IN_SPECIFICATION} setting in the
     * {@link  GlobalConfiguration}.
     *
     * @return {@code true} if the object types should include the catalog name in their specification, otherwise {@code false}.
     */
    public boolean shouldIncludeCatalogInSpecification() {
        return GlobalConfiguration.INCLUDE_CATALOG_IN_SPECIFICATION.getCurrentValue();
    }
}
