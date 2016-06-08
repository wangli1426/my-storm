/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package backtype.storm.generated;

import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2016-06-02")
public class ExecutorMigrationOptions implements org.apache.thrift.TBase<ExecutorMigrationOptions, ExecutorMigrationOptions._Fields>, java.io.Serializable, Cloneable, Comparable<ExecutorMigrationOptions> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("ExecutorMigrationOptions");

  private static final org.apache.thrift.protocol.TField MIGRATIONS_FIELD_DESC = new org.apache.thrift.protocol.TField("migrations", org.apache.thrift.protocol.TType.LIST, (short)1);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new ExecutorMigrationOptionsStandardSchemeFactory());
    schemes.put(TupleScheme.class, new ExecutorMigrationOptionsTupleSchemeFactory());
  }

  private List<ExecutorMigration> migrations; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    MIGRATIONS((short)1, "migrations");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // MIGRATIONS
          return MIGRATIONS;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.MIGRATIONS};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.MIGRATIONS, new org.apache.thrift.meta_data.FieldMetaData("migrations", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, ExecutorMigration.class))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(ExecutorMigrationOptions.class, metaDataMap);
  }

  public ExecutorMigrationOptions() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ExecutorMigrationOptions(ExecutorMigrationOptions other) {
    if (other.is_set_migrations()) {
      List<ExecutorMigration> __this__migrations = new ArrayList<ExecutorMigration>(other.migrations.size());
      for (ExecutorMigration other_element : other.migrations) {
        __this__migrations.add(new ExecutorMigration(other_element));
      }
      this.migrations = __this__migrations;
    }
  }

  public ExecutorMigrationOptions deepCopy() {
    return new ExecutorMigrationOptions(this);
  }

  @Override
  public void clear() {
    this.migrations = null;
  }

  public int get_migrations_size() {
    return (this.migrations == null) ? 0 : this.migrations.size();
  }

  public java.util.Iterator<ExecutorMigration> get_migrations_iterator() {
    return (this.migrations == null) ? null : this.migrations.iterator();
  }

  public void add_to_migrations(ExecutorMigration elem) {
    if (this.migrations == null) {
      this.migrations = new ArrayList<ExecutorMigration>();
    }
    this.migrations.add(elem);
  }

  public List<ExecutorMigration> get_migrations() {
    return this.migrations;
  }

  public void set_migrations(List<ExecutorMigration> migrations) {
    this.migrations = migrations;
  }

  public void unset_migrations() {
    this.migrations = null;
  }

  /** Returns true if field migrations is set (has been assigned a value) and false otherwise */
  public boolean is_set_migrations() {
    return this.migrations != null;
  }

  public void set_migrations_isSet(boolean value) {
    if (!value) {
      this.migrations = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case MIGRATIONS:
      if (value == null) {
        unset_migrations();
      } else {
        set_migrations((List<ExecutorMigration>)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case MIGRATIONS:
      return get_migrations();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case MIGRATIONS:
      return is_set_migrations();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof ExecutorMigrationOptions)
      return this.equals((ExecutorMigrationOptions)that);
    return false;
  }

  public boolean equals(ExecutorMigrationOptions that) {
    if (that == null)
      return false;

    boolean this_present_migrations = true && this.is_set_migrations();
    boolean that_present_migrations = true && that.is_set_migrations();
    if (this_present_migrations || that_present_migrations) {
      if (!(this_present_migrations && that_present_migrations))
        return false;
      if (!this.migrations.equals(that.migrations))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_migrations = true && (is_set_migrations());
    list.add(present_migrations);
    if (present_migrations)
      list.add(migrations);

    return list.hashCode();
  }

  @Override
  public int compareTo(ExecutorMigrationOptions other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(is_set_migrations()).compareTo(other.is_set_migrations());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (is_set_migrations()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.migrations, other.migrations);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ExecutorMigrationOptions(");
    boolean first = true;

    if (is_set_migrations()) {
      sb.append("migrations:");
      if (this.migrations == null) {
        sb.append("null");
      } else {
        sb.append(this.migrations);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class ExecutorMigrationOptionsStandardSchemeFactory implements SchemeFactory {
    public ExecutorMigrationOptionsStandardScheme getScheme() {
      return new ExecutorMigrationOptionsStandardScheme();
    }
  }

  private static class ExecutorMigrationOptionsStandardScheme extends StandardScheme<ExecutorMigrationOptions> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, ExecutorMigrationOptions struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // MIGRATIONS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list570 = iprot.readListBegin();
                struct.migrations = new ArrayList<ExecutorMigration>(_list570.size);
                ExecutorMigration _elem571;
                for (int _i572 = 0; _i572 < _list570.size; ++_i572)
                {
                  _elem571 = new ExecutorMigration();
                  _elem571.read(iprot);
                  struct.migrations.add(_elem571);
                }
                iprot.readListEnd();
              }
              struct.set_migrations_isSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, ExecutorMigrationOptions struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.migrations != null) {
        if (struct.is_set_migrations()) {
          oprot.writeFieldBegin(MIGRATIONS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, struct.migrations.size()));
            for (ExecutorMigration _iter573 : struct.migrations)
            {
              _iter573.write(oprot);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class ExecutorMigrationOptionsTupleSchemeFactory implements SchemeFactory {
    public ExecutorMigrationOptionsTupleScheme getScheme() {
      return new ExecutorMigrationOptionsTupleScheme();
    }
  }

  private static class ExecutorMigrationOptionsTupleScheme extends TupleScheme<ExecutorMigrationOptions> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, ExecutorMigrationOptions struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.is_set_migrations()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.is_set_migrations()) {
        {
          oprot.writeI32(struct.migrations.size());
          for (ExecutorMigration _iter574 : struct.migrations)
          {
            _iter574.write(oprot);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, ExecutorMigrationOptions struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list575 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, iprot.readI32());
          struct.migrations = new ArrayList<ExecutorMigration>(_list575.size);
          ExecutorMigration _elem576;
          for (int _i577 = 0; _i577 < _list575.size; ++_i577)
          {
            _elem576 = new ExecutorMigration();
            _elem576.read(iprot);
            struct.migrations.add(_elem576);
          }
        }
        struct.set_migrations_isSet(true);
      }
    }
  }

}

