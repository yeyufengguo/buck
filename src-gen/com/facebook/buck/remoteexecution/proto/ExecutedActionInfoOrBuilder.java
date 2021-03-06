// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:ExecutedActionInfoOrBuilder.java.pb.meta")
public interface ExecutedActionInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.ExecutedActionInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>int64 cpu_stat_usage_usec = 1;</code>
   */
  long getCpuStatUsageUsec();

  /**
   * <code>int64 cpu_stat_user_usec = 2;</code>
   */
  long getCpuStatUserUsec();

  /**
   * <code>int64 cpu_stat_system_usec = 3;</code>
   */
  long getCpuStatSystemUsec();

  /**
   * <pre>
   * Whether we should fallback to local retry if this action fails with exit code 1.
   * Fallback means we don't trust if this action failed and it may be flaky.
   * </pre>
   *
   * <code>.google.protobuf.BoolValue is_fallback_enabled_for_completed_action = 4;</code>
   */
  boolean hasIsFallbackEnabledForCompletedAction();
  /**
   * <pre>
   * Whether we should fallback to local retry if this action fails with exit code 1.
   * Fallback means we don't trust if this action failed and it may be flaky.
   * </pre>
   *
   * <code>.google.protobuf.BoolValue is_fallback_enabled_for_completed_action = 4;</code>
   */
  com.google.protobuf.BoolValue getIsFallbackEnabledForCompletedAction();
  /**
   * <pre>
   * Whether we should fallback to local retry if this action fails with exit code 1.
   * Fallback means we don't trust if this action failed and it may be flaky.
   * </pre>
   *
   * <code>.google.protobuf.BoolValue is_fallback_enabled_for_completed_action = 4;</code>
   */
  com.google.protobuf.BoolValueOrBuilder getIsFallbackEnabledForCompletedActionOrBuilder();
}
