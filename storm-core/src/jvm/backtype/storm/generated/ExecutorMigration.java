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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2016-12-09")
public class ExecutorMigration implements org.apache.thrift.TBase<ExecutorMigration, ExecutorMigration._Fields>, java.io.Serializable, Cloneable, Comparable<ExecutorMigration> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("ExecutorMigration");

  private static final org.apache.thrift.protocol.TField EXECUTOR_FIELD_DESC = new org.apache.thrift.protocol.TField("executor", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField DESC_IP_FIELD_DESC = new org.apache.thrift.protocol.TField("desc_ip", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField DESC_PORT_FIELD_DESC = new org.apache.thrift.protocol.TField("desc_port", org.apache.thrift.protocol.TType.STRING, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new ExecutorMigrationStandardSchemeFactory());
    schemes.put(TupleScheme.class, new ExecutorMigrationTupleSchemeFactory());
  }

  private ExecutorInfo executor; // optional
  private String desc_ip; // optional
  private String desc_port; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    EXECUTOR((short)1, "executor"),
    DESC_IP((short)2, "desc_ip"),
    DESC_PORT((short)3, "desc_port");

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
        case 1: // EXECUTOR
          return EXECUTOR;
        case 2: // DESC_IP
          return DESC_IP;
        case 3: // DESC_PORT
          return DESC_PORT;
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
  private static final _Fields optionals[] = {_Fields.EXECUTOR,_Fields.DESC_IP,_Fields.DESC_PORT};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.EXECUTOR, new org.apache.thrift.meta_data.FieldMetaData("executor", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, ExecutorInfo.class)));
    tmpMap.put(_Fields.DESC_IP, new org.apache.thrift.meta_data.FieldMetaData("desc_ip", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.DESC_PORT, new org.apache.thrift.meta_data.FieldMetaData("desc_port", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(ExecutorMigration.class, metaDataMap);
  }

  public ExecutorMigration() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ExecutorMigration(ExecutorMigration other) {
    if (other.is_set_executor()) {
      this.executor = new ExecutorInfo(other.executor);
    }
    if (other.is_set_desc_ip()) {
      this.desc_ip = other.desc_ip;
    }
    if (other.is_set_desc_port()) {
      this.desc_port = other.desc_port;
    }
  }

  public ExecutorMigration deepCopy() {
    return new ExecutorMigration(this);
  }

  @Override
  public void clear() {
    this.executor = null;
    this.desc_ip = null;
    this.desc_port = null;
  }

  public ExecutorInfo get_executor() {
    return this.executor;
  }

  public void set_executor(ExecutorInfo executor) {
    this.executor = executor;
  }

  public void unset_executor() {
    this.executor = null;
  }

  /** Returns true if field executor is set (has been assigned a value) and false otherwise */
  public boolean is_set_executor() {
    return this.executor != null;
  }

  public void set_executor_isSet(boolean value) {
    if (!value) {
      this.executor = null;
    }
  }

  public String get_desc_ip() {
    return this.desc_ip;
  }

  public void set_desc_ip(String desc_ip) {
    this.desc_ip = desc_ip;
  }

  public void unset_desc_ip() {
    this.desc_ip = null;
  }

  /** Returns true if field desc_ip is set (has been assigned a value) and false otherwise */
  public boolean is_set_desc_ip() {
    return this.desc_ip != null;
  }

  public void set_desc_ip_isSet(boolean value) {
    if (!value) {
      this.desc_ip = null;
    }
  }

  public String get_desc_port() {
    return this.desc_port;
  }

  public void set_desc_port(String desc_port) {
    this.desc_port = desc_port;
  }

  public void unset_desc_port() {
    this.desc_port = null;
  }

  /** Returns true if field desc_port is set (has been assigned a value) and false otherwise */
  public boolean is_set_desc_port() {
    return this.desc_port != null;
  }

  public void set_desc_port_isSet(boolean value) {
    if (!value) {
      this.desc_port = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case EXECUTOR:
      if (value == null) {
        unset_executor();
      } else {
        set_executor((ExecutorInfo)value);
      }
      break;

    case DESC_IP:
      if (value == null) {
        unset_desc_ip();
      } else {
        set_desc_ip((String)value);
      }
      break;

    case DESC_PORT:
      if (value == null) {
        unset_desc_port();
      } else {
        set_desc_port((String)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case EXECUTOR:
      return get_executor();

    case DESC_IP:
      return get_desc_ip();

    case DESC_PORT:
      return get_desc_port();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case EXECUTOR:
      return is_set_executor();
    case DESC_IP:
      return is_set_desc_ip();
    case DESC_PORT:
      return is_set_desc_port();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof ExecutorMigration)
      return this.equals((ExecutorMigration)that);
    return false;
  }

  public boolean equals(ExecutorMigration that) {
    if (that == null)
      return false;

    boolean this_present_executor = true && this.is_set_executor();
    boolean that_present_executor = true && that.is_set_executor();
    if (this_present_executor || that_present_executor) {
      if (!(this_present_executor && that_present_executor))
        return false;
      if (!this.executor.equals(that.executor))
        return false;
    }

    boolean this_present_desc_ip = true && this.is_set_desc_ip();
    boolean that_present_desc_ip = true && that.is_set_desc_ip();
    if (this_present_desc_ip || that_present_desc_ip) {
      if (!(this_present_desc_ip && that_present_desc_ip))
        return false;
      if (!this.desc_ip.equals(that.desc_ip))
        return false;
    }

    boolean this_present_desc_port = true && this.is_set_desc_port();
    boolean that_present_desc_port = true && that.is_set_desc_port();
    if (this_present_desc_port || that_present_desc_port) {
      if (!(this_present_desc_port && that_present_desc_port))
        return false;
      if (!this.desc_port.equals(that.desc_port))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_executor = true && (is_set_executor());
    list.add(present_executor);
    if (present_executor)
      list.add(executor);

    boolean present_desc_ip = true && (is_set_desc_ip());
    list.add(present_desc_ip);
    if (present_desc_ip)
      list.add(desc_ip);

    boolean present_desc_port = true && (is_set_desc_port());
    list.add(present_desc_port);
    if (present_desc_port)
      list.add(desc_port);

    return list.hashCode();
  }

  @Override
  public int compareTo(ExecutorMigration other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(is_set_executor()).compareTo(other.is_set_executor());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (is_set_executor()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.executor, other.executor);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(is_set_desc_ip()).compareTo(other.is_set_desc_ip());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (is_set_desc_ip()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.desc_ip, other.desc_ip);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(is_set_desc_port()).compareTo(other.is_set_desc_port());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (is_set_desc_port()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.desc_port, other.desc_port);
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
    StringBuilder sb = new StringBuilder("ExecutorMigration(");
    boolean first = true;

    if (is_set_executor()) {
      sb.append("executor:");
      if (this.executor == null) {
        sb.append("null");
      } else {
        sb.append(this.executor);
      }
      first = false;
    }
    if (is_set_desc_ip()) {
      if (!first) sb.append(", ");
      sb.append("desc_ip:");
      if (this.desc_ip == null) {
        sb.append("null");
      } else {
        sb.append(this.desc_ip);
      }
      first = false;
    }
    if (is_set_desc_port()) {
      if (!first) sb.append(", ");
      sb.append("desc_port:");
      if (this.desc_port == null) {
        sb.append("null");
      } else {
        sb.append(this.desc_port);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (executor != null) {
      executor.validate();
    }
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

  private static class ExecutorMigrationStandardSchemeFactory implements SchemeFactory {
    public ExecutorMigrationStandardScheme getScheme() {
      return new ExecutorMigrationStandardScheme();
    }
  }

  private static class ExecutorMigrationStandardScheme extends StandardScheme<ExecutorMigration> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, ExecutorMigration struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // EXECUTOR
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.executor = new ExecutorInfo();
              struct.executor.read(iprot);
              struct.set_executor_isSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // DESC_IP
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.desc_ip = iprot.readString();
              struct.set_desc_ip_isSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // DESC_PORT
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.desc_port = iprot.readString();
              struct.set_desc_port_isSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, ExecutorMigration struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.executor != null) {
        if (struct.is_set_executor()) {
          oprot.writeFieldBegin(EXECUTOR_FIELD_DESC);
          struct.executor.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.desc_ip != null) {
        if (struct.is_set_desc_ip()) {
          oprot.writeFieldBegin(DESC_IP_FIELD_DESC);
          oprot.writeString(struct.desc_ip);
          oprot.writeFieldEnd();
        }
      }
      if (struct.desc_port != null) {
        if (struct.is_set_desc_port()) {
          oprot.writeFieldBegin(DESC_PORT_FIELD_DESC);
          oprot.writeString(struct.desc_port);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class ExecutorMigrationTupleSchemeFactory implements SchemeFactory {
    public ExecutorMigrationTupleScheme getScheme() {
      return new ExecutorMigrationTupleScheme();
    }
  }

  private static class ExecutorMigrationTupleScheme extends TupleScheme<ExecutorMigration> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, ExecutorMigration struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.is_set_executor()) {
        optionals.set(0);
      }
      if (struct.is_set_desc_ip()) {
        optionals.set(1);
      }
      if (struct.is_set_desc_port()) {
        optionals.set(2);
      }
      oprot.writeBitSet(optionals, 3);
      if (struct.is_set_executor()) {
        struct.executor.write(oprot);
      }
      if (struct.is_set_desc_ip()) {
        oprot.writeString(struct.desc_ip);
      }
      if (struct.is_set_desc_port()) {
        oprot.writeString(struct.desc_port);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, ExecutorMigration struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(3);
      if (incoming.get(0)) {
        struct.executor = new ExecutorInfo();
        struct.executor.read(iprot);
        struct.set_executor_isSet(true);
      }
      if (incoming.get(1)) {
        struct.desc_ip = iprot.readString();
        struct.set_desc_ip_isSet(true);
      }
      if (incoming.get(2)) {
        struct.desc_port = iprot.readString();
        struct.set_desc_port_isSet(true);
      }
    }
  }

}

