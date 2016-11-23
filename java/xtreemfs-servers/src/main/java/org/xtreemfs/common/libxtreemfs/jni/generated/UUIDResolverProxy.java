/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 3.0.10
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.xtreemfs.common.libxtreemfs.jni.generated;

public class UUIDResolverProxy {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected UUIDResolverProxy(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(UUIDResolverProxy obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        xtreemfs_jniJNI.delete_UUIDResolverProxy(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  public void uUIDToAddress(String uuid, String[] address) throws org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException, org.xtreemfs.common.libxtreemfs.exceptions.XtreemFSException {
    xtreemfs_jniJNI.UUIDResolverProxy_uUIDToAddress(swigCPtr, this, uuid, address);
  }

  public void volumeNameToMRCUUID(String volume_name, String[] mrc_uuid) throws org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException, org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException, org.xtreemfs.common.libxtreemfs.exceptions.XtreemFSException {
    xtreemfs_jniJNI.UUIDResolverProxy_volumeNameToMRCUUID(swigCPtr, this, volume_name, mrc_uuid);
  }

  public StringVector volumeNameToMRCUUIDs(String volume_name) throws org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException, org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException, org.xtreemfs.common.libxtreemfs.exceptions.XtreemFSException {
    return new StringVector(xtreemfs_jniJNI.UUIDResolverProxy_volumeNameToMRCUUIDs(swigCPtr, this, volume_name), true);
  }

}
